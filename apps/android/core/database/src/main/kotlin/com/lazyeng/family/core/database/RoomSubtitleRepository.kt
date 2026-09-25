package com.lazyeng.family.core.database

import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.model.*
import kotlinx.coroutines.CancellationException

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
                require(track.currentVersionId == null) // Only the later publisher may select a current version.
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
            require(lines.isNotEmpty() && lines.map { it.id }.distinct().size == lines.size && lines.map { it.sequence }.distinct().size == lines.size)
            require(lines.all { it.versionId == version.id && it.id.value.isNotBlank() && it.sequence > 0 && it.startMs >= 0 && it.endMs >= 0 && it.text.isNotBlank() })
            val old = dao.version(profileId.value, version.id.value)
            val record = SubtitleVersionEntity(version.id.value, version.trackId.value, version.sourceHash, version.parserVersion,
                version.status.name, version.sourceEncoding, null)
            if (old == null) dao.insert(record) else {
                require(old.trackId == record.trackId && old.status == SubtitleVersionStatus.DRAFT.name)
                dao.update(record)
                dao.clearDraftLines(profileId.value, version.id.value)
            }
            dao.insert(lines.map { it.toEntity() })
        }
    }
    override suspend fun saveTrack(profileId: ProfileId, track: SubtitleTrack) { writeTrack(profileId, track).orThrow() }
    override suspend fun saveDraft(profileId: ProfileId, version: DraftSubtitleVersion, lines: List<SubtitleLine>) { writeDraft(profileId, version, lines).orThrow() }

    private suspend fun result(block: suspend () -> Unit): AppResult<Unit> = try {
        block(); AppResult.Success(Unit)
    } catch (e: CancellationException) { throw e
    } catch (e: SubtitleOperationException) { AppResult.Failure(e.error)
    } catch (_: IllegalArgumentException) { AppResult.Failure(SubtitleErrors.invalidRecord)
    } catch (_: SQLiteFullException) { AppResult.Failure(KnownAppErrors.storageLow)
    } catch (_: android.database.sqlite.SQLiteConstraintException) { AppResult.Failure(SubtitleErrors.invalidRecord)
    } catch (_: android.database.SQLException) { AppResult.Failure(SubtitleErrors.storageFailure) }
    private fun fail(error: AppError): Nothing = throw SubtitleOperationException(error)
    private fun AppResult<Unit>.orThrow() { if (this is AppResult.Failure) fail(error) }
}
