package com.lazyeng.family.core.common.subtitle

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SubtitleParserTest {
    private fun file(name: String) = checkNotNull(javaClass.getResourceAsStream("/subtitles/$name")).use { it.readBytes() }
    private fun parse(name: String, encoding: SubtitleEncoding? = null) = SubtitleParser.parse(file(name),
        if (name.endsWith("vtt")) SubtitleFormat.VTT else SubtitleFormat.SRT, encoding)
    private fun success(name: String, encoding: SubtitleEncoding? = null) = (parse(name, encoding) as SubtitleParseResult.Success).subtitle
    private fun failure(name: String) = (parse(name) as SubtitleParseResult.Failure).report

    @Test fun standardFormatsHaveIdenticalMillisecondCues() {
        val srt = success("standard.srt")
        assertEquals(srt.cues, success("standard.vtt").cues)
        assertEquals(listOf(ParsedCue(1, 1250, 3500, "The blue box is open."),
            ParsedCue(2, 62003, 64999, "A small light is on.")), srt.cues)
        assertEquals(SubtitleParser.VERSION, srt.parserVersion)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(file("standard.srt")).joinToString("") { "%02x".format(it.toInt() and 255) }, srt.sourceHash)
    }
    @Test fun bomCrLfAndBilingualAreDecodedWithoutLosingBody() {
        val expected = success("bilingual.srt").cues
        assertTrue(expected.first().text.contains("蓝色盒子"))
        for (name in listOf("bom.srt", "crlf.srt", "utf16le.srt", "utf16be.srt")) assertEquals(name, expected, success(name).cues)
        assertTrue(success("bom.srt").hadBom)
        assertFalse(success("crlf.srt").hadBom)
        assertEquals(SubtitleEncoding.UTF16_LE, success("utf16le.srt").encoding)
        assertEquals(SubtitleEncoding.UTF16_BE, success("utf16be.srt").encoding)
        val crlf = file("crlf.srt")
        assertTrue(crlf.indices.any { it > 0 && crlf[it] == 10.toByte() && crlf[it - 1] == 13.toByte() })
    }
    @Test fun gbkCandidateRequiresExplicitChoiceRatherThanGuessing() {
        assertEquals(SubtitleIssue.ENCODING_UNCERTAIN, failure("gbk.srt").issue)
        assertEquals(listOf(SubtitleEncoding.GBK), failure("gbk.srt").encodingCandidates)
        assertEquals(success("bilingual.srt").cues, success("gbk.srt", SubtitleEncoding.GBK).cues)
        assertEquals(SubtitleEncoding.GBK, success("gbk.srt", SubtitleEncoding.GBK).encoding)
        assertTrue(parse("invalid-encoding.srt", SubtitleEncoding.UTF8) is SubtitleParseResult.Failure)
        assertTrue(parse("bom.srt", SubtitleEncoding.GBK) is SubtitleParseResult.Failure)
    }
    @Test fun multilineWhitespaceAndSortingAreDeterministic() {
        assertEquals("The blue box is open.\nThere is a small light.", success("multiline.srt").cues.first().text)
        val expected = success("whitespace.srt")
        assertEquals(listOf(1, 2), expected.cues.map { it.sequence })
        assertEquals("First example.", expected.cues.first().text)
        repeat(30) { assertEquals(expected, success("whitespace.srt")) }
    }
    @Test fun supportedStylesAreStrippedConsistentlyAndBodyIsPreserved() {
        val expected = listOf("The blue box is open.", "A small light.")
        for (name in listOf("tags.srt", "tags.vtt")) assertEquals(expected, success(name).cues.map { it.text })
        assertEquals(SubtitleIssue.MARKUP_UNSUPPORTED, failure("unsupported-tag.srt").issue)
        assertEquals(listOf(ParsedCue(1, 1250, 3500, "Spoken text.")), success("notes.vtt").cues)
        assertEquals(SubtitleIssue.VTT_LAYOUT_UNSUPPORTED, failure("layout.vtt").issue)
    }
    @Test fun malformedFilesFailEntirelyWithLineNumbersAndActionableReports() {
        for (name in listOf("bad-range.srt", "bad-format.srt", "missing-arrow.srt", "bad-seconds.vtt", "overflow.srt")) {
            val report = failure(name)
            assertEquals(name, SubtitleIssue.TIMESTAMP_INVALID, report.issue)
            assertTrue(report.fileLine!! > 0)
            assertTrue(report.userMessage.contains("请检查"))
            assertEquals("SUBTITLE_TIMESTAMP_INVALID", report.error.code.value)
            assertNull(report.error.diagnostic)
        }
        assertEquals(6, failure("bad-range.srt").fileLine)
        assertEquals(SubtitleIssue.SEQUENCE_INVALID, failure("duplicate.srt").issue)
        assertEquals(SubtitleIssue.FORMAT_INVALID, failure("missing-separator.srt").issue)
        assertEquals(SubtitleIssue.ENCODING_UNCERTAIN, failure("invalid-encoding.srt").issue)
    }
    @Test fun semanticTimelineIsNotSilentlyRewritten() {
        val cue = success("reversed.srt").cues.single()
        assertEquals(3500L, cue.startMs)
        assertEquals(1250L, cue.endMs)
    }
}
