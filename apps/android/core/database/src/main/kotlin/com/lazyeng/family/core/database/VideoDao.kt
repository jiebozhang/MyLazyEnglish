package com.lazyeng.family.core.database

import androidx.room.*

@Dao
internal interface VideoDao {
    @Query("SELECT family_id FROM profiles WHERE id = :profileId AND deleted_at IS NULL")
    suspend fun family(profileId: String): String?
    @Query("SELECT v.* FROM videos v JOIN profiles p ON p.family_id = v.family_id WHERE p.id = :profileId AND p.deleted_at IS NULL AND v.id = :videoId")
    suspend fun video(profileId: String, videoId: String): VideoEntity?
    @Query("UPDATE videos SET status='NEEDS_SUBTITLE', import_status='NEEDS_SUBTITLE', subtitle_status='NO_SUBTITLE', playable=0, error_code='NO_SUBTITLE', repair_advice='请家长重新关联英文字幕。' WHERE id=:videoId AND status='READY' AND EXISTS (SELECT 1 FROM profiles p WHERE p.id=:profileId AND p.family_id=videos.family_id AND p.deleted_at IS NULL)")
    suspend fun markNeedsSubtitle(profileId: String, videoId: String): Int
    @Query("UPDATE import_jobs SET status='NEEDS_SUBTITLE', error_code='NO_SUBTITLE' WHERE video_id=:videoId AND EXISTS (SELECT 1 FROM videos v JOIN profiles p ON p.family_id=v.family_id WHERE v.id=import_jobs.video_id AND p.id=:profileId AND p.deleted_at IS NULL)")
    suspend fun markImportJobsNeedsSubtitle(profileId: String, videoId: String): Int
    @Query("UPDATE import_jobs SET status='READY', error_code=NULL WHERE video_id=:videoId AND EXISTS (SELECT 1 FROM videos v JOIN profiles p ON p.family_id=v.family_id WHERE v.id=import_jobs.video_id AND p.id=:profileId AND p.deleted_at IS NULL)")
    suspend fun markImportJobsReady(profileId: String, videoId: String): Int
    @Query("SELECT v.* FROM videos v JOIN profiles p ON p.family_id = v.family_id WHERE p.id = :profileId AND p.deleted_at IS NULL ORDER BY v.id")
    suspend fun videos(profileId: String): List<VideoEntity>
    @Query("SELECT v.* FROM videos v JOIN profiles p ON p.family_id = v.family_id WHERE p.id = :profileId AND p.deleted_at IS NULL AND v.status = 'READY' AND v.playable = 1 ORDER BY v.id")
    suspend fun recommended(profileId: String): List<VideoEntity>
    @Insert suspend fun insert(value: VideoEntity)
    @Update suspend fun update(value: VideoEntity)
    @Query("SELECT a.* FROM video_assets a JOIN videos v ON a.video_id = v.id JOIN profiles p ON p.family_id = v.family_id WHERE p.id = :profileId AND p.deleted_at IS NULL AND a.id = :assetId")
    suspend fun asset(profileId: String, assetId: String): VideoAssetEntity?
    @Query("SELECT a.* FROM video_assets a JOIN videos v ON a.video_id = v.id JOIN profiles p ON p.family_id = v.family_id WHERE p.id = :profileId AND p.deleted_at IS NULL AND v.id = :videoId ORDER BY a.id")
    suspend fun assets(profileId: String, videoId: String): List<VideoAssetEntity>
    @Insert suspend fun insert(value: VideoAssetEntity)
    @Update suspend fun update(value: VideoAssetEntity)
    @Query("SELECT j.* FROM import_jobs j JOIN videos v ON j.video_id = v.id JOIN profiles p ON p.family_id = v.family_id WHERE p.id = :profileId AND p.deleted_at IS NULL AND j.id = :jobId")
    suspend fun job(profileId: String, jobId: String): ImportJobEntity?
    @Insert suspend fun insert(value: ImportJobEntity)
    @Update suspend fun update(value: ImportJobEntity)
    @Query("SELECT w.* FROM watch_progress w JOIN profiles p ON p.id = w.profile_id JOIN videos v ON v.id = w.video_id AND v.family_id = p.family_id WHERE w.profile_id = :profileId AND p.deleted_at IS NULL AND w.video_id = :videoId")
    suspend fun progress(profileId: String, videoId: String): WatchProgressEntity?
    @Query("SELECT w.* FROM watch_progress w JOIN profiles p ON p.id = w.profile_id JOIN videos v ON v.id = w.video_id AND v.family_id = p.family_id WHERE w.profile_id = :profileId AND p.deleted_at IS NULL ORDER BY w.video_id")
    suspend fun progress(profileId: String): List<WatchProgressEntity>
    @Insert suspend fun insert(value: WatchProgressEntity)
    @Update suspend fun update(value: WatchProgressEntity)
    @Query("DELETE FROM watch_progress WHERE profile_id = :profileId")
    suspend fun deleteProgressForProfile(profileId: String): Int
    @Query("DELETE FROM watch_progress WHERE video_id = :videoId")
    suspend fun deleteProgressForVideo(videoId: String): Int
    @Query("DELETE FROM import_jobs WHERE video_id = :videoId")
    suspend fun deleteJobsForVideo(videoId: String): Int
    @Query("DELETE FROM video_assets WHERE video_id = :videoId")
    suspend fun deleteAssetsForVideo(videoId: String): Int
    @Query("DELETE FROM videos WHERE id = :videoId")
    suspend fun deleteVideo(videoId: String): Int
}
