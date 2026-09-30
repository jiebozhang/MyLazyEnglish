package com.lazyeng.family.core.database

import androidx.room.withTransaction
import com.lazyeng.family.core.model.ProfileCleaner
import com.lazyeng.family.core.model.ProfileDeletionRequested

/** Deletes profile-owned rows within the coordinator's encompassing Room transaction. */
class RoomProfileCleaner(private val database: FamilyDatabase) : ProfileCleaner {
    override suspend fun clean(request: ProfileDeletionRequested) = database.withTransaction {
        val profile = database.profileDao().getActive(request.familyId.value, request.profileId.value)
            ?: return@withTransaction
        require(!request.requestedAt.isBefore(profile.createdAt)) { "Deletion predates profile creation" }
        database.videoDao().deleteProgressForProfile(request.profileId.value)
        database.lookupEventDao().deleteForProfile(request.profileId.value)
        database.profileDao().deletePermanently(request.familyId.value, request.profileId.value)
    }
}
