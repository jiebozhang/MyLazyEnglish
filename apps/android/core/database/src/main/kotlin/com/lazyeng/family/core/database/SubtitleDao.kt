package com.lazyeng.family.core.database

import androidx.room.*

@Dao
internal interface SubtitleDao {
    @Query("SELECT t.* FROM subtitle_tracks t JOIN videos v ON v.id=t.video_id JOIN profiles p ON p.family_id=v.family_id WHERE p.id=:profileId AND p.deleted_at IS NULL AND t.id=:trackId")
    suspend fun track(profileId: String, trackId: String): SubtitleTrackEntity?
    @Query("SELECT t.* FROM subtitle_tracks t JOIN videos v ON v.id=t.video_id JOIN profiles p ON p.family_id=v.family_id WHERE p.id=:profileId AND p.deleted_at IS NULL AND t.video_id=:videoId ORDER BY t.id")
    suspend fun tracks(profileId: String, videoId: String): List<SubtitleTrackEntity>
    @Query("SELECT s.* FROM subtitle_versions s JOIN subtitle_tracks t ON t.id=s.track_id JOIN videos v ON v.id=t.video_id JOIN profiles p ON p.family_id=v.family_id WHERE p.id=:profileId AND p.deleted_at IS NULL AND s.id=:versionId")
    suspend fun version(profileId: String, versionId: String): SubtitleVersionEntity?
    @Query("SELECT l.* FROM subtitle_lines l JOIN subtitle_versions s ON s.id=l.version_id JOIN subtitle_tracks t ON t.id=s.track_id JOIN videos v ON v.id=t.video_id JOIN profiles p ON p.family_id=v.family_id WHERE p.id=:profileId AND p.deleted_at IS NULL AND l.version_id=:versionId ORDER BY l.seq, l.id LIMIT :limit OFFSET :offset")
    suspend fun lines(profileId: String, versionId: String, offset: Int, limit: Int): List<SubtitleLineEntity>
    @Insert suspend fun insert(track: SubtitleTrackEntity)
    @Update suspend fun update(track: SubtitleTrackEntity)
    @Insert suspend fun insert(version: SubtitleVersionEntity)
    @Update suspend fun update(version: SubtitleVersionEntity)
    @Insert suspend fun insert(lines: List<SubtitleLineEntity>)
    @Query("DELETE FROM subtitle_lines WHERE version_id=:versionId AND EXISTS (SELECT 1 FROM subtitle_versions s JOIN subtitle_tracks t ON t.id=s.track_id JOIN videos v ON v.id=t.video_id JOIN profiles p ON p.family_id=v.family_id WHERE s.id=:versionId AND s.status='DRAFT' AND p.id=:profileId AND p.deleted_at IS NULL)")
    suspend fun clearDraftLines(profileId: String, versionId: String)
}
