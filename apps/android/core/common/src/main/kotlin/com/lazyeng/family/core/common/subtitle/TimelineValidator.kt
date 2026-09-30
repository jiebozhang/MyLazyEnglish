package com.lazyeng.family.core.common.subtitle

import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.model.SubtitleLine
import com.lazyeng.family.core.model.SubtitleLineId

enum class TimelineSeverity { ERROR, WARNING }
enum class TimelineIssue { EMPTY, NEGATIVE_TIME, REVERSED_TIME, ZERO_DURATION, INVALID_SEQUENCE, EMPTY_TEXT, OUT_OF_ORDER, TOO_SHORT, TOO_LONG, LONG_TEXT, OVERLAP, OFFSET_OVERFLOW }
data class TimelineFinding(
    val issue: TimelineIssue, val severity: TimelineSeverity,
    val lineId: SubtitleLineId?, val sequence: Int?, val relatedLineId: SubtitleLineId? = null,
) {
    val error: AppError get() = AppError(AppErrorCode("SUBTITLE_${issue.name}"), ErrorHandling.USER_ACTION,
        UserMessageKey("error.subtitle.${issue.name.lowercase()}"))
}
data class TimelineReport(val findings: List<TimelineFinding>) {
    val canPublish: Boolean get() = findings.none { it.severity == TimelineSeverity.ERROR }
}

/** Product-tunable advisory defaults, not thresholds mandated by the PRD. */
data class TimelinePolicy(
    val shortDurationMs: Long = 300, val longDurationMs: Long = 10_000,
    val overlapToleranceMs: Long = 0, val longTextCodePoints: Int = 120,
) {
    init {
        require(shortDurationMs >= 0 && longDurationMs >= shortDurationMs)
        require(overlapToleranceMs >= 0 && longTextCodePoints > 0)
    }
}
data class TimelinePreview(val lines: List<SubtitleLine>, val report: TimelineReport)

object TimelineValidator {
    fun validate(lines: List<SubtitleLine>, policy: TimelinePolicy = TimelinePolicy()): TimelineReport {
        val findings = mutableListOf<TimelineFinding>()
        fun add(issue: TimelineIssue, severity: TimelineSeverity, line: SubtitleLine?, related: SubtitleLine? = null) {
            findings += TimelineFinding(issue, severity, line?.id, line?.sequence, related?.id)
        }
        if (lines.isEmpty()) add(TimelineIssue.EMPTY, TimelineSeverity.ERROR, null)
        val sequences = mutableSetOf<Int>()
        val ids = mutableSetOf<SubtitleLineId>()
        val ordered = lines.sortedBy { it.sequence }
        var previous: SubtitleLine? = null
        val valid = mutableListOf<SubtitleLine>()
        for (line in ordered) {
            if (line.sequence <= 0 || !sequences.add(line.sequence) || !ids.add(line.id)) add(TimelineIssue.INVALID_SEQUENCE, TimelineSeverity.ERROR, line)
            if (line.text.isBlank()) add(TimelineIssue.EMPTY_TEXT, TimelineSeverity.ERROR, line)
            if (line.startMs < 0 || line.endMs < 0) add(TimelineIssue.NEGATIVE_TIME, TimelineSeverity.ERROR, line)
            if (line.startMs > line.endMs) add(TimelineIssue.REVERSED_TIME, TimelineSeverity.ERROR, line)
            if (line.startMs == line.endMs) add(TimelineIssue.ZERO_DURATION, TimelineSeverity.ERROR, line)
            if (line.startMs >= 0 && line.endMs > line.startMs) {
                val duration = line.endMs - line.startMs
                if (duration < policy.shortDurationMs) add(TimelineIssue.TOO_SHORT, TimelineSeverity.WARNING, line)
                if (duration > policy.longDurationMs) add(TimelineIssue.TOO_LONG, TimelineSeverity.WARNING, line)
                valid += line
            }
            if (line.text.codePointCount(0, line.text.length) > policy.longTextCodePoints) add(TimelineIssue.LONG_TEXT, TimelineSeverity.WARNING, line)
            previous?.let { if (line.startMs < it.startMs) add(TimelineIssue.OUT_OF_ORDER, TimelineSeverity.WARNING, line, it) }
            previous = line
        }
        // A sweep retaining the furthest end detects nested overlaps, not only adjacent cues.
        var furthest: SubtitleLine? = null
        for (line in valid.sortedWith(compareBy({ it.startMs }, { it.sequence }))) {
            furthest?.let { other ->
                val overlap = minOf(other.endMs, line.endMs) - line.startMs
                if (overlap > policy.overlapToleranceMs) add(TimelineIssue.OVERLAP, TimelineSeverity.WARNING, line, other)
            }
            if (furthest == null || line.endMs > furthest.endMs) furthest = line
        }
        return TimelineReport(findings.toList())
    }

    /** No clamping, persistence or mutation: callers save a calibrated copy under NEW version/line IDs. */
    fun previewOffset(lines: List<SubtitleLine>, offsetMs: Long, policy: TimelinePolicy = TimelinePolicy()): TimelinePreview {
        val shifted = mutableListOf<SubtitleLine>()
        for (line in lines) {
            try { shifted += line.copy(startMs = Math.addExact(line.startMs, offsetMs), endMs = Math.addExact(line.endMs, offsetMs)) }
            catch (_: ArithmeticException) {
                return TimelinePreview(lines.toList(), TimelineReport(listOf(TimelineFinding(TimelineIssue.OFFSET_OVERFLOW,
                    TimelineSeverity.ERROR, line.id, line.sequence))))
            }
        }
        return TimelinePreview(shifted, validate(shifted, policy))
    }
}
