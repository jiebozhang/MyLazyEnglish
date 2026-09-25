package com.lazyeng.family.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lazyeng.family.core.model.Family
import com.lazyeng.family.core.model.FamilyId
import com.lazyeng.family.core.model.Profile
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.ProfileRole
import java.time.Instant

@Entity(tableName = "families")
internal data class FamilyEntity(
    @PrimaryKey val id: String,
    val settings: Map<String, String>,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
)

@Entity(
    tableName = "profiles",
    foreignKeys = [ForeignKey(
        entity = FamilyEntity::class,
        parentColumns = ["id"],
        childColumns = ["family_id"],
        onDelete = ForeignKey.RESTRICT,
        onUpdate = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["family_id", "id"], unique = true)],
)
internal data class ProfileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "family_id") val familyId: String,
    val nickname: String,
    @ColumnInfo(name = "avatar_id") val avatarId: String?,
    val role: ProfileRole,
    @ColumnInfo(name = "age_mode") val ageMode: String,
    @ColumnInfo(name = "english_level") val englishLevel: String,
    @ColumnInfo(name = "ui_preferences") val uiPreferences: Map<String, String>,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant?,
)

internal fun Family.toEntity() = FamilyEntity(id.value, settings, createdAt)
internal fun FamilyEntity.toDomain() = Family(FamilyId(id), settings, createdAt)
internal fun Profile.toEntity() = ProfileEntity(
    id.value, familyId.value, nickname, avatarId, role, ageMode,
    englishLevel, uiPreferences, createdAt, deletedAt,
)
internal fun ProfileEntity.toDomain() = Profile(
    ProfileId(id), FamilyId(familyId), nickname, avatarId, role, ageMode,
    englishLevel, uiPreferences, createdAt, deletedAt,
)
