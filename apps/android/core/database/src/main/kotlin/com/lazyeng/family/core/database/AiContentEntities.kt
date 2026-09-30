package com.lazyeng.family.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lazyeng.family.core.model.AiBudgetUsage
import com.lazyeng.family.core.model.AiGeneratedContent
import com.lazyeng.family.core.model.AiJob
import com.lazyeng.family.core.model.AiJobId
import com.lazyeng.family.core.model.AiJobStatus
import com.lazyeng.family.core.model.CacheKey
import com.lazyeng.family.core.model.AiFeature
import com.lazyeng.family.core.model.GeneratedContentId
import com.lazyeng.family.core.model.GeneratedContentStatus
import com.lazyeng.family.core.model.FamilyId

@Entity(tableName = "ai_generated_content", foreignKeys = [ForeignKey(
    entity = FamilyEntity::class, parentColumns = ["id"], childColumns = ["family_id"], onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["family_id", "cache_key"], unique = true), Index(value = ["family_id", "feature"])])
internal data class AiGeneratedContentEntity(
    @PrimaryKey val id: String, @ColumnInfo(name = "family_id") val familyId: String, val feature: String,
    @ColumnInfo(name = "cache_key") val cacheKey: String, @ColumnInfo(name = "input_ref") val inputRef: String,
    @ColumnInfo(name = "output_json") val outputJson: String?, @ColumnInfo(name = "schema_version") val schemaVersion: String,
    val version: String, val status: String, @ColumnInfo(name = "safety_flag") val safetyFlag: String?,
)

@Entity(tableName = "ai_jobs", foreignKeys = [ForeignKey(
    entity = FamilyEntity::class, parentColumns = ["id"], childColumns = ["family_id"], onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["family_id", "idempotency_key"], unique = true), Index(value = ["family_id", "created_at_ms"])])
internal data class AiJobEntity(
    @PrimaryKey val id: String, @ColumnInfo(name = "family_id") val familyId: String, val feature: String,
    val status: String, val progress: Int, @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "usage_bucket") val usageBucket: String?, @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long, @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(tableName = "ai_budget_usage", primaryKeys = ["family_id", "period", "window_key"], foreignKeys = [ForeignKey(
    entity = FamilyEntity::class, parentColumns = ["id"], childColumns = ["family_id"], onDelete = ForeignKey.CASCADE,
)])
internal data class AiBudgetUsageEntity(
    @ColumnInfo(name = "family_id") val familyId: String, val period: String, @ColumnInfo(name = "window_key") val windowKey: String,
    @ColumnInfo(name = "request_count") val requestCount: Int, @ColumnInfo(name = "cache_hits") val cacheHits: Int,
    @ColumnInfo(name = "usage_bucket") val usageBucket: String, @ColumnInfo(name = "request_limit") val requestLimit: Int?,
    @ColumnInfo(name = "usage_limit") val usageLimit: Int?,
)

internal fun AiGeneratedContentEntity.toDomain() = AiGeneratedContent(
    GeneratedContentId(id), AiFeature.valueOf(feature), CacheKey(cacheKey), inputRef, outputJson,
    schemaVersion, version, if (status == "READY") GeneratedContentStatus.Ready else GeneratedContentStatus.Unknown(status), safetyFlag,
)
internal fun AiGeneratedContent.toEntity(familyId: String) = AiGeneratedContentEntity(
    id.value, familyId, feature.name, cacheKey.value, inputReference, outputJson, schemaVersion,
    version, if (status == GeneratedContentStatus.Ready) "READY" else (status as GeneratedContentStatus.Unknown).raw,
    safetyFlag,
)
internal fun AiJobEntity.toDomain() = AiJob(
    AiJobId(id), AiFeature.valueOf(feature), when (status) {
        "QUEUED" -> AiJobStatus.Queued
        "CANCELLED" -> AiJobStatus.Cancelled
        else -> AiJobStatus.Unknown(status)
    }, progress.toFloat(), errorCode, usageBucket,
)
internal fun AiJob.toEntity(familyId: String, idempotencyKey: String, createdAtMs: Long, updatedAtMs: Long) = AiJobEntity(
    id.value, familyId, feature.name, (status as? AiJobStatus.Unknown)?.raw ?: when (status) {
        AiJobStatus.Queued -> "QUEUED"
        AiJobStatus.Cancelled -> "CANCELLED"
        else -> "UNKNOWN"
    }, progress.toInt().coerceIn(0, 100), errorCode, usage, idempotencyKey, createdAtMs, updatedAtMs,
)
internal fun AiBudgetUsageEntity.toDomain() = AiBudgetUsage(
    FamilyId(familyId), period, windowKey, requestCount, cacheHits, usageBucket, requestLimit, usageLimit,
)
