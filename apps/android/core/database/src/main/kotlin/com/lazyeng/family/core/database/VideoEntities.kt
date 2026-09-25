package com.lazyeng.family.core.database

import androidx.room.*
import com.lazyeng.family.core.model.*
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "videos", foreignKeys = [ForeignKey(entity = FamilyEntity::class,
    parentColumns = ["id"], childColumns = ["family_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("family_id")])
internal data class VideoEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "family_id") val familyId: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_ref") val sourceRef: String,
    val checksum: String?,
    val metadata: VideoMetadata,
    val status: String,
    @ColumnInfo(name = "import_status") val importStatus: String,
    @ColumnInfo(name = "subtitle_status") val subtitleStatus: String,
    @ColumnInfo(name = "translation_status") val translationStatus: String,
    val playable: Boolean,
    val rights: VideoRights,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "repair_advice") val repairAdvice: String?,
)

@Entity(tableName = "video_assets", foreignKeys = [ForeignKey(entity = VideoEntity::class,
    parentColumns = ["id"], childColumns = ["video_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("video_id")])
internal data class VideoAssetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "video_id") val videoId: String,
    @ColumnInfo(name = "local_uri") val localUri: String?,
    val checksum: String?, val codec: String?, val bytes: Long?,
)

@Entity(tableName = "import_jobs", foreignKeys = [ForeignKey(entity = VideoEntity::class,
    parentColumns = ["id"], childColumns = ["video_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["video_id", "idempotency_key"], unique = true)])
internal data class ImportJobEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "video_id") val videoId: String,
    val status: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "error_code") val errorCode: String?,
)

@Entity(tableName = "watch_progress", primaryKeys = ["profile_id", "video_id"],
    foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profile_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = VideoEntity::class, parentColumns = ["id"], childColumns = ["video_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("video_id")])
internal data class WatchProgressEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "video_id") val videoId: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    val completion: Float,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

internal fun Video.toEntity(familyId: String) = VideoEntity(id.value, familyId, sourceType.name, sourceReference.value,
    checksum, metadata, status.name, StatusCodecs.importStatusToWire(importStatus),
    StatusCodecs.subtitleStatusToWire(subtitleStatus), StatusCodecs.translationStatusToWire(translationStatus),
    playable, rights, errorCode, repairAdvice)
internal fun VideoEntity.toDomain() = Video(VideoId(id), VideoSourceType.valueOf(sourceType), VideoSourceReference(sourceRef),
    checksum, metadata, VideoStatus.valueOf(status), StatusCodecs.importStatusFromWire(importStatus),
    StatusCodecs.subtitleStatusFromWire(subtitleStatus), StatusCodecs.translationStatusFromWire(translationStatus),
    playable, rights, errorCode, repairAdvice)
internal fun VideoAssetEntity.toDomain() = VideoAsset(VideoAssetId(id), VideoId(videoId), localUri?.let(::FileReference), checksum, codec, bytes)
internal fun ImportJobEntity.toDomain() = ImportJob(ImportJobId(id), VideoId(videoId), StatusCodecs.importStatusFromWire(status), idempotencyKey, errorCode)
internal fun WatchProgressEntity.toDomain() = WatchProgress(ProfileId(profileId), VideoId(videoId), positionMs, completion, updatedAt)

/** Structured metadata is stored as JSON, not ad-hoc delimited strings. Ownership/state stay indexed columns. */
internal class VideoConverters {
    @TypeConverter fun metadata(value: VideoMetadata): String = JSONObject()
        .put("title", value.title).put("coverAssetId", value.coverAssetId?.value ?: JSONObject.NULL)
        .put("durationMs", value.durationMs ?: JSONObject.NULL).put("topicTags", JSONArray(value.topicTags))
        .put("cefrLevel", value.cefrLevel ?: JSONObject.NULL).put("subtitleWordCount", value.subtitleWordCount ?: JSONObject.NULL)
        .put("accentTag", value.accentTag ?: JSONObject.NULL).put("ageFit", value.ageFit ?: JSONObject.NULL)
        .put("levelSource", value.levelSource?.name ?: JSONObject.NULL).toString()
    @TypeConverter fun metadata(value: String): VideoMetadata = JSONObject(value).let { json ->
        val tags = json.getJSONArray("topicTags")
        VideoMetadata(json.getString("title"), json.nullable("coverAssetId")?.let(::VideoAssetId),
            if (json.isNull("durationMs")) null else json.getLong("durationMs"),
            (0 until tags.length()).map(tags::getString), json.nullable("cefrLevel"),
            if (json.isNull("subtitleWordCount")) null else json.getInt("subtitleWordCount"),
            json.nullable("accentTag"), json.nullable("ageFit"), json.nullable("levelSource")?.let(VideoLevelSource::valueOf))
    }
    @TypeConverter fun rights(value: VideoRights): String = JSONObject().put("ownerType", value.ownerType)
        .put("usageNote", value.usageNote ?: JSONObject.NULL).put("addedBy", value.addedBy?.value ?: JSONObject.NULL)
        .put("sourceTermsAcknowledged", value.sourceTermsAcknowledged).toString()
    @TypeConverter fun rights(value: String): VideoRights = JSONObject(value).let {
        VideoRights(it.getString("ownerType"), it.nullable("usageNote"), it.nullable("addedBy")?.let(::ProfileId),
            it.getBoolean("sourceTermsAcknowledged"))
    }
    private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key)
}
