package com.lazyeng.family.core.common

import com.lazyeng.family.core.model.*

fun interface SubtitleReadinessChecker {
    suspend fun hasPublishedEnglish(profileId: ProfileId, videoId: VideoId): Boolean
}
fun interface MediaReadinessChecker {
    suspend fun isAccessible(profileId: ProfileId, video: Video, assets: List<VideoAsset>): Boolean
}

/** Fail closed until E3-T2/E2-T2 install their real adapters. No I/O or fabricated readiness. */
object UnavailableSubtitleReadiness : SubtitleReadinessChecker {
    override suspend fun hasPublishedEnglish(profileId: ProfileId, videoId: VideoId) = false
}
object UnavailableMediaReadiness : MediaReadinessChecker {
    override suspend fun isAccessible(profileId: ProfileId, video: Video, assets: List<VideoAsset>) = false
}

interface VideoCatalog : VideoRepository {
    suspend fun writeVideo(profileId: ProfileId, video: Video): AppResult<Unit>
    suspend fun transition(profileId: ProfileId, videoId: VideoId, next: VideoStatus,
        errorCode: String? = null, repairAdvice: String? = null): AppResult<Video>
    suspend fun recommendations(profileId: ProfileId): List<Video>
}

/** Typed bridge for T0-3's Unit-returning write methods. New commands use AppResult directly. */
class VideoOperationException(val error: AppError) : IllegalStateException(error.code.value)

object VideoErrors {
    val invalidTransition = error("VIDEO_INVALID_TRANSITION", ErrorHandling.NON_RETRYABLE)
    val invalidRecord = error("VIDEO_INVALID_RECORD", ErrorHandling.USER_ACTION)
    val scopeUnavailable = error("VIDEO_SCOPE_UNAVAILABLE", ErrorHandling.NON_RETRYABLE)
    val storageFailure = error("VIDEO_STORAGE_FAILURE", ErrorHandling.USER_ACTION)
    private fun error(code: String, handling: ErrorHandling) =
        AppError(AppErrorCode(code), handling, UserMessageKey("error.video_unavailable"))
}
