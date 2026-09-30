package com.lazyeng.family.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vocabulary_items",
    foreignKeys = [ForeignKey(
        entity = ProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profile_id"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["profile_id", "lemma"], unique = true), Index("profile_id")],
)
internal data class VocabularyItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "profile_id") val profileId: String,
    val lemma: String,
    val status: String,
    val stage: Int = 0,
    @ColumnInfo(name = "next_review_at_ms") val nextReviewAtMs: Long?,
    @ColumnInfo(name = "review_count", defaultValue = "0") val reviewCount: Int = 0,
    @ColumnInfo(name = "lapse_count", defaultValue = "0") val lapseCount: Int = 0,
    @ColumnInfo(name = "consecutive_remembered", defaultValue = "0") val consecutiveRemembered: Int = 0,
    @ColumnInfo(name = "last_reviewed_at_ms") val lastReviewedAtMs: Long? = null,
)

@Entity(
    tableName = "vocabulary_contexts",
    foreignKeys = [ForeignKey(
        entity = VocabularyItemEntity::class,
        parentColumns = ["profile_id", "lemma"],
        childColumns = ["profile_id", "lemma"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["profile_id", "lemma", "video_id", "line_id", "timestamp_ms"], unique = true),
        Index(value = ["profile_id", "lemma"]),
    ],
)
internal data class VocabularyContextEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "profile_id") val profileId: String,
    val lemma: String,
    val surface: String,
    val phonetic: String?,
    val definition: String?,
    @ColumnInfo(name = "sense_id") val senseId: String?,
    @ColumnInfo(name = "context_sentence") val contextSentence: String,
    @ColumnInfo(name = "video_id") val videoId: String,
    @ColumnInfo(name = "line_id") val lineId: String,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

@Entity(
    tableName = "review_attempts",
    foreignKeys = [ForeignKey(
        entity = ProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profile_id"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["profile_id", "lemma", "occurred_at_ms"]),
        Index(value = ["profile_id", "event_uuid"], unique = true),
    ],
)
internal data class ReviewAttemptEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "event_uuid") val eventUuid: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    val lemma: String,
    val rating: String,
    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
)
