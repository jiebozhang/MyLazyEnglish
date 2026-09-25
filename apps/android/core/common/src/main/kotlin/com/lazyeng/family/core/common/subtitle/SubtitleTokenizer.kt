package com.lazyeng.family.core.common.subtitle

import java.text.Normalizer
import java.util.Locale

enum class SubtitleTokenKind { WORD, NUMBER, PUNCTUATION, OTHER }
/** Offsets are UTF-16, end-exclusive, matching Kotlin String/Compose character ranges. */
data class SubtitleToken(val surface: String, val normalized: String, val start: Int, val end: Int, val kind: SubtitleTokenKind)

object SubtitleTokenizer {
    private const val LETTERS = "[\\p{IsLatin}][\\p{IsLatin}\\p{M}]*"
    private val words = Regex("(?:[A-Za-z]\\.)+[A-Za-z]\\.?|$LETTERS(?:['\u2019\u2018\u02BC\\-\u2010\u2011]$LETTERS)*")
    private val numbers = Regex("[0-9]+(?:[.,][0-9]+)*")
    private val pieces = Regex("${words.pattern}|${numbers.pattern}|[^\\s\\p{Z}]")

    /** Lookup normalization is not lemmatization: never guess that 's means is/has/possession. */
    fun tokenize(text: String): List<SubtitleToken> = pieces.findAll(text).map { match ->
        val surface = match.value
        val kind = when {
            words.matches(surface) -> SubtitleTokenKind.WORD
            numbers.matches(surface) -> SubtitleTokenKind.NUMBER
            surface.codePoints().allMatch { Character.getType(it) in punctuationTypes } -> SubtitleTokenKind.PUNCTUATION
            else -> SubtitleTokenKind.OTHER
        }
        val normalized = if (kind == SubtitleTokenKind.WORD) Normalizer.normalize(surface, Normalizer.Form.NFC)
            .lowercase(Locale.ROOT).replace('\u2019', '\'').replace('\u2018', '\'').replace('\u02BC', '\'')
            .replace('\u2010', '-').replace('\u2011', '-') else surface
        SubtitleToken(surface, normalized, match.range.first, match.range.last + 1, kind)
    }.toList()

    private val punctuationTypes = setOf(Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())
}
