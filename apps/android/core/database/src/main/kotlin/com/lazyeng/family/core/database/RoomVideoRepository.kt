package com.lazyeng.family.core.database

import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.model.*
import kotlinx.coroutines.CancellationException

internal class RoomVideoRepository(
    private val db: FamilyDatabase,
    private val media: MediaReadinessChecker,
    private val subtitles: SubtitleReadinessChecker,
) : VideoCatalog, VideoAssetRepository, ImportJobRepository, WatchProgressRepository {
    private val dao get() = db.videoDao()
    override suspend fun getVideo(profileId: ProfileId, videoId: VideoId) = dao.video(profileId.value, videoId.value)?.toDomain()
    override suspend fun listVideos(profileId: ProfileId) = dao.videos(profileId.value).map { it.toDomain() }
    override suspend fun recommendations(profileId: ProfileId): List<Video> = db.withTransaction {
        val family = dao.family(profileId.value) ?: return@withTransaction emptyList()
        val profile = db.profileDao().getActive(family, profileId.value) ?: return@withTransaction emptyList()
        dao.recommended(profileId.value).map { it.toDomain() }.filter {
            profile.role != ProfileRole.CHILD || (it.metadata.ageFit != null && it.metadata.ageFit !in setOf("adult", "unknown"))
        }
    }

    override suspend fun writeVideo(profileId: ProfileId, video: Video): AppResult<Unit> = result {
        db.withTransaction {
            val family = dao.family(profileId.value) ?: fail(VideoErrors.scopeUnavailable)
            validate(video)
            video.rights.addedBy?.let {
                // Attribution survives a member tombstone; it cannot point into another household.
                if (db.profileDao().getRecord(family, it.value) == null) fail(VideoErrors.scopeUnavailable)
            }
            video.metadata.coverAssetId?.let {
                if (getAsset(profileId, it)?.videoId != video.id) fail(VideoErrors.invalidRecord)
            }
            val old = dao.video(profileId.value, video.id.value)
            // Generic saves cannot bypass the transition guard, including first insertion of READY.
            if (old == null) {
                if (video.status != VideoStatus.DRAFT || video.playable) fail(VideoErrors.invalidTransition)
                dao.insert(video.toEntity(family))
            } else {
                if (old.status != video.status.name || old.playable != video.playable ||
                    old.errorCode != video.errorCode || old.repairAdvice != video.repairAdvice) fail(VideoErrors.invalidTransition)
                if (video.status == VideoStatus.READY) ready(profileId, video)
                dao.update(video.toEntity(family))
            }
        }
    }
    override suspend fun saveVideo(profileId: ProfileId, video: Video) { writeVideo(profileId, video).valueOrThrow() }
    override suspend fun archiveVideo(profileId: ProfileId, videoId: VideoId) {
        transition(profileId, videoId, VideoStatus.ARCHIVED).valueOrThrow()
    }
    override suspend fun transition(profileId: ProfileId, videoId: VideoId, next: VideoStatus,
        errorCode: String?, repairAdvice: String?): AppResult<Video> = result {
        db.withTransaction {
            val entity = dao.video(profileId.value, videoId.value) ?: fail(VideoErrors.scopeUnavailable)
            val old = entity.toDomain()
            if (!old.canTransitionTo(next)) fail(VideoErrors.invalidTransition)
            val failure = next == VideoStatus.NEEDS_SUBTITLE || next == VideoStatus.FAILED
            if (failure && (errorCode.isNullOrBlank() || repairAdvice.isNullOrBlank())) fail(VideoErrors.invalidRecord)
            if (next == VideoStatus.READY) ready(profileId, old)
            val updated = old.copy(status = next, importStatus = StatusCodecs.importStatusFromWire(next.name),
                playable = if (next == VideoStatus.READY) true else old.playable,
                subtitleStatus = if (next == VideoStatus.READY) SubtitleStatus.Available else old.subtitleStatus,
                errorCode = if (failure) errorCode else null, repairAdvice = if (failure) repairAdvice else null)
            dao.update(updated.toEntity(entity.familyId))
            updated
        }
    }
    private suspend fun ready(profileId: ProfileId, video: Video) {
        if (!media.isAccessible(profileId, video, listAssets(profileId, video.id))) fail(KnownAppErrors.videoUnavailable)
        if (!subtitles.hasPublishedEnglish(profileId, video.id)) fail(KnownAppErrors.noSubtitle)
    }
    private fun validate(video: Video) {
        require(video.id.value.isNotBlank() && video.sourceReference.value.isNotBlank() && video.metadata.title.isNotBlank())
        require(video.sourceType == VideoSourceType.LOCAL_FILE) // V1.0 has no platform import path.
        require((video.metadata.durationMs ?: 0) >= 0)
        require((video.metadata.subtitleWordCount ?: 0) >= 0)
        require(StatusCodecs.importStatusToWire(video.importStatus) == video.status.name)
    }

    override suspend fun getAsset(profileId: ProfileId, assetId: VideoAssetId) = dao.asset(profileId.value, assetId.value)?.toDomain()
    override suspend fun listAssets(profileId: ProfileId, videoId: VideoId) = dao.assets(profileId.value, videoId.value).map { it.toDomain() }
    override suspend fun saveAsset(profileId: ProfileId, asset: VideoAsset) { result {
        db.withTransaction {
            require(asset.id.value.isNotBlank() && (asset.bytes ?: 0) >= 0)
            val video = getVideo(profileId, asset.videoId) ?: fail(VideoErrors.scopeUnavailable)
            if (video.status in setOf(VideoStatus.READY, VideoStatus.ARCHIVED)) fail(VideoErrors.invalidTransition)
            val previous = dao.asset(profileId.value, asset.id.value)
            if (previous != null && previous.videoId != asset.videoId.value) fail(VideoErrors.scopeUnavailable)
            val value = VideoAssetEntity(asset.id.value, asset.videoId.value, asset.localReference?.value, asset.checksum, asset.codec, asset.bytes)
            if (previous == null) dao.insert(value) else dao.update(value)
        }
    }.valueOrThrow() }

    override suspend fun getImportJob(profileId: ProfileId, jobId: ImportJobId) = dao.job(profileId.value, jobId.value)?.toDomain()
    override suspend fun saveImportJob(profileId: ProfileId, job: ImportJob) { result {
        db.withTransaction {
            require(job.id.value.isNotBlank() && job.idempotencyKey.isNotBlank())
            getVideo(profileId, job.videoId) ?: fail(VideoErrors.scopeUnavailable)
            val previous = dao.job(profileId.value, job.id.value)
            if (previous != null && (previous.videoId != job.videoId.value || previous.idempotencyKey != job.idempotencyKey)) {
                fail(VideoErrors.scopeUnavailable)
            }
            val value = ImportJobEntity(job.id.value, job.videoId.value, StatusCodecs.importStatusToWire(job.status), job.idempotencyKey, job.errorCode)
            if (previous == null) dao.insert(value) else dao.update(value)
        }
    }.valueOrThrow() }

    override suspend fun getProgress(profileId: ProfileId, videoId: VideoId) = dao.progress(profileId.value, videoId.value)?.toDomain()
    override suspend fun listProgress(profileId: ProfileId) = dao.progress(profileId.value).map { it.toDomain() }
    override suspend fun saveProgress(profileId: ProfileId, progress: WatchProgress) { result {
        db.withTransaction {
            if (progress.profileId != profileId) fail(VideoErrors.scopeUnavailable)
            getVideo(profileId, progress.videoId) ?: fail(VideoErrors.scopeUnavailable)
            require(progress.positionMs >= 0 && progress.completion.isFinite() && progress.completion in 0f..1f)
            val old = dao.progress(profileId.value, progress.videoId.value)
            if (old != null && progress.updatedAt < old.updatedAt) return@withTransaction
            val value = WatchProgressEntity(profileId.value, progress.videoId.value, progress.positionMs, progress.completion, progress.updatedAt)
            if (old == null) dao.insert(value) else dao.update(value)
        }
    }.valueOrThrow() }
}

private fun fail(error: AppError): Nothing = throw VideoOperationException(error)
private fun <T> AppResult<T>.valueOrThrow(): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> fail(error)
}
private suspend fun <T> result(block: suspend () -> T): AppResult<T> = try { AppResult.Success(block()) }
catch (cancelled: CancellationException) { throw cancelled }
catch (error: VideoOperationException) { AppResult.Failure(error.error) }
catch (_: SQLiteFullException) { AppResult.Failure(KnownAppErrors.storageLow) }
catch (_: IllegalArgumentException) { AppResult.Failure(VideoErrors.invalidRecord) }
catch (_: Exception) { AppResult.Failure(VideoErrors.storageFailure) }
