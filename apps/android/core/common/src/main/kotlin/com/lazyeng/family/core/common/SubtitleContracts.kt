package com.lazyeng.family.core.common

import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.common.subtitle.TimelineReport
import java.time.Instant

interface SubtitleCatalog : SubtitleRepository, SubtitlePublisher {
    suspend fun writeTrack(profileId: ProfileId, track: SubtitleTrack): AppResult<Unit>
    suspend fun writeDraft(profileId: ProfileId, version: DraftSubtitleVersion, lines: List<SubtitleLine>): AppResult<Unit>
    suspend fun deleteDraft(profileId: ProfileId, versionId: SubtitleVersionId): AppResult<Unit>
    suspend fun publish(profileId: ProfileId, versionId: SubtitleVersionId, publishedAt: Instant): SubtitlePublishResult
}

sealed interface SubtitlePublishResult {
    data class Success(val version: PublishedSubtitleVersion, val validation: TimelineReport) : SubtitlePublishResult
    data class Failure(val error: AppError, val validation: TimelineReport? = null) : SubtitlePublishResult
}

object SubtitleErrors {
    val immutableVersion = AppError(AppErrorCode("SUBTITLE_VERSION_IMMUTABLE"), ErrorHandling.USER_ACTION, UserMessageKey("error.subtitle.create_new_version"))
    val invalidTimeline = AppError(AppErrorCode("SUBTITLE_TIMELINE_INVALID"), ErrorHandling.USER_ACTION, UserMessageKey("error.subtitle.fix_timeline"))
    val invalidRecord = AppError(AppErrorCode("SUBTITLE_INVALID_RECORD"), ErrorHandling.USER_ACTION, UserMessageKey("error.subtitle.invalid_record"))
    val scopeUnavailable = AppError(AppErrorCode("SUBTITLE_SCOPE_UNAVAILABLE"), ErrorHandling.USER_ACTION, UserMessageKey("error.subtitle.scope_unavailable"))
    val storageFailure = AppError(AppErrorCode("SUBTITLE_STORAGE_FAILURE"), ErrorHandling.USER_ACTION, UserMessageKey("error.subtitle.storage_failure"))
}

class SubtitleOperationException(val error: AppError) : IllegalStateException(error.code.value)
