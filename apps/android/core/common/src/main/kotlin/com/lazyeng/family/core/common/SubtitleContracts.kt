package com.lazyeng.family.core.common

import com.lazyeng.family.core.model.*

interface SubtitleCatalog : SubtitleRepository {
    suspend fun writeTrack(profileId: ProfileId, track: SubtitleTrack): AppResult<Unit>
    suspend fun writeDraft(profileId: ProfileId, version: DraftSubtitleVersion, lines: List<SubtitleLine>): AppResult<Unit>
}

object SubtitleErrors {
    val invalidRecord = AppError(AppErrorCode("SUBTITLE_INVALID_RECORD"), ErrorHandling.USER_ACTION, UserMessageKey("error.subtitle.invalid_record"))
    val scopeUnavailable = AppError(AppErrorCode("SUBTITLE_SCOPE_UNAVAILABLE"), ErrorHandling.USER_ACTION, UserMessageKey("error.subtitle.scope_unavailable"))
    val storageFailure = AppError(AppErrorCode("SUBTITLE_STORAGE_FAILURE"), ErrorHandling.USER_ACTION, UserMessageKey("error.subtitle.storage_failure"))
}

class SubtitleOperationException(val error: AppError) : IllegalStateException(error.code.value)
