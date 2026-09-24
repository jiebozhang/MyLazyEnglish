package com.lazyeng.family.core.model

/** Exact wire-value codecs preserve unrecognized status strings for forward compatibility. */
object StatusCodecs {
    fun importStatusFromWire(raw: String): ImportStatus = when (raw) {
        "DRAFT" -> ImportStatus.Draft
        "PROCESSING" -> ImportStatus.Processing
        "NEEDS_SUBTITLE" -> ImportStatus.NeedsSubtitle
        "FAILED" -> ImportStatus.Failed
        "READY" -> ImportStatus.Ready
        "ARCHIVED" -> ImportStatus.Archived
        else -> ImportStatus.Unknown(raw)
    }

    fun importStatusToWire(status: ImportStatus): String = when (status) {
        ImportStatus.Draft -> "DRAFT"
        ImportStatus.Processing -> "PROCESSING"
        ImportStatus.NeedsSubtitle -> "NEEDS_SUBTITLE"
        ImportStatus.Failed -> "FAILED"
        ImportStatus.Ready -> "READY"
        ImportStatus.Archived -> "ARCHIVED"
        is ImportStatus.Unknown -> status.raw
    }

    fun subtitleStatusFromWire(raw: String): SubtitleStatus = when (raw) {
        "NO_SUBTITLE" -> SubtitleStatus.NoSubtitle
        "AVAILABLE" -> SubtitleStatus.Available
        else -> SubtitleStatus.Unknown(raw)
    }

    fun subtitleStatusToWire(status: SubtitleStatus): String = when (status) {
        SubtitleStatus.NoSubtitle -> "NO_SUBTITLE"
        SubtitleStatus.Available -> "AVAILABLE"
        is SubtitleStatus.Unknown -> status.raw
    }

    fun translationStatusFromWire(raw: String): TranslationStatus = when (raw) {
        "AVAILABLE" -> TranslationStatus.Available
        "STALE" -> TranslationStatus.Stale
        else -> TranslationStatus.Unknown(raw)
    }

    fun translationStatusToWire(status: TranslationStatus): String = when (status) {
        TranslationStatus.Available -> "AVAILABLE"
        TranslationStatus.Stale -> "STALE"
        is TranslationStatus.Unknown -> status.raw
    }

    fun aiJobStatusFromWire(raw: String): AiJobStatus = when (raw) {
        "QUEUED" -> AiJobStatus.Queued
        "CANCELLED" -> AiJobStatus.Cancelled
        else -> AiJobStatus.Unknown(raw)
    }

    fun aiJobStatusToWire(status: AiJobStatus): String = when (status) {
        AiJobStatus.Queued -> "QUEUED"
        AiJobStatus.Cancelled -> "CANCELLED"
        is AiJobStatus.Unknown -> status.raw
    }

    fun generatedContentStatusFromWire(raw: String): GeneratedContentStatus = when (raw) {
        "READY" -> GeneratedContentStatus.Ready
        else -> GeneratedContentStatus.Unknown(raw)
    }

    fun generatedContentStatusToWire(status: GeneratedContentStatus): String = when (status) {
        GeneratedContentStatus.Ready -> "READY"
        is GeneratedContentStatus.Unknown -> status.raw
    }
}
