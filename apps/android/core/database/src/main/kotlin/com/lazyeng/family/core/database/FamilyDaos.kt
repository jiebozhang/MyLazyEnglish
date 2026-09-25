package com.lazyeng.family.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.time.Instant

@Dao
internal interface FamilyDao {
    @Query("SELECT * FROM families WHERE id = :familyId")
    suspend fun get(familyId: String): FamilyEntity?

    @Insert suspend fun insert(family: FamilyEntity)
    @Update suspend fun update(family: FamilyEntity)
}

@Dao
internal interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE family_id = :familyId AND id = :profileId AND deleted_at IS NULL")
    suspend fun getActive(familyId: String, profileId: String): ProfileEntity?

    /** Internal scoped tombstone read for mutation guards and audit tests. */
    @Query("SELECT * FROM profiles WHERE family_id = :familyId AND id = :profileId")
    suspend fun getRecord(familyId: String, profileId: String): ProfileEntity?

    @Insert suspend fun insert(profile: ProfileEntity)

    @Query("""
        UPDATE profiles SET nickname = :nickname, avatar_id = :avatarId, role = :role,
            age_mode = :ageMode, english_level = :englishLevel, ui_preferences = :preferences
        WHERE family_id = :familyId AND id = :profileId AND deleted_at IS NULL
    """)
    suspend fun updateDetails(
        familyId: String, profileId: String, nickname: String, avatarId: String?,
        role: String, ageMode: String, englishLevel: String, preferences: Map<String, String>,
    )

    @Query("""
        UPDATE profiles SET deleted_at = :deletedAt
        WHERE family_id = :familyId AND id = :profileId AND deleted_at IS NULL
    """)
    suspend fun markDeleted(familyId: String, profileId: String, deletedAt: Instant)
}
