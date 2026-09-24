package com.lazyeng.family.core.model

import java.time.Instant
import java.time.LocalDate

data class Family(val id: FamilyId, val settings: Map<String, String>, val createdAt: Instant)

enum class ProfileRole { PARENT, CHILD, ADULT }
data class Profile(
    val id: ProfileId,
    val familyId: FamilyId,
    val nickname: String,
    val avatarId: String?,
    val role: ProfileRole,
    val ageMode: String,
    val englishLevel: String,
    val uiPreferences: Map<String, String>,
    val createdAt: Instant,
    val deletedAt: Instant?,
)

enum class VideoSourceType { LOCAL_FILE, PLATFORM_REFERENCE }
@JvmInline value class VideoSourceReference(val value: String)
enum class VideoStatus { DRAFT, PROCESSING, NEEDS_SUBTITLE, FAILED, READY, ARCHIVED }
data class VideoMetadata(
    val title: String,
    val coverAssetId: VideoAssetId?,
    val durationMs: Long?,
    val topicTags: List<String>,
    val cefrLevel: String?,
    val subtitleWordCount: Int?,
    val accentTag: String?,
    val ageFit: String?,
)
data class VideoRights(
    val ownerType: String,
    val usageNote: String?,
    val addedBy: ProfileId?,
    val sourceTermsAcknowledged: Boolean,
)
data class Video(
    val id: VideoId,
    val sourceType: VideoSourceType,
    val sourceReference: VideoSourceReference,
    val checksum: String?,
    val metadata: VideoMetadata,
    val status: VideoStatus,
    val importStatus: String,
    val subtitleStatus: String,
    val translationStatus: String,
    val playable: Boolean,
    val rights: VideoRights,
    val errorCode: String? = null,
    val repairAdvice: String? = null,
) {
    fun canTransitionTo(next: VideoStatus): Boolean = when (status) {
        VideoStatus.DRAFT -> next == VideoStatus.PROCESSING
        VideoStatus.PROCESSING -> next == VideoStatus.NEEDS_SUBTITLE ||
            next == VideoStatus.FAILED || next == VideoStatus.READY
        VideoStatus.READY -> next == VideoStatus.ARCHIVED
        VideoStatus.NEEDS_SUBTITLE, VideoStatus.FAILED, VideoStatus.ARCHIVED -> false
    }
}

data class VideoAsset(
    val id: VideoAssetId,
    val videoId: VideoId,
    val localReference: FileReference?,
    val checksum: String?,
    val codec: String?,
    val bytes: Long?,
)
data class SubtitleTrack(
    val id: SubtitleTrackId,
    val videoId: VideoId,
    val language: String,
    val sourceType: String,
    val currentVersionId: SubtitleVersionId?,
)
sealed interface SubtitleVersion {
    val id: SubtitleVersionId
    val trackId: SubtitleTrackId
    val sourceHash: String
    val parserVersion: String
}
data class DraftSubtitleVersion(
    override val id: SubtitleVersionId,
    override val trackId: SubtitleTrackId,
    override val sourceHash: String,
    override val parserVersion: String,
) : SubtitleVersion
class PublishedSubtitleVersion(
    override val id: SubtitleVersionId,
    override val trackId: SubtitleTrackId,
    override val sourceHash: String,
    override val parserVersion: String,
    val publishedAt: Instant,
) : SubtitleVersion
data class SubtitleLine(
    val id: SubtitleLineId,
    val versionId: SubtitleVersionId,
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
data class TranslationLine(
    val lineId: SubtitleLineId,
    val translationVersionId: TranslationVersionId,
    val textZh: String,
)

data class WatchProgress(
    val profileId: ProfileId,
    val videoId: VideoId,
    val positionMs: Long,
    val completion: Float,
    val updatedAt: Instant,
)
data class DictionaryEntry(
    val lemma: String,
    val partOfSpeech: String?,
    val phonetic: String?,
    val senses: List<String>,
    val cefr: String?,
    val frequencySource: String?,
)
data class LookupEvent(
    val id: LookupEventId,
    val eventUuid: EventUuid,
    val profileId: ProfileId,
    val word: String,
    val lineId: SubtitleLineId?,
    val videoId: VideoId?,
    val occurredAt: Instant,
)
enum class VocabularyStatus { LEARNING, REVIEW, MASTERED, PAUSED }
data class VocabularyItemKey(val profileId: ProfileId, val lemma: String)
data class VocabularyItem(
    val profileId: ProfileId,
    val lemma: String,
    val status: VocabularyStatus,
    val nextReviewAt: Instant?,
) {
    val key: VocabularyItemKey get() = VocabularyItemKey(profileId, lemma)
}
data class VocabularyContext(
    val vocabularyItem: VocabularyItemKey,
    val lineId: SubtitleLineId,
    val senseId: String?,
    val timestampMs: Long,
)
enum class ReviewRating { FORGOT, UNCERTAIN, REMEMBERED }
data class ReviewAttempt(
    val id: ReviewAttemptId,
    val eventUuid: EventUuid,
    val profileId: ProfileId,
    val vocabularyItem: VocabularyItemKey,
    val rating: ReviewRating,
    val occurredAt: Instant,
)
enum class AiFeature { CONTEXT_EXPLANATION, SUBTITLE_TRANSLATION, LEARNING_TEXT, ASR }
data class AiGeneratedContent(
    val id: GeneratedContentId,
    val feature: AiFeature,
    val cacheKey: CacheKey,
    val inputReference: String,
    val outputJson: String?,
    val schemaVersion: String,
    val version: String,
    val status: String,
    val safetyFlag: String?,
)
data class AiJob(
    val id: AiJobId,
    val feature: AiFeature,
    val status: String,
    val progress: Float,
    val errorCode: String?,
    val usage: String?,
)
data class LearningSession(
    val id: LearningSessionId,
    val profileId: ProfileId,
    val startedAt: Instant,
    val activeSeconds: Long,
    val endedReason: String?,
)
data class TimeLimitOverride(
    val profileId: ProfileId,
    val minutes: Int,
    val authorizedAt: Instant,
    val localDate: LocalDate,
)
