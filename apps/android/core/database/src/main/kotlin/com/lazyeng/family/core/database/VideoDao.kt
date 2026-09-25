package com.lazyeng.family.core.database

import androidx.room.*

@Dao
internal interface VideoDao {
    @Query("SELECT family_id FROM profiles WHERE id = :profileId AND deleted_at IS NULL")
    suspend fun family(profileId: String): String?
    @Query("SELECT v.* FROM videos v JOIN profiles p ON p.family_id = v.family_id WHERE p.id = :profileId AND p.deleted_at IS NULL AND v.id = :videoId")
    suspend fun video(profileId: String, videoId: String): VideoEntity?
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
}
