package com.lazyeng.family.core.database

import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.model.*
import kotlinx.coroutines.CancellationException
import com.lazyeng.family.core.common.subtitle.TimelineValidator
import com.lazyeng.family.core.common.subtitle.TimelineReport
import java.time.Instant

internal class RoomSubtitleRepository(private val db: FamilyDatabase) : SubtitleCatalog {
    private val dao get() = db.subtitleDao()
    override suspend fun getTrack(profileId: ProfileId, trackId: SubtitleTrackId) = dao.track(profileId.value, trackId.value)?.toDomain()
    override suspend fun listTracks(profileId: ProfileId, videoId: VideoId) = dao.tracks(profileId.value, videoId.value).map { it.toDomain() }
    override suspend fun getDraft(profileId: ProfileId, versionId: SubtitleVersionId) =
        dao.version(profileId.value, versionId.value)?.takeIf { it.status == SubtitleVersionStatus.DRAFT.name }?.toDraft()
    override suspend fun getPublishedVersion(profileId: ProfileId, versionId: SubtitleVersionId) =
        dao.version(profileId.value, versionId.value)?.takeIf { it.status == SubtitleVersionStatus.PUBLISHED.name }?.toPublished()
    override suspend fun listLines(profileId: ProfileId, versionId: SubtitleVersionId, offset: Int, limit: Int): List<SubtitleLine> {
        if (offset < 0 || limit <= 0) throw SubtitleOperationException(SubtitleErrors.invalidRecord)
        return dao.lines(profileId.value, versionId.value, offset, limit).map { it.toDomain() }
    }
    override suspend fun writeTrack(profileId: ProfileId, track: SubtitleTrack): AppResult<Unit> = result {
        db.withTransaction {
            require(track.id.value.isNotBlank() && track.language.isNotBlank() && track.sourceType.isNotBlank())
            db.videoDao().video(profileId.value, track.videoId.value) ?: fail(SubtitleErrors.scopeUnavailable)
            val old = dao.track(profileId.value, track.id.value)
            if (old == null) {
                require(track.currentVersionId == null) // Only the publisher may select a current version.
                dao.insert(SubtitleTrackEntity(track.id.value, track.videoId.value, track.language, track.sourceType, null))
            } else {
                require(old.videoId == track.videoId.value && old.currentVersionId == track.currentVersionId?.value)
                dao.update(old.copy(language = track.language, sourceType = track.sourceType))
            }
        }
    }
    override suspend fun writeDraft(profileId: ProfileId, version: DraftSubtitleVersion, lines: List<SubtitleLine>): AppResult<Unit> = result {
        db.withTransaction {
            dao.track(profileId.value, version.trackId.value) ?: fail(SubtitleErrors.scopeUnavailable)
            require(version.id.value.isNotBlank() && version.sourceHash.isNotBlank() && version.parserVersion.isNotBlank())
            require(!version.sourceEncoding.isNullOrBlank())
            require(lines.map { it.id }.distinct().size == lines.size && lines.map { it.sequence }.distinct().size == lines.size)
            require(lines.all { it.versionId == version.id && it.id.value.isNotBlank() && it.sequence > 0 && it.startMs >= 0 && it.endMs >= 0 && it.text.isNotBlank() })
            val old = dao.version(profileId.value, version.id.value)
            val record = SubtitleVersionEntity(version.id.value, version.trackId.value, version.sourceHash, version.parserVersion,
                version.status.name, version.sourceEncoding, null)
            if (old == null) dao.insertDraft(profileId.value, record) else {
                if (old.status != SubtitleVersionStatus.DRAFT.name) fail(SubtitleErrors.immutableVersion)
                require(old.trackId == record.trackId)
                dao.updateDraft(profileId.value, record)
                dao.clearDraftLines(profileId.value, version.id.value)
            }
            dao.appendDraftLines(profileId.value, lines.map { it.toEntity() })
        }
    }
    override suspend fun saveTrack(profileId: ProfileId, track: SubtitleTrack) { writeTrack(profileId, track).orThrow() }
    override suspend fun saveDraft(profileId: ProfileId, version: DraftSubtitleVersion, lines: List<SubtitleLine>) { writeDraft(profileId, version, lines).orThrow() }

    override suspend fun deleteDraft(profileId: ProfileId, versionId: SubtitleVersionId): AppResult<Unit> = result {
        dao.deleteDraft(profileId.value, versionId.value)
    }

    override suspend fun publish(profileId: ProfileId, versionId: SubtitleVersionId, publishedAt: Instant): SubtitlePublishResult {
        var report: TimelineReport? = null
        val outcome = result {
            db.withTransaction {
                val version = dao.version(profileId.value, versionId.value) ?: fail(SubtitleErrors.scopeUnavailable)
                val lines = dao.lines(profileId.value, versionId.value, 0, Int.MAX_VALUE).map { it.toDomain() }
                val validation = TimelineValidator.validate(lines)
                report = validation
                if (!validation.canPublish) fail(SubtitleErrors.invalidTimeline)
                require(version.sourceHash.isNotBlank() && version.parserVersion.isNotBlank() && !version.sourceEncoding.isNullOrBlank())
                if (version.status == SubtitleVersionStatus.PUBLISHED.name) {
                    // Idempotent retry cannot change its timestamp or reactivate a superseded version.
                    if (version.publishedAt == null) fail(SubtitleErrors.invalidRecord)
                    return@withTransaction version.toPublished()
                }
                require(version.status == SubtitleVersionStatus.DRAFT.name)
                if (dao.markPublished(profileId.value, versionId.value, publishedAt) != 1) fail(SubtitleErrors.immutableVersion)
                if (dao.selectPublished(profileId.value, version.trackId, versionId.value) != 1) fail(SubtitleErrors.scopeUnavailable)
                version.copy(status = SubtitleVersionStatus.PUBLISHED.name, publishedAt = publishedAt).toPublished()
            }
        }
        return when (outcome) {
            is AppResult.Success -> SubtitlePublishResult.Success(outcome.value, checkNotNull(report))
            is AppResult.Failure -> SubtitlePublishResult.Failure(outcome.error, report)
        }
    }

    override suspend fun publishDraft(profileId: ProfileId, versionId: SubtitleVersionId, publishedAt: Instant): PublishedSubtitleVersion =
        when (val outcome = publish(profileId, versionId, publishedAt)) {
            is SubtitlePublishResult.Success -> outcome.version
            is SubtitlePublishResult.Failure -> fail(outcome.error)
        }

    private suspend fun <T> result(block: suspend () -> T): AppResult<T> = try {
        AppResult.Success(block())
    } catch (e: CancellationException) { throw e
    } catch (e: SubtitleOperationException) { AppResult.Failure(e.error)
    } catch (_: IllegalArgumentException) { AppResult.Failure(SubtitleErrors.invalidRecord)
    } catch (_: SQLiteFullException) { AppResult.Failure(KnownAppErrors.storageLow)
    } catch (_: android.database.sqlite.SQLiteConstraintException) { AppResult.Failure(SubtitleErrors.invalidRecord)
    } catch (_: android.database.SQLException) { AppResult.Failure(SubtitleErrors.storageFailure) }
    private fun fail(error: AppError): Nothing = throw SubtitleOperationException(error)
    private fun AppResult<Unit>.orThrow() { if (this is AppResult.Failure) fail(error) }
}
