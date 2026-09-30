package com.lazyeng.family.feature.library

import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.common.subtitle.SubtitleTokenizer
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.security.ParentAuthorizedScope
import com.lazyeng.family.core.security.ParentVideoAccess

enum class DetailAvailability { READY, DRAFT, PROCESSING, NEEDS_SUBTITLE, FAILED, ARCHIVED, MISSING, PERMISSION, UNAVAILABLE }
data class ChildVideoDetail(val title: String, val durationMs: Long?, val level: String?, val topics: List<String>,
    val positionMs: Long, val completion: Float?, val availability: DetailAvailability) {
    val canWatch get() = availability == DetailAvailability.READY
}
data class VersionDetail(val id: String, val language: String, val published: Boolean, val current: Boolean,
    val publishedAt: String?, val encoding: String?)
data class ParentVideoDetail(val child: ChildVideoDetail, val source: String, val sourceType: String,
    val status: VideoStatus, val versions: List<VersionDetail>, val wordCount: Int?, val ownerType: String,
    val usageNote: String?, val rightsAcknowledged: Boolean, val errorCode: String?, val repairAdvice: String?,
    val canRepairSubtitle: Boolean)

/** Reads domain repositories only. Each operation rechecks the captured Profile before and after I/O. */
class VideoDetailData(private val profileId: ProfileId, val videoId: VideoId,
    private val videos: VideoCatalog, private val subtitles: SubtitleCatalog,
    private val progress: WatchProgressRepository, private val readiness: SubtitleReadinessChecker,
    private val access: suspend (FileReference) -> Unit, private val scopeValid: suspend () -> Boolean) {
    suspend fun validScope() = scopeValid()
    private suspend fun video(scope: ParentAuthorizedScope? = null): Video? {
        if (!scopeValid()) return null
        val item = if (scope == null) videos.getVideo(profileId, videoId)
        else (videos as? ParentVideoAccess)?.getForAuthorizedParent(profileId, videoId, scope)
        return item.takeIf { scopeValid() }
    }
    private suspend fun project(video: Video): ChildVideoDetail {
        var accessFailure: ImportFailure? = null
        try { access(FileReference(video.sourceReference.value)) }
        catch (failure: ImportException) { accessFailure = failure.failure }
        val available = availability(video, readiness.hasPublishedEnglish(profileId, videoId), accessFailure)
        val watched = progress.getProgress(profileId, videoId)
        return ChildVideoDetail(video.metadata.title, video.metadata.durationMs, levelLabel(video.metadata), video.metadata.topicTags,
            watched?.positionMs?.coerceAtLeast(0) ?: 0, watched?.completion?.coerceIn(0f, 1f), available)
    }
    suspend fun child(): ChildVideoDetail? {
        val item = video() ?: return null
        val detail = project(item)
        return detail.takeIf { scopeValid() }
    }
    suspend fun parent(scope: ParentAuthorizedScope? = null): ParentVideoDetail? {
        val item = video(scope) ?: return null
        val child = project(item)
        val tracks = subtitles.listTracks(profileId, videoId)
        val versions = tracks.flatMap { track -> subtitles.listVersions(profileId, track.id).map { version ->
            VersionDetail(version.id.value, track.language, version.status == SubtitleVersionStatus.PUBLISHED,
                track.currentVersionId == version.id, (version as? PublishedSubtitleVersion)?.publishedAt?.toString(), version.sourceEncoding)
        } }
        // Count tokens from the actual current English version, not a stale imported metadata count.
        val current = tracks.firstOrNull { track ->
            Regex("en(?:-[a-z0-9]{1,8})*", RegexOption.IGNORE_CASE).matches(track.language) &&
                track.currentVersionId?.let { subtitles.getPublishedVersion(profileId, it) } != null
        }?.currentVersionId
        val wordCount = current?.takeIf { subtitles.getPublishedVersion(profileId, it) != null }?.let { id ->
            var offset = 0
            var words = 0
            do {
                val lines = subtitles.listLines(profileId, id, offset, 200)
                words += lines.sumOf { SubtitleTokenizer.tokenize(it.text).count { token ->
                    token.kind == com.lazyeng.family.core.common.subtitle.SubtitleTokenKind.WORD
                } }
                offset += lines.size
            } while (lines.size == 200)
            words
        }
        val failure = when (child.availability) {
            DetailAvailability.MISSING -> ImportErrors.missing
            DetailAvailability.PERMISSION -> ImportErrors.permission
            DetailAvailability.UNAVAILABLE -> ImportErrors.access
            else -> null
        }
        return ParentVideoDetail(child, item.sourceReference.value, item.sourceType.name, item.status, versions, wordCount,
            item.rights.ownerType, item.rights.usageNote, item.rights.sourceTermsAcknowledged,
            failure?.error?.code?.value ?: item.errorCode, failure?.message ?: item.repairAdvice,
            item.status in setOf(VideoStatus.READY, VideoStatus.NEEDS_SUBTITLE) && failure == null).takeIf { scopeValid() }
    }
    suspend fun archive(): AppResult<Video> {
        if (!scopeValid()) return AppResult.Failure(VideoErrors.scopeUnavailable)
        return videos.transition(profileId, videoId, VideoStatus.ARCHIVED)
    }
    companion object {
        fun levelLabel(metadata: VideoMetadata): String? = metadata.cefrLevel?.let { value -> when (metadata.levelSource) {
            VideoLevelSource.MANUAL -> "人工等级 $value"
            VideoLevelSource.ESTIMATED -> "估算等级 $value"
            null -> null
        } }
        fun availability(video: Video, publishedEnglish: Boolean, failure: ImportFailure?): DetailAvailability = when {
            video.status == VideoStatus.ARCHIVED -> DetailAvailability.ARCHIVED
            failure?.error?.code == ImportErrors.missing.error.code -> DetailAvailability.MISSING
            failure?.error?.code == ImportErrors.permission.error.code -> DetailAvailability.PERMISSION
            failure != null -> DetailAvailability.UNAVAILABLE
            video.status == VideoStatus.READY && video.playable && publishedEnglish -> DetailAvailability.READY
            video.status == VideoStatus.READY || video.status == VideoStatus.NEEDS_SUBTITLE -> DetailAvailability.NEEDS_SUBTITLE
            video.status == VideoStatus.DRAFT -> DetailAvailability.DRAFT
            video.status == VideoStatus.PROCESSING -> DetailAvailability.PROCESSING
            else -> DetailAvailability.FAILED
        }
    }
}
