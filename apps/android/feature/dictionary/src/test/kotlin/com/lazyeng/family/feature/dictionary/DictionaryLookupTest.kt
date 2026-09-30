package com.lazyeng.family.feature.dictionary

import com.lazyeng.family.core.model.DictionaryEntry
import com.lazyeng.family.core.model.DictionaryRepository
import com.lazyeng.family.core.testing.TestDispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryLookupTest {
    @Test
    fun irregularAndRegularFormsResolveOnlyToAvailableDictionaryEntries() = runTest(TestDispatchers().scheduler) {
        val repo = FakeDictionaryRepository(mapOf("run" to entry("run"), "good" to entry("good"), "make" to entry("make"), "child" to entry("child")))
        val useCase = LocalDictionaryLookupUseCase(repo)
        listOf("running" to "run", "ran" to "run", "better" to "good", "making" to "make", "children's" to "child").forEach { (surface, lemma) ->
            val selection = (DictionarySelectionParser.fromRange(surface, 0, surface.length) as SelectionResult.Accepted).selection
            val result = useCase(selection)
            assertEquals(surface, result.surface)
            assertEquals(lemma, result.matchedLemma)
            assertNotNullEntry(result.entry)
        }
    }

    @Test
    fun inflectedFormsPreferContextualLemmaOverAmbiguousSurfaceHeadword() = runTest(TestDispatchers().scheduler) {
        val repo = FakeDictionaryRepository(mapOf(
            "running" to entry("running"), "run" to entry("run"),
            "jumped" to entry("jumped"), "jump" to entry("jump"),
        ))
        val useCase = LocalDictionaryLookupUseCase(repo)
        val progressive = "She is running home."
        val running = useCase(progressive, progressive.indexOf("running"), progressive.indexOf("running") + 7)!!
        val past = "They jumped home."
        val jumped = useCase(past, past.indexOf("jumped"), past.indexOf("jumped") + 6)!!

        assertEquals("run", running.matchedLemma)
        assertEquals("run", running.entry?.lemma)
        assertEquals("jump", jumped.matchedLemma)
        assertEquals("jump", jumped.entry?.lemma)
    }

    @Test
    fun contractionsAndHyphenatedTermsKeepSurfaceAndTryDeterministicForms() = runTest(TestDispatchers().scheduler) {
        val repo = FakeDictionaryRepository(mapOf("do" to entry("do"), "well-known" to entry("well-known")))
        val useCase = LocalDictionaryLookupUseCase(repo)
        val dont = useCase((DictionarySelectionParser.fromRange("don't", 0, 5) as SelectionResult.Accepted).selection)
        assertEquals("don't", dont.surface)
        assertEquals("do", dont.matchedLemma)
        val hyphenated = useCase((DictionarySelectionParser.fromRange("well-known", 0, 10) as SelectionResult.Accepted).selection)
        assertEquals("well-known", hyphenated.matchedLemma)
        assertEquals(listOf("i'm", "i", "be"), EnglishLemmaCandidates.candidates("I'm"))
        assertEquals(listOf("it's", "it", "be"), EnglishLemmaCandidates.candidates("it's"))
        assertEquals(listOf("children's", "child"), EnglishLemmaCandidates.candidates("children's"))
        assertEquals(listOf("dogs'", "dog"), EnglishLemmaCandidates.candidates("dogs'"))
    }

    @Test
    fun titleCaseProperNounIsNeverMorphologicallyLowered() = runTest(TestDispatchers().scheduler) {
        val repo = FakeDictionaryRepository(mapOf("run" to entry("run")))
        val sentence = "They met Runnington yesterday"
        val start = sentence.indexOf("Runnington")
        val result = LocalDictionaryLookupUseCase(repo)(sentence, start, start + "Runnington".length)!!
        assertEquals(LookupSelectionKind.PROPER_NOUN, result.kind)
        assertNull(result.entry)
        assertEquals(listOf("Runnington"), repo.queries)
        assertEquals(listOf("James'"), EnglishLemmaCandidates.candidates("James'", LookupSelectionKind.PROPER_NOUN))
        val name = "James' book"
        val nameSelection = DictionarySelectionParser.fromRange(name, 0, 6) as SelectionResult.Accepted
        assertEquals(LookupSelectionKind.PROPER_NOUN, nameSelection.selection.kind)
    }

    @Test
    fun numericWordsAndSpellingMissesRemainVisibleWithoutFuzzyReplacement() = runTest(TestDispatchers().scheduler) {
        val repo = FakeDictionaryRepository(mapOf("elephant" to entry("elephant")))
        val useCase = LocalDictionaryLookupUseCase(repo)
        val number = useCase("42", 0, 2)!!
        assertEquals(LookupMissReason.NUMBER, number.missReason)
        assertEquals("42", number.surface)
        assertTrue(repo.queries.isEmpty())

        val typo = useCase("elefant", 0, 7)!!
        assertEquals("elefant", typo.surface)
        assertEquals(LookupMissReason.NOT_FOUND, typo.missReason)
        assertTrue(typo.spellingMayBeIncorrect)
        assertFalse(repo.queries.contains("elephant"))
    }

    @Test
    fun knownPhrasalVerbIsAcceptedButArbitraryAndOverlongRangesAreNotQueried() = runTest(TestDispatchers().scheduler) {
        val repo = FakeDictionaryRepository(mapOf("give up" to entry("give up")))
        val useCase = LocalDictionaryLookupUseCase(repo)
        val phrase = "give up"
        val parsed = DictionarySelectionParser.fromRange(phrase, 0, phrase.length) as SelectionResult.Accepted
        assertEquals(LookupSelectionKind.PHRASE, parsed.selection.kind)
        assertEquals("give up", useCase(parsed.selection).matchedLemma)

        val arbitrary = "bright green elephant"
        val rejected = useCase(arbitrary, 0, arbitrary.length)!!
        assertEquals(LookupSelectionKind.UNSUPPORTED_PHRASE, rejected.kind)
        assertEquals(arbitrary, rejected.surface)
        val long = "this is far too many words to search as one phrase"
        val longResult = useCase(long, 0, long.length)!!
        assertEquals(LookupMissReason.SELECTION_TOO_LONG, longResult.missReason)
        assertEquals(long, longResult.surface)
        assertFalse(repo.queries.contains(arbitrary))
        assertFalse(repo.queries.contains(long))
    }

    @Test
    fun sourceOffsetsAreExactAndUnmatchedResultRetainsFullSentenceForAi() = runTest(TestDispatchers().scheduler) {
        val sentence = "😀 I saw the elefant!"
        val start = sentence.indexOf("elefant")
        val end = start + "elefant".length
        val parsed = DictionarySelectionParser.fromRange(sentence, start, end) as SelectionResult.Accepted
        assertEquals("elefant", sentence.substring(parsed.selection.start, parsed.selection.end))
        val result = LocalDictionaryLookupUseCase(FakeDictionaryRepository(emptyMap()))(parsed.selection)
        assertEquals("elefant", result.surface)
        assertEquals(sentence, result.sentence)
        assertEquals(start, result.start)
        assertEquals(end, result.end)
        assertNull(result.entry)
    }

    private fun entry(lemma: String) = DictionaryEntry(lemma, "n.", null, listOf("sample"), null, null)

    private fun assertNotNullEntry(entry: DictionaryEntry?) {
        assertTrue("expected a dictionary hit", entry != null)
    }

    private class FakeDictionaryRepository(private val entries: Map<String, DictionaryEntry>) : DictionaryRepository {
        val queries = mutableListOf<String>()
        override suspend fun findEntry(lemma: String): DictionaryEntry? {
            queries += lemma
            return entries[lemma.lowercase()]
        }
    }
}
