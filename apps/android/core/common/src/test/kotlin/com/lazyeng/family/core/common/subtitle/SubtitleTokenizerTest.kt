package com.lazyeng.family.core.common.subtitle

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SubtitleTokenizerTest {
    @Test fun contractionsPossessivesHyphensAndDottedAbbreviationsRemainSingleWords() {
        val text = "I'm  here; don't forget Alex's well-known U.S.A. map, e.g. U.K!"
        val words = checked(text).filter { it.kind == SubtitleTokenKind.WORD }
        assertEquals(listOf("I'm", "here", "don't", "forget", "Alex's", "well-known", "U.S.A.", "map", "e.g.", "U.K"), words.map { it.surface })
        assertEquals("i'm", words[0].normalized)
        assertEquals("alex's", words[4].normalized)
        assertEquals("u.s.a.", words[6].normalized)
    }
    @Test fun curlyQuotesAndUnicodeHyphensNormalizeWithoutChangingOffsets() {
        val text = "“I’m… don’t; James’s well\u2011known idea!”"
        assertEquals(listOf("i'm", "don't", "james's", "well-known", "idea"), checked(text)
            .filter { it.kind == SubtitleTokenKind.WORD }.map { it.normalized })
        assertTrue(checked(text).any { it.surface == "…" && it.kind == SubtitleTokenKind.PUNCTUATION })
    }
    @Test fun surrogatePairsCombiningMarksNumbersAndMixedTextKeepExactUtf16Ranges() {
        val text = "\uD83D\uDE00 Cafe\u0301, 12.5 中文\nhello!"
        val tokens = checked(text)
        assertEquals(2, tokens.first().end)
        assertEquals(3, tokens[1].start)
        assertEquals("café", tokens[1].normalized)
        assertTrue(tokens.any { it.surface == "12.5" && it.kind == SubtitleTokenKind.NUMBER })
        assertTrue(tokens.any { it.surface == "中" && it.kind == SubtitleTokenKind.OTHER })
    }
    @Test fun noGuessingOfInflectionOrContractionExpansion() {
        val words = checked("Alice's children went; she's ready.").filter { it.kind == SubtitleTokenKind.WORD }
        assertEquals(listOf("alice's", "children", "went", "she's", "ready"), words.map { it.normalized })
    }
    @Test fun emptyWhitespaceAndRepeatedPunctuationAreNotPhantomWords() {
        assertTrue(checked("").isEmpty())
        assertTrue(checked(" \t\n\u00A0").isEmpty())
        assertTrue(checked("?!... --").none { it.kind == SubtitleTokenKind.WORD })
        assertEquals(listOf("two", "words"), checked("two    words").map { it.normalized })
    }
    @Test fun normalizationDoesNotDependOnDeviceLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("i'm", checked("I'M").single().normalized)
        } finally { Locale.setDefault(old) }
    }
    private fun checked(text: String): List<SubtitleToken> {
        val tokens = SubtitleTokenizer.tokenize(text)
        tokens.forEach { assertEquals(it.surface, text.substring(it.start, it.end)) }
        tokens.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
        return tokens
    }
}
