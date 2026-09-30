package com.lazyeng.family.feature.dictionary

import com.lazyeng.family.core.common.subtitle.SubtitleTokenKind
import com.lazyeng.family.core.common.subtitle.SubtitleTokenizer
import com.lazyeng.family.core.model.DictionaryEntry
import com.lazyeng.family.core.model.DictionaryRepository
import java.util.Locale

enum class LookupSelectionKind { WORD, PHRASE, NUMBER, PROPER_NOUN, UNSUPPORTED_PHRASE, TOO_LONG }

data class DictionarySelection(
    val surface: String,
    val queryForm: String,
    val sentence: String,
    val start: Int,
    val end: Int,
    val kind: LookupSelectionKind,
)

sealed interface SelectionResult {
    data class Accepted(val selection: DictionarySelection) : SelectionResult
    data class Rejected(val surface: String, val sentence: String, val start: Int, val end: Int, val reason: LookupSelectionKind) : SelectionResult
}

/** Builds selections from the shared tokenizer's UTF-16, end-exclusive source ranges. */
object DictionarySelectionParser {
    private const val MAX_PHRASE_WORDS = 4
    private val knownPhrases = setOf(
        "give up", "look after", "look for", "look at", "look into", "turn on", "turn off",
        "take off", "get up", "find out", "pick up", "put on", "come back", "go on",
    )
    private val contractions = setOf("i'm", "it's", "he's", "she's", "we're", "they're", "you're")

    fun fromRange(sentence: String, start: Int, end: Int): SelectionResult {
        if (start < 0 || end <= start || end > sentence.length) {
            return SelectionResult.Rejected("", sentence, start, end, LookupSelectionKind.UNSUPPORTED_PHRASE)
        }
        var left = start
        var right = end
        while (left < right && sentence[left].isWhitespace()) left++
        while (right > left && sentence[right - 1].isWhitespace()) right--
        val surface = sentence.substring(left, right)
        if (surface.isEmpty()) return SelectionResult.Rejected(surface, sentence, left, right, LookupSelectionKind.UNSUPPORTED_PHRASE)

        val tokens = SubtitleTokenizer.tokenize(sentence).filter { it.start >= left && it.end <= right }
        val lexical = tokens.filter { it.kind == SubtitleTokenKind.WORD || it.kind == SubtitleTokenKind.NUMBER }
        if (lexical.isEmpty()) return SelectionResult.Rejected(surface, sentence, left, right, LookupSelectionKind.UNSUPPORTED_PHRASE)
        if (lexical.size > MAX_PHRASE_WORDS) return SelectionResult.Rejected(surface, sentence, left, right, LookupSelectionKind.TOO_LONG)
        if (tokens.any { it.kind != SubtitleTokenKind.WORD && it.kind != SubtitleTokenKind.NUMBER && it.kind != SubtitleTokenKind.PUNCTUATION }) {
            return SelectionResult.Rejected(surface, sentence, left, right, LookupSelectionKind.UNSUPPORTED_PHRASE)
        }
        if (lexical.size == 1 && lexical.single().kind == SubtitleTokenKind.NUMBER) {
            return SelectionResult.Accepted(DictionarySelection(surface, lexical.single().surface, sentence, left, right, LookupSelectionKind.NUMBER))
        }

        val normalizedWords = lexical.map { it.normalized }
        if (normalizedWords.size == 1) {
            val token = lexical.single()
            val proper = isLikelyProperNoun(surface, token.start, sentence, token.normalized)
            return SelectionResult.Accepted(
                DictionarySelection(surface, token.normalized, sentence, left, right,
                    if (proper) LookupSelectionKind.PROPER_NOUN else LookupSelectionKind.WORD),
            )
        }

        val phrase = normalizedWords.joinToString(" ")
        if (phrase !in knownPhrases) {
            return SelectionResult.Rejected(surface, sentence, left, right, LookupSelectionKind.UNSUPPORTED_PHRASE)
        }
        return SelectionResult.Accepted(DictionarySelection(surface, phrase, sentence, left, right, LookupSelectionKind.PHRASE))
    }

    private fun isLikelyProperNoun(surface: String, start: Int, sentence: String, normalized: String): Boolean {
        if (normalized in contractions) return false
        val letters = surface.filter(Char::isLetter)
        if (letters.isEmpty()) return false
        val isTitleCase = letters.first().isUpperCase() && letters.drop(1).any(Char::isLowerCase)
        val sentenceStart = sentence.take(start).none(Char::isLetterOrDigit)
        return letters.all(Char::isUpperCase) || (isTitleCase && !sentenceStart) || surface.endsWith("'") || surface.endsWith("’")
    }
}

/** Ordered, deterministic candidate generation. A candidate is accepted only if ECDICT contains it. */
object EnglishLemmaCandidates {
    private val irregular = mapOf(
        "ran" to listOf("run"), "went" to listOf("go"), "gone" to listOf("go"), "did" to listOf("do"),
        "does" to listOf("do"), "was" to listOf("be"), "were" to listOf("be"), "is" to listOf("be"),
        "are" to listOf("be"), "am" to listOf("be"), "been" to listOf("be"), "had" to listOf("have"),
        "has" to listOf("have"), "having" to listOf("have"), "children" to listOf("child"),
        "men" to listOf("man"), "women" to listOf("woman"), "mice" to listOf("mouse"), "geese" to listOf("goose"),
        "feet" to listOf("foot"), "teeth" to listOf("tooth"), "better" to listOf("good"), "best" to listOf("good"),
        "worse" to listOf("bad"), "worst" to listOf("bad"), "bought" to listOf("buy"), "taught" to listOf("teach"),
        "thought" to listOf("think"), "caught" to listOf("catch"), "brought" to listOf("bring"),
        "made" to listOf("make"), "ate" to listOf("eat"), "eaten" to listOf("eat"), "saw" to listOf("see"),
        "seen" to listOf("see"), "wrote" to listOf("write"), "written" to listOf("write"),
        "took" to listOf("take"), "taken" to listOf("take"), "spoke" to listOf("speak"), "spoken" to listOf("speak"),
    )
    private val contractions = mapOf(
        "i'm" to listOf("i", "be"), "you're" to listOf("you", "be"), "we're" to listOf("we", "be"),
        "they're" to listOf("they", "be"), "he's" to listOf("he", "be"), "she's" to listOf("she", "be"),
        "it's" to listOf("it", "be"), "don't" to listOf("do"), "doesn't" to listOf("do"), "didn't" to listOf("do"),
        "can't" to listOf("can"), "couldn't" to listOf("could"), "won't" to listOf("will"),
        "wouldn't" to listOf("would"), "isn't" to listOf("be"), "aren't" to listOf("be"),
        "wasn't" to listOf("be"), "weren't" to listOf("be"), "hasn't" to listOf("have"),
        "haven't" to listOf("have"), "hadn't" to listOf("have"), "I've".lowercase(Locale.ROOT) to listOf("i", "have"),
        "we've" to listOf("we", "have"), "they've" to listOf("they", "have"), "I'll".lowercase(Locale.ROOT) to listOf("i", "will"),
        "we'll" to listOf("we", "will"), "they'll" to listOf("they", "will"),
    )

    fun candidates(surface: String, selectionKind: LookupSelectionKind = LookupSelectionKind.WORD): List<String> {
        val normalized = normalize(surface)
        if (normalized.isEmpty() || selectionKind == LookupSelectionKind.NUMBER || selectionKind == LookupSelectionKind.PHRASE ||
            selectionKind == LookupSelectionKind.UNSUPPORTED_PHRASE || selectionKind == LookupSelectionKind.TOO_LONG) return emptyList()
        if (selectionKind == LookupSelectionKind.PROPER_NOUN) return listOf(surface.trim())
        contractions[normalized]?.let { return distinct(normalized, *it.toTypedArray()) }

        val possessive = normalize(surface)
        if (possessive.endsWith("'s") && possessive !in contractions) {
            val base = possessive.dropLast(2)
            val forms = candidates(base)
            return listOf(possessive, *(forms.drop(1).ifEmpty { forms }).toTypedArray()).distinct()
        }
        if (possessive.endsWith("'") && possessive.length > 1) {
            val base = possessive.dropLast(1)
            val forms = candidates(base)
            return listOf(possessive, *(forms.drop(1).ifEmpty { forms }).toTypedArray()).distinct()
        }
        irregular[normalized]?.let { return distinct(normalized, *it.toTypedArray()) }

        val result = linkedSetOf(normalized)
        fun add(value: String) { if (value.length > 1) result += value }
        val word = normalized
        when {
            word.endsWith("ies") && word.length > 4 -> add(word.dropLast(3) + "y")
            word.endsWith("ied") && word.length > 4 -> add(word.dropLast(3) + "y")
            word.endsWith("ing") && word.length > 5 -> {
                val stem = word.dropLast(3)
                if (isDoubledFinalConsonant(stem)) add(stem.dropLast(1))
                add(stem)
                add(stem + "e")
            }
            word.endsWith("ed") && word.length > 4 -> {
                val stem = word.dropLast(2)
                if (isDoubledFinalConsonant(stem)) add(stem.dropLast(1))
                add(stem)
                add(stem + "e")
            }
            word.endsWith("est") && word.length > 5 -> {
                val stem = word.dropLast(3)
                if (stem.endsWith("i")) add(stem.dropLast(1) + "y")
                if (isDoubledFinalConsonant(stem)) add(stem.dropLast(1))
                add(stem)
                add(stem + "e")
            }
            word.endsWith("er") && word.length > 4 -> {
                val stem = word.dropLast(2)
                if (stem.endsWith("i")) add(stem.dropLast(1) + "y")
                if (isDoubledFinalConsonant(stem)) add(stem.dropLast(1))
                add(stem)
                add(stem + "e")
            }
            word.endsWith("es") && word.length > 4 -> {
                add(word.dropLast(2))
                add(word.dropLast(1))
            }
            word.endsWith("s") && word.length > 3 -> add(word.dropLast(1))
        }
        return result.toList()
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace('\u2019', '\'').replace('\u2018', '\'').replace('\u02bc', '\'')
        .replace('\u2010', '-').replace('\u2011', '-')

    private fun isDoubledFinalConsonant(value: String): Boolean = value.length > 2 &&
        value.last() == value[value.lastIndex - 1] && value.last() in "bcdfghjklmnpqrstvwxyz"

    private fun distinct(first: String, vararg rest: String): List<String> = (listOf(first) + rest).distinct()
}

data class LocalDictionaryLookup(
    val surface: String,
    val sentence: String,
    val start: Int,
    val end: Int,
    val kind: LookupSelectionKind,
    val matchedLemma: String?,
    val entry: DictionaryEntry?,
    val missReason: LookupMissReason?,
    val spellingMayBeIncorrect: Boolean,
)

enum class LookupMissReason { NOT_FOUND, NUMBER, UNSUPPORTED_PHRASE, SELECTION_TOO_LONG }

class LocalDictionaryLookupUseCase(private val dictionary: DictionaryRepository) {
    suspend operator fun invoke(selection: DictionarySelection): LocalDictionaryLookup {
        val reason = when (selection.kind) {
            LookupSelectionKind.NUMBER -> LookupMissReason.NUMBER
            LookupSelectionKind.UNSUPPORTED_PHRASE -> LookupMissReason.UNSUPPORTED_PHRASE
            LookupSelectionKind.TOO_LONG -> LookupMissReason.SELECTION_TOO_LONG
            else -> null
        }
        if (reason != null) return selection.result(null, null, reason, false)

        val candidateInput = if (selection.kind == LookupSelectionKind.PROPER_NOUN) selection.surface else selection.queryForm
        val candidates = if (selection.kind == LookupSelectionKind.PHRASE) listOf(selection.queryForm)
            else orderedCandidates(selection, candidateInput)
        for (candidate in candidates) {
            val entry = dictionary.findEntry(candidate)
            if (entry != null) return selection.result(candidate, entry, null, false)
        }
        return selection.result(null, null, LookupMissReason.NOT_FOUND, true)
    }

    private fun orderedCandidates(selection: DictionarySelection, input: String): List<String> {
        val candidates = EnglishLemmaCandidates.candidates(input, selection.kind)
        if (selection.kind != LookupSelectionKind.WORD || candidates.size < 2) return candidates
        val normalized = input.lowercase(Locale.ROOT)
        val hasInflectionContext = normalized.endsWith("ed") ||
            (normalized.endsWith("ing") && Regex("\\b(?:am|is|are|was|were|be|been|being)\\s+$", RegexOption.IGNORE_CASE)
                .containsMatchIn(selection.sentence.substring(0, selection.start.coerceIn(0, selection.sentence.length))))
        return if (hasInflectionContext) candidates.drop(1) + candidates.first() else candidates
    }

    suspend operator fun invoke(sentence: String, start: Int, end: Int): LocalDictionaryLookup? {
        return when (val result = DictionarySelectionParser.fromRange(sentence, start, end)) {
            is SelectionResult.Accepted -> invoke(result.selection)
            is SelectionResult.Rejected -> LocalDictionaryLookup(
                result.surface, result.sentence, result.start, result.end, result.reason,
                null, null, if (result.reason == LookupSelectionKind.TOO_LONG) LookupMissReason.SELECTION_TOO_LONG else LookupMissReason.UNSUPPORTED_PHRASE,
                spellingMayBeIncorrect = false,
            )
        }
    }

    private fun DictionarySelection.result(
        matchedLemma: String?, entry: DictionaryEntry?, missReason: LookupMissReason?, spellingMayBeIncorrect: Boolean,
    ) = LocalDictionaryLookup(surface, sentence, start, end, kind, matchedLemma, entry, missReason, spellingMayBeIncorrect)
}
