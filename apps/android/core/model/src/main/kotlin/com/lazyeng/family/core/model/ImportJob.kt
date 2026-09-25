package com.lazyeng.family.core.model

@JvmInline value class ImportJobId(val value: String)

/** Minimal durable import envelope; processing and retry policy belong to E2-T2. */
data class ImportJob(
    val id: ImportJobId,
    val videoId: VideoId,
    val status: ImportStatus,
    val idempotencyKey: String,
    val errorCode: String? = null,
)

interface VideoAssetRepository {
    suspend fun getAsset(profileId: ProfileId, assetId: VideoAssetId): VideoAsset?
    suspend fun listAssets(profileId: ProfileId, videoId: VideoId): List<VideoAsset>
    suspend fun saveAsset(profileId: ProfileId, asset: VideoAsset)
}

interface ImportJobRepository {
    suspend fun getImportJob(profileId: ProfileId, jobId: ImportJobId): ImportJob?
    suspend fun saveImportJob(profileId: ProfileId, job: ImportJob)
}
