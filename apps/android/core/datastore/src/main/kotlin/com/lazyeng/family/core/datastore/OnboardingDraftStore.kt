package com.lazyeng.family.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lazyeng.family.core.model.FamilyId
import com.lazyeng.family.core.model.ProfileId
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first

data class OnboardingDraft(
    val familyId: FamilyId,
    val profileId: ProfileId,
    val createdAt: Instant,
    val nickname: String = "",
    val level: String = "Power Up 2",
    val avatar: String = "sun",
)

interface OnboardingDraftStore {
    suspend fun loadOrCreate(): OnboardingDraft
    suspend fun saveDetails(nickname: String, level: String, avatar: String): OnboardingDraft
    suspend fun completed(): Boolean
    suspend fun markCompleted()
}

// Corrupt onboarding state must fail closed, not silently create new family identities.
private val Context.onboardingDataStore by preferencesDataStore(name = "onboarding")

class PreferencesOnboardingDraftStore(
    private val store: DataStore<Preferences>,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : OnboardingDraftStore {
    override suspend fun completed(): Boolean = store.data.first()[COMPLETED] ?: false
    override suspend fun markCompleted() { store.edit { decode(it); it[COMPLETED] = true } }
    override suspend fun loadOrCreate(): OnboardingDraft {
        val saved = store.edit {
            if (it.asMap().isEmpty()) {
                it[FAMILY] = newId()
                it[PROFILE] = newId()
                it[CREATED] = now().toString()
                it[NICKNAME] = ""
                it[LEVEL] = "Power Up 2"
                it[AVATAR] = "sun"
            }
        }
        return decode(saved)
    }
    override suspend fun saveDetails(nickname: String, level: String, avatar: String): OnboardingDraft {
        val saved = store.edit {
            decode(it)
            it[NICKNAME] = nickname
            it[LEVEL] = level
            it[AVATAR] = avatar
        }
        return decode(saved)
    }
    private fun decode(data: Preferences) = OnboardingDraft(
        FamilyId(checkNotNull(data[FAMILY])), ProfileId(checkNotNull(data[PROFILE])),
        Instant.parse(checkNotNull(data[CREATED])), checkNotNull(data[NICKNAME]),
        checkNotNull(data[LEVEL]), checkNotNull(data[AVATAR]),
    )
    companion object {
        private val COMPLETED = booleanPreferencesKey("completed")
        private val FAMILY = stringPreferencesKey("family_id")
        private val PROFILE = stringPreferencesKey("profile_id")
        private val CREATED = stringPreferencesKey("created_at")
        private val NICKNAME = stringPreferencesKey("nickname")
        private val LEVEL = stringPreferencesKey("level")
        private val AVATAR = stringPreferencesKey("avatar")
        fun create(context: Context): OnboardingDraftStore =
            PreferencesOnboardingDraftStore(context.applicationContext.onboardingDataStore)
    }
}
