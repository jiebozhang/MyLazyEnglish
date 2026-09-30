package com.lazyeng.family.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface AiContentDao {
    @Query("SELECT * FROM ai_generated_content WHERE family_id = :familyId AND cache_key = :cacheKey AND status = 'READY' LIMIT 1")
    suspend fun findReady(familyId: String, cacheKey: String): AiGeneratedContentEntity?
    @Query("SELECT * FROM ai_generated_content WHERE family_id = :familyId AND input_ref = :inputReference AND status = 'READY' AND safety_flag = 'safe' ORDER BY rowid DESC LIMIT 1")
    suspend fun findLatestReady(familyId: String, inputReference: String): AiGeneratedContentEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveContent(content: AiGeneratedContentEntity)
    @Query("SELECT * FROM ai_jobs WHERE family_id = :familyId AND id = :jobId LIMIT 1")
    suspend fun getJob(familyId: String, jobId: String): AiJobEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveJob(job: AiJobEntity)
    @Query("SELECT * FROM ai_budget_usage WHERE family_id = :familyId AND period = :period AND window_key = :windowKey LIMIT 1")
    suspend fun getBudgetUsage(familyId: String, period: String, windowKey: String): AiBudgetUsageEntity?
    @Query("""INSERT INTO ai_budget_usage (family_id, period, window_key, request_count, cache_hits, usage_bucket, request_limit, usage_limit)
        VALUES (:familyId, :period, :windowKey, 0, 0, '', :requestLimit, :usageLimit)
        ON CONFLICT(family_id, period, window_key) DO UPDATE SET request_limit = :requestLimit, usage_limit = :usageLimit""")
    suspend fun configureBudget(familyId: String, period: String, windowKey: String, requestLimit: Int?, usageLimit: Int?)
    @Query("UPDATE ai_budget_usage SET usage_bucket = CASE WHEN usage_bucket = '' OR usage_bucket = 'UNKNOWN' THEN :bucket WHEN usage_bucket = :bucket THEN usage_bucket ELSE 'MIXED' END WHERE family_id = :familyId AND period = :period AND window_key = :windowKey")
    suspend fun updateUsageBucket(familyId: String, period: String, windowKey: String, bucket: String)
    @Query("""INSERT INTO ai_budget_usage (family_id, period, window_key, request_count, cache_hits, usage_bucket, request_limit, usage_limit)
        VALUES (:familyId, :period, :windowKey, CASE WHEN :cacheHit = 1 THEN 0 ELSE 1 END, CASE WHEN :cacheHit = 1 THEN 1 ELSE 0 END, :bucket, NULL, NULL)
        ON CONFLICT(family_id, period, window_key) DO UPDATE SET
        request_count = request_count + CASE WHEN :cacheHit = 1 THEN 0 ELSE 1 END,
        cache_hits = cache_hits + CASE WHEN :cacheHit = 1 THEN 1 ELSE 0 END,
        usage_bucket = CASE WHEN usage_bucket = '' THEN :bucket WHEN usage_bucket = :bucket THEN usage_bucket ELSE 'MIXED' END""")
    suspend fun incrementUsage(familyId: String, period: String, windowKey: String, bucket: String, cacheHit: Boolean)
}
