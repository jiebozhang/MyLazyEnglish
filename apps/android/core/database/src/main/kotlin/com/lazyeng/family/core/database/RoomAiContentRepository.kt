package com.lazyeng.family.core.database

import androidx.room.withTransaction
import com.lazyeng.family.core.model.AiBudgetUsage
import com.lazyeng.family.core.model.AiBudgetUsageRepository
import com.lazyeng.family.core.model.AiContentRepository
import com.lazyeng.family.core.model.AiGeneratedContent
import com.lazyeng.family.core.model.AiJob
import com.lazyeng.family.core.model.AiJobId
import com.lazyeng.family.core.model.AiJobRepository
import com.lazyeng.family.core.model.CacheKey
import com.lazyeng.family.core.model.FamilyId

internal class RoomAiContentRepository(private val db: FamilyDatabase) : AiContentRepository, AiJobRepository, AiBudgetUsageRepository {
    override suspend fun findContent(familyId: FamilyId, cacheKey: CacheKey): AiGeneratedContent? =
        db.aiContentDao().findReady(familyId.value, cacheKey.value)?.toDomain()

    override suspend fun findLatestReady(familyId: FamilyId, inputReference: String): AiGeneratedContent? =
        db.aiContentDao().findLatestReady(familyId.value, inputReference)?.toDomain()

    override suspend fun saveContent(familyId: FamilyId, content: AiGeneratedContent) {
        require(content.outputJson != null && content.status == com.lazyeng.family.core.model.GeneratedContentStatus.Ready)
        require(content.safetyFlag == "safe")
        db.withTransaction { db.aiContentDao().saveContent(content.toEntity(familyId.value)) }
    }

    override suspend fun getJob(familyId: FamilyId, jobId: AiJobId): AiJob? =
        db.aiContentDao().getJob(familyId.value, jobId.value)?.toDomain()

    override suspend fun saveJob(familyId: FamilyId, job: AiJob) {
        val now = System.currentTimeMillis()
        val existing = db.aiContentDao().getJob(familyId.value, job.id.value)
        db.aiContentDao().saveJob(job.toEntity(familyId.value, job.id.value, existing?.createdAtMs ?: now, now))
    }

    override suspend fun read(familyId: FamilyId, period: String, windowKey: String): AiBudgetUsage? =
        db.aiContentDao().getBudgetUsage(familyId.value, period, windowKey)?.toDomain()

    override suspend fun record(familyId: FamilyId, period: String, windowKey: String, usageBucket: String, cacheHit: Boolean) {
        db.withTransaction {
            val usage = db.aiContentDao().getBudgetUsage(familyId.value, period, windowKey)
            val limit = usage?.requestLimit
            if (!cacheHit && limit != null) check(usage.requestCount < limit) { "AI request budget reached" }
            db.aiContentDao().incrementUsage(familyId.value, period, windowKey, usageBucket, cacheHit)
        }
    }

    override suspend fun reserveRequest(familyId: FamilyId, dailyWindow: String, monthlyWindow: String) {
        db.withTransaction {
            val daily = db.aiContentDao().getBudgetUsage(familyId.value, "DAILY", dailyWindow)
            val monthly = db.aiContentDao().getBudgetUsage(familyId.value, "MONTHLY", monthlyWindow)
            check(daily?.requestLimit?.let { daily.requestCount < it } != false) { "AI daily budget reached" }
            check(monthly?.requestLimit?.let { monthly.requestCount < it } != false) { "AI monthly budget reached" }
            db.aiContentDao().incrementUsage(familyId.value, "DAILY", dailyWindow, "UNKNOWN", false)
            db.aiContentDao().incrementUsage(familyId.value, "MONTHLY", monthlyWindow, "UNKNOWN", false)
        }
    }

    override suspend fun updateUsageBucket(familyId: FamilyId, period: String, windowKey: String, usageBucket: String) {
        db.aiContentDao().updateUsageBucket(familyId.value, period, windowKey, usageBucket)
    }

    override suspend fun configure(familyId: FamilyId, period: String, windowKey: String, requestLimit: Int?, usageLimit: Int?) {
        require(period in setOf("DAILY", "MONTHLY") && (requestLimit == null || requestLimit >= 0) && (usageLimit == null || usageLimit >= 0))
        db.aiContentDao().configureBudget(familyId.value, period, windowKey, requestLimit, usageLimit)
    }
}
