package com.lazyeng.family.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lazyeng.family.core.model.CurrentProfileStore
import com.lazyeng.family.core.model.FamilyId
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.ProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.currentProfileDataStore by preferencesDataStore(
    name = "current_profile",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class DataStoreCurrentProfileStore(
    private val dataStore: DataStore<Preferences>,
    private val profiles: ProfileRepository,
) : CurrentProfileStore {
    private val selectionMutex = Mutex()

    override suspend fun getCurrentProfileId(familyId: FamilyId): ProfileId? = selectionMutex.withLock {
        val saved = dataStore.data.first()
        if (saved[FAMILY_ID] != familyId.value) return@withLock null
        val rawId = saved[PROFILE_ID]
        val profileId = rawId?.takeIf { it.isNotBlank() }?.let(::ProfileId)
        if (profileId != null && profiles.getProfile(familyId, profileId) != null) {
            return@withLock profileId
        }
        // Room and DataStore have no shared transaction. A stale pointer is never a default profile.
        dataStore.edit { current ->
            if (current[FAMILY_ID] == saved[FAMILY_ID] && current[PROFILE_ID] == rawId) {
                current.remove(FAMILY_ID)
                current.remove(PROFILE_ID)
            }
        }
        null
    }

    override suspend fun selectProfile(familyId: FamilyId, profileId: ProfileId): Boolean = selectionMutex.withLock {
        if (profiles.getProfile(familyId, profileId) == null) return@withLock false
        dataStore.edit {
            it[FAMILY_ID] = familyId.value
            it[PROFILE_ID] = profileId.value
        }
        true
    }

    override suspend fun clearSelection(familyId: FamilyId) {
        selectionMutex.withLock {
            dataStore.edit {
                if (it[FAMILY_ID] == familyId.value) {
                    it.remove(FAMILY_ID)
                    it.remove(PROFILE_ID)
                }
            }
        }
    }

    companion object {
        private val FAMILY_ID = stringPreferencesKey("current_family_id")
        private val PROFILE_ID = stringPreferencesKey("current_profile_id")

        /** Own one store facade per application, backed by the singleton preferences delegate. */
        fun create(context: Context, profiles: ProfileRepository): CurrentProfileStore =
            DataStoreCurrentProfileStore(context.applicationContext.currentProfileDataStore, profiles)
    }
}
