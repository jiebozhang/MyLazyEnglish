package com.lazyeng.family.core.model

data class AiCacheDimensions(
    val familyId: FamilyId,
    val lemma: String,
    val sentenceHash: String,
    val lineVersion: String,
    val learnerLevel: String,
    val uiLanguage: String,
    val promptVersion: String,
    val provider: String,
    val model: String,
) {
    fun keyMaterial() = listOf(
        familyId.value, lemma, sentenceHash, lineVersion, learnerLevel, uiLanguage, promptVersion, provider, model,
    ).joinToString("\u001f")
}

data class AiBudgetUsage(
    val familyId: FamilyId,
    val period: String,
    val windowKey: String,
    val requestCount: Int,
    val cacheHits: Int,
    val usageBucket: String,
    val requestLimit: Int?,
    val usageLimit: Int?,
)

interface AiBudgetUsageRepository {
    suspend fun read(familyId: FamilyId, period: String, windowKey: String): AiBudgetUsage?
    suspend fun record(familyId: FamilyId, period: String, windowKey: String, usageBucket: String, cacheHit: Boolean)
    suspend fun reserveRequest(familyId: FamilyId, dailyWindow: String, monthlyWindow: String)
    suspend fun updateUsageBucket(familyId: FamilyId, period: String, windowKey: String, usageBucket: String)
    suspend fun configure(familyId: FamilyId, period: String, windowKey: String, requestLimit: Int?, usageLimit: Int?)
}
