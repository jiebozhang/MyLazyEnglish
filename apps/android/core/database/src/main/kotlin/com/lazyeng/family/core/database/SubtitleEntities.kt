package com.lazyeng.family.core.database

import androidx.room.*
import com.lazyeng.family.core.model.*
import java.time.Instant

@Entity(tableName = "subtitle_tracks", foreignKeys = [
    ForeignKey(entity = VideoEntity::class, parentColumns = ["id"], childColumns = ["video_id"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = SubtitleVersionEntity::class, parentColumns = ["track_id", "id"], childColumns = ["id", "current_version_id"],
        onDelete = ForeignKey.RESTRICT, deferred = true),
], indices = [Index("video_id"), Index(value = ["id", "current_version_id"])])
internal data class SubtitleTrackEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "video_id") val videoId: String,
    val language: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "current_version_id") val currentVersionId: String?,
)

@Entity(tableName = "subtitle_versions", foreignKeys = [
    ForeignKey(entity = SubtitleTrackEntity::class, parentColumns = ["id"], childColumns = ["track_id"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["track_id", "id"], unique = true)])
internal data class SubtitleVersionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "source_hash") val sourceHash: String,
    @ColumnInfo(name = "parser_ver") val parserVersion: String,
    val status: String,
    @ColumnInfo(name = "source_encoding") val sourceEncoding: String?,
    @ColumnInfo(name = "published_at") val publishedAt: Instant?,
)

@Entity(tableName = "subtitle_lines", foreignKeys = [
    ForeignKey(entity = SubtitleVersionEntity::class, parentColumns = ["id"], childColumns = ["version_id"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["version_id", "seq"], unique = true)])
internal data class SubtitleLineEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "version_id") val versionId: String,
    @ColumnInfo(name = "seq") val sequence: Int,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    val text: String,
)

internal fun SubtitleTrackEntity.toDomain() = SubtitleTrack(SubtitleTrackId(id), VideoId(videoId), language, sourceType, currentVersionId?.let(::SubtitleVersionId))
internal fun SubtitleLineEntity.toDomain() = SubtitleLine(SubtitleLineId(id), SubtitleVersionId(versionId), sequence, startMs, endMs, text)
internal fun SubtitleLine.toEntity() = SubtitleLineEntity(id.value, versionId.value, sequence, startMs, endMs, text)
internal fun SubtitleVersionEntity.toDraft() = DraftSubtitleVersion(SubtitleVersionId(id), SubtitleTrackId(trackId), sourceHash, parserVersion, sourceEncoding)
internal fun SubtitleVersionEntity.toPublished() = PublishedSubtitleVersion(SubtitleVersionId(id), SubtitleTrackId(trackId),
    sourceHash, parserVersion, checkNotNull(publishedAt), sourceEncoding)
