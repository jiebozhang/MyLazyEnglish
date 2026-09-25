package com.lazyeng.family.core.common.subtitle

import com.lazyeng.family.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TimelineValidatorTest {
    private fun line(seq: Int, start: Long, end: Long, text: String = "Synthetic sentence.") =
        SubtitleLine(SubtitleLineId("line-$seq"), SubtitleVersionId("version"), seq, start, end, text)

    @Test fun normalSequenceAndTouchingBoundariesHaveNoFindings() {
        val lines = listOf(line(1, 0, 1000), line(2, 1000, 2000))
        assertEquals(emptyList<TimelineFinding>(), TimelineValidator.validate(lines).findings)
        assertEquals(TimelineValidator.validate(lines), TimelineValidator.validate(lines.reversed()))
    }
    @Test fun negativeReversedAndZeroDurationAreBlockingAndLocated() {
        val cases = listOf(line(1, -1, 1000) to TimelineIssue.NEGATIVE_TIME,
            line(2, 2000, 1000) to TimelineIssue.REVERSED_TIME, line(3, 1000, 1000) to TimelineIssue.ZERO_DURATION)
        for ((line, issue) in cases) {
            val report = TimelineValidator.validate(listOf(line))
            assertFalse(report.canPublish)
            val finding = report.findings.single { it.issue == issue }
            assertEquals(TimelineSeverity.ERROR, finding.severity)
            assertEquals(line.id, finding.lineId)
            assertEquals(line.sequence, finding.sequence)
            assertEquals("SUBTITLE_${issue.name}", finding.error.code.value)
        }
    }
    @Test fun nestedOverlapAndOutOfOrderAreWarningsNotAutomaticRepairs() {
        val lines = listOf(line(1, 0, 9000), line(2, 4000, 5000), line(3, 2000, 3000))
        val original = lines.toList()
        val report = TimelineValidator.validate(lines)
        assertTrue(report.canPublish)
        assertEquals(2, report.findings.count { it.issue == TimelineIssue.OVERLAP })
        assertEquals(1, report.findings.count { it.issue == TimelineIssue.OUT_OF_ORDER })
        assertTrue(report.findings.filter { it.issue == TimelineIssue.OVERLAP }.all { it.relatedLineId == lines[0].id })
        assertEquals(original, lines)
    }
    @Test fun thresholdBoundariesAreExplicitAndConfigurable() {
        val policy = TimelinePolicy(300, 1000, 100, 4)
        assertTrue(TimelineValidator.validate(listOf(line(1, 0, 300, "text")), policy).findings.isEmpty())
        assertTrue(TimelineValidator.validate(listOf(line(1, 0, 1000, "text")), policy).findings.isEmpty())
        assertEquals(TimelineIssue.TOO_SHORT, TimelineValidator.validate(listOf(line(1, 0, 299, "text")), policy).findings.single().issue)
        assertEquals(setOf(TimelineIssue.TOO_LONG, TimelineIssue.LONG_TEXT),
            TimelineValidator.validate(listOf(line(1, 0, 1001, "texts")), policy).findings.map { it.issue }.toSet())
        assertFalse(TimelineValidator.validate(listOf(line(1, 0, 1000), line(2, 900, 1500)), policy).findings.any { it.issue == TimelineIssue.OVERLAP })
        assertTrue(TimelineValidator.validate(listOf(line(1, 0, 1000), line(2, 899, 1500)), policy).findings.any { it.issue == TimelineIssue.OVERLAP })
    }
    @Test fun emptyTextDuplicateIdsAndSequencesCannotPublish() {
        assertFalse(TimelineValidator.validate(emptyList()).canPublish)
        assertFalse(TimelineValidator.validate(listOf(line(1, 0, 1000, "  "))).canPublish)
        assertFalse(TimelineValidator.validate(listOf(line(1, 0, 1000), line(1, 2000, 3000))).canPublish)
        assertFalse(TimelineValidator.validate(listOf(line(0, 0, 1000))).canPublish)
    }
    @Test fun shiftPreviewIsPureAndDoesNotClampNegativeOrOverflow() {
        val source = listOf(line(1, 1000, 2000))
        val preview = TimelineValidator.previewOffset(source, 250)
        assertEquals(1250L, preview.lines.single().startMs)
        assertEquals(2250L, preview.lines.single().endMs)
        assertEquals(1000L, source.single().startMs)
        assertEquals(source.single().text, preview.lines.single().text)
        val negative = TimelineValidator.previewOffset(source, -1500)
        assertFalse(negative.report.canPublish)
        assertEquals(-500L, negative.lines.single().startMs)
        val overflow = TimelineValidator.previewOffset(source, Long.MAX_VALUE)
        assertFalse(overflow.report.canPublish)
        assertEquals(TimelineIssue.OFFSET_OVERFLOW, overflow.report.findings.single().issue)
        assertEquals(source, overflow.lines)
    }
    @Test fun extremeTimestampsDoNotOverflowDurationOrOverlapArithmetic() {
        val report = TimelineValidator.validate(listOf(line(1, Long.MIN_VALUE, Long.MAX_VALUE), line(2, 0, Long.MAX_VALUE)))
        assertFalse(report.canPublish)
        assertTrue(report.findings.any { it.issue == TimelineIssue.TOO_LONG })
    }
}
