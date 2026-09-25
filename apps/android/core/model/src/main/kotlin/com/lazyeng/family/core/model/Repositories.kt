package com.lazyeng.family.core.model

import java.time.Instant
import java.time.LocalDate

interface FamilyRepository {
    suspend fun getFamily(familyId: FamilyId): Family?
    suspend fun saveFamily(family: Family)
}
/** Both household and profile must match; missing/deleted/cross-household reads return null. */
interface ProfileRepository {
    suspend fun getProfile(familyId: FamilyId, profileId: ProfileId): Profile?
    /** Creates or edits a live profile without changing its owner, creation time, or tombstone. */
    suspend fun saveProfile(familyId: FamilyId, profileId: ProfileId, profile: Profile)
    /** Idempotent tombstone; absent or cross-household targets are a no-op. */
    suspend fun deleteProfile(familyId: FamilyId, profileId: ProfileId, deletedAt: Instant)
}
/** Videos are visible only within the requesting profile's authorized household scope. */
interface VideoRepository {
    suspend fun getVideo(profileId: ProfileId, videoId: VideoId): Video?
    suspend fun listVideos(profileId: ProfileId): List<Video>
    suspend fun saveVideo(profileId: ProfileId, video: Video)
    suspend fun archiveVideo(profileId: ProfileId, videoId: VideoId)
}
/** Reserved for the later deletion task, including learning-event snapshots and managed-file cleanup. */
interface VideoDeletionRepository {
    suspend fun deleteVideoAndDetachMedia(profileId: ProfileId, videoId: VideoId)
}
/** Published versions are read-only; edits are represented by a new draft and publication. */
interface SubtitleRepository {
    suspend fun getTrack(profileId: ProfileId, trackId: SubtitleTrackId): SubtitleTrack?
    suspend fun listTracks(profileId: ProfileId, videoId: VideoId): List<SubtitleTrack>
    suspend fun getPublishedVersion(profileId: ProfileId, versionId: SubtitleVersionId): PublishedSubtitleVersion?
    suspend fun listLines(profileId: ProfileId, versionId: SubtitleVersionId, offset: Int, limit: Int): List<SubtitleLine>
    suspend fun saveDraft(profileId: ProfileId, version: DraftSubtitleVersion, lines: List<SubtitleLine>)
    suspend fun publishDraft(profileId: ProfileId, versionId: SubtitleVersionId, publishedAt: Instant): PublishedSubtitleVersion
}
interface DictionaryRepository {
    suspend fun findEntry(lemma: String): DictionaryEntry?
}
interface WatchProgressRepository {
    suspend fun getProgress(profileId: ProfileId, videoId: VideoId): WatchProgress?
    suspend fun listProgress(profileId: ProfileId): List<WatchProgress>
    suspend fun saveProgress(profileId: ProfileId, progress: WatchProgress)
}
/** Lookup history is append-only; no update or delete operation is exposed. */
interface LookupEventRepository {
    suspend fun append(profileId: ProfileId, event: LookupEvent)
    suspend fun listEvents(profileId: ProfileId, from: Instant, to: Instant): List<LookupEvent>
}
interface VocabularyRepository {
    suspend fun listItems(profileId: ProfileId): List<VocabularyItem>
    suspend fun getItem(profileId: ProfileId, lemma: String): VocabularyItem?
    suspend fun saveSnapshot(profileId: ProfileId, item: VocabularyItem)
    suspend fun deleteItem(profileId: ProfileId, lemma: String)
    suspend fun listContexts(profileId: ProfileId, lemma: String): List<VocabularyContext>
    suspend fun addContext(profileId: ProfileId, context: VocabularyContext)
}
/** Review history is append-only; snapshot changes belong to VocabularyRepository. */
interface ReviewAttemptRepository {
    suspend fun append(profileId: ProfileId, attempt: ReviewAttempt)
    suspend fun listAttempts(profileId: ProfileId, lemma: String): List<ReviewAttempt>
}
interface AiContentRepository {
    suspend fun findContent(familyId: FamilyId, cacheKey: CacheKey): AiGeneratedContent?
    suspend fun saveContent(familyId: FamilyId, content: AiGeneratedContent)
}
interface AiJobRepository {
    suspend fun getJob(familyId: FamilyId, jobId: AiJobId): AiJob?
    suspend fun saveJob(familyId: FamilyId, job: AiJob)
}
interface LearningSessionRepository {
    suspend fun listSessions(profileId: ProfileId, from: Instant, to: Instant): List<LearningSession>
    suspend fun saveSession(profileId: ProfileId, session: LearningSession)
}
interface TimeLimitOverrideRepository {
    suspend fun listOverrides(profileId: ProfileId, localDate: LocalDate): List<TimeLimitOverride>
    suspend fun append(profileId: ProfileId, override: TimeLimitOverride)
}
