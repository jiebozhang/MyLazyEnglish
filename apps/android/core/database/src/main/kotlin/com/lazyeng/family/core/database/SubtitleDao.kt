package com.lazyeng.family.core.database

import androidx.room.*
import com.lazyeng.family.core.common.SubtitleErrors
import com.lazyeng.family.core.common.SubtitleOperationException
import java.time.Instant

@Dao
internal abstract class SubtitleDao {
    @Query("SELECT t.* FROM subtitle_tracks t JOIN videos v ON v.id=t.video_id JOIN profiles p ON p.family_id=v.family_id WHERE p.id=:profileId AND p.deleted_at IS NULL AND t.id=:trackId")
    abstract suspend fun track(profileId: String, trackId: String): SubtitleTrackEntity?
    @Query("SELECT t.* FROM subtitle_tracks t JOIN videos v ON v.id=t.video_id JOIN profiles p ON p.family_id=v.family_id WHERE p.id=:profileId AND p.deleted_at IS NULL AND t.video_id=:videoId ORDER BY t.id")
    abstract suspend fun tracks(profileId: String, videoId: String): List<SubtitleTrackEntity>
    @Query("SELECT s.* FROM subtitle_versions s JOIN subtitle_tracks t ON t.id=s.track_id JOIN videos v ON v.id=t.video_id JOIN profiles p ON p.family_id=v.family_id WHERE p.id=:profileId AND p.deleted_at IS NULL AND s.id=:versionId")
    abstract suspend fun version(profileId: String, versionId: String): SubtitleVersionEntity?
    @Query("SELECT l.* FROM subtitle_lines l JOIN subtitle_versions s ON s.id=l.version_id JOIN subtitle_tracks t ON t.id=s.track_id JOIN videos v ON v.id=t.video_id JOIN profiles p ON p.family_id=v.family_id WHERE p.id=:profileId AND p.deleted_at IS NULL AND l.version_id=:versionId ORDER BY l.seq, l.id LIMIT :limit OFFSET :offset")
    abstract suspend fun lines(profileId: String, versionId: String, offset: Int, limit: Int): List<SubtitleLineEntity>
    @Insert abstract suspend fun insert(track: SubtitleTrackEntity)
    @Update abstract suspend fun update(track: SubtitleTrackEntity)
    @Insert protected abstract suspend fun insertVersion(version: SubtitleVersionEntity)
    @Update protected abstract suspend fun updateVersion(version: SubtitleVersionEntity)
    @Insert protected abstract suspend fun insertLines(lines: List<SubtitleLineEntity>)

    @Transaction
    open suspend fun insertDraft(profileId: String, version: SubtitleVersionEntity) {
        track(profileId, version.trackId) ?: throw SubtitleOperationException(SubtitleErrors.scopeUnavailable)
        require(version.status == "DRAFT" && version.publishedAt == null)
        insertVersion(version)
    }

    @Transaction
    open suspend fun updateDraft(profileId: String, value: SubtitleVersionEntity) {
        val old = requireDraft(profileId, value.id)
        require(old.trackId == value.trackId && value.status == "DRAFT" && value.publishedAt == null)
        updateVersion(value)
    }

    @Transaction
    open suspend fun appendDraftLines(profileId: String, lines: List<SubtitleLineEntity>) {
        lines.map { it.versionId }.distinct().forEach { requireDraft(profileId, it) }
        insertLines(lines)
    }

    @Transaction
    open suspend fun clearDraftLines(profileId: String, versionId: String) {
        requireDraft(profileId, versionId)
        deleteLines(profileId, versionId)
    }

    @Transaction
    open suspend fun deleteDraft(profileId: String, versionId: String) {
        requireDraft(profileId, versionId)
        deleteLines(profileId, versionId)
        deleteVersion(profileId, versionId)
    }

    private suspend fun requireDraft(profileId: String, versionId: String): SubtitleVersionEntity {
        val old = version(profileId, versionId) ?: throw SubtitleOperationException(SubtitleErrors.scopeUnavailable)
        if (old.status != "DRAFT") throw SubtitleOperationException(SubtitleErrors.immutableVersion)
        return old
    }

    @Query("DELETE FROM subtitle_versions WHERE id=:versionId AND status='DRAFT' AND EXISTS (SELECT 1 FROM subtitle_tracks t JOIN videos v ON v.id=t.video_id JOIN profiles p ON p.family_id=v.family_id WHERE t.id=subtitle_versions.track_id AND p.id=:profileId AND p.deleted_at IS NULL)")
    protected abstract suspend fun deleteVersion(profileId: String, versionId: String)

    @Query("UPDATE subtitle_versions SET status='PUBLISHED', published_at=:at WHERE id=:versionId AND status='DRAFT' AND EXISTS (SELECT 1 FROM subtitle_tracks t JOIN videos v ON v.id=t.video_id JOIN profiles p ON p.family_id=v.family_id WHERE t.id=subtitle_versions.track_id AND p.id=:profileId AND p.deleted_at IS NULL)")
    abstract suspend fun markPublished(profileId: String, versionId: String, at: Instant): Int

    @Query("UPDATE subtitle_tracks SET current_version_id=:versionId WHERE id=:trackId AND EXISTS (SELECT 1 FROM subtitle_versions s JOIN videos v ON v.id=subtitle_tracks.video_id JOIN profiles p ON p.family_id=v.family_id WHERE s.id=:versionId AND s.track_id=:trackId AND s.status='PUBLISHED' AND p.id=:profileId AND p.deleted_at IS NULL)")
    abstract suspend fun selectPublished(profileId: String, trackId: String, versionId: String): Int
    @Query("DELETE FROM subtitle_lines WHERE version_id=:versionId AND EXISTS (SELECT 1 FROM subtitle_versions s JOIN subtitle_tracks t ON t.id=s.track_id JOIN videos v ON v.id=t.video_id JOIN profiles p ON p.family_id=v.family_id WHERE s.id=:versionId AND s.status='DRAFT' AND p.id=:profileId AND p.deleted_at IS NULL)")
    protected abstract suspend fun deleteLines(profileId: String, versionId: String)
}
