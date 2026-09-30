package com.lazyeng.family.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lazyeng.family.core.model.EventUuid
import com.lazyeng.family.core.model.LookupEvent
import com.lazyeng.family.core.model.LookupEventId
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.SubtitleLineId
import com.lazyeng.family.core.model.VideoId
import java.time.Instant
import java.util.Locale

@Entity(
    tableName = "lookup_events",
    foreignKeys = [ForeignKey(
        entity = ProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profile_id"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["profile_id", "lemma", "line_id"], unique = true),
        Index(value = ["event_uuid"], unique = true),
        Index(value = ["profile_id", "occurred_at"]),
    ],
)
internal data class LookupEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "event_uuid") val eventUuid: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    val word: String,
    val lemma: String,
    @ColumnInfo(name = "line_id") val lineId: String?,
    @ColumnInfo(name = "video_id") val videoId: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Instant,
)

internal fun LookupEvent.toEntity() = LookupEventEntity(
    id.value, eventUuid.value, profileId.value, word, lemma.trim().lowercase(Locale.ROOT),
    lineId?.value, videoId?.value, occurredAt,
)

internal fun LookupEventEntity.toDomain() = LookupEvent(
    id = LookupEventId(id),
    eventUuid = EventUuid(eventUuid),
    profileId = ProfileId(profileId),
    word = word,
    lemma = lemma,
    lineId = lineId?.let(::SubtitleLineId),
    videoId = videoId?.let(::VideoId),
    occurredAt = occurredAt,
)
