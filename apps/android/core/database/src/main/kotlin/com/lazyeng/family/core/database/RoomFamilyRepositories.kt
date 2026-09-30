package com.lazyeng.family.core.database

import androidx.room.withTransaction
import com.lazyeng.family.core.model.Family
import com.lazyeng.family.core.model.FamilyId
import com.lazyeng.family.core.model.FamilyRepository
import com.lazyeng.family.core.model.Profile
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.ProfileRepository
import com.lazyeng.family.core.model.ProfileDirectory
import com.lazyeng.family.core.model.ProfileChoice
import com.lazyeng.family.core.model.ProfileRole
import java.time.Instant

internal class RoomFamilyRepository(private val database: FamilyDatabase) : FamilyRepository {
    override suspend fun getFamily(familyId: FamilyId): Family? =
        database.familyDao().get(familyId.value)?.toDomain()

    override suspend fun saveFamily(family: Family) {
        require(family.id.value.isNotBlank()) { "Family ID is required" }
        database.withTransaction {
            val dao = database.familyDao()
            val existing = dao.get(family.id.value)
            if (existing == null) {
                dao.insert(family.toEntity())
            } else {
                require(existing.createdAt == family.createdAt) { "Creation time is immutable" }
                dao.update(family.toEntity())
            }
        }
    }
}

internal class RoomProfileRepository(private val database: FamilyDatabase) : ProfileRepository, ProfileDirectory {
    override suspend fun choices(familyId: FamilyId): List<ProfileChoice> =
        database.profileDao().choices(familyId.value).map {
            ProfileChoice(ProfileId(it.id), it.nickname, it.avatar_id, ProfileRole.valueOf(it.role), it.english_level)
        }
    override suspend fun getProfile(familyId: FamilyId, profileId: ProfileId): Profile? =
        database.profileDao().getActive(familyId.value, profileId.value)?.toDomain()

    override suspend fun saveProfile(familyId: FamilyId, profileId: ProfileId, profile: Profile) {
        require(profileId.value.isNotBlank() && familyId.value.isNotBlank()) { "Scope IDs are required" }
        require(profileId == profile.id && familyId == profile.familyId) { "Profile scope mismatch" }
        require(profile.deletedAt == null) { "Use deleteProfile to create a tombstone" }
        database.withTransaction {
            val dao = database.profileDao()
            val existing = dao.getRecord(familyId.value, profileId.value)
            if (existing == null) {
                // ABORT on a reused global ID or absent family; never REPLACE another owner's row.
                dao.insert(profile.toEntity())
            } else {
                require(existing.deletedAt == null) { "Deleted profiles cannot be restored by save" }
                require(existing.createdAt == profile.createdAt) { "Creation time is immutable" }
                dao.updateDetails(
                    familyId.value, profileId.value, profile.nickname, profile.avatarId,
                    profile.role.name, profile.ageMode, profile.englishLevel, profile.uiPreferences,
                )
            }
        }
    }

    override suspend fun deleteProfile(familyId: FamilyId, profileId: ProfileId, deletedAt: Instant) {
        database.withTransaction {
            val dao = database.profileDao()
            val existing = dao.getActive(familyId.value, profileId.value) ?: return@withTransaction
            require(!deletedAt.isBefore(existing.createdAt)) { "Deletion predates creation" }
            dao.markDeleted(familyId.value, profileId.value, deletedAt)
        }
    }
}
