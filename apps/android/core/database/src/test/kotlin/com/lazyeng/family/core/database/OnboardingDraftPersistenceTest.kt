package com.lazyeng.family.core.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lazyeng.family.core.datastore.PreferencesOnboardingDraftStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OnboardingDraftPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun reopensDraftWithoutRegeneratingIdentityAndStoresOnlyNonSecretFields() = runBlocking {
        val file = File(temporary.root, "onboarding.preferences_pb")
        var job = SupervisorJob()
        var data = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
        var counter = 0
        val original = PreferencesOnboardingDraftStore(data, { Instant.EPOCH }, { "fixture-${counter++}" })
        val created = original.loadOrCreate()
        val saved = original.saveDetails("Fixture child", "Fixture level", "star")
        assertFalse(original.completed())
        original.markCompleted()
        assertEquals(created.familyId, saved.familyId)
        assertEquals(created.profileId, saved.profileId)
        job.cancelAndJoin()
        job = SupervisorJob()
        data = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
        try {
            val restored = PreferencesOnboardingDraftStore(data, { error("No real time needed") }, { error("No new ID") })
            assertEquals(saved, restored.loadOrCreate())
            assertTrue(restored.completed())
            assertEquals(setOf("family_id", "profile_id", "created_at", "nickname", "level", "avatar", "completed"),
                data.data.first().asMap().keys.map { it.name }.toSet())
        } finally { job.cancelAndJoin() }
    }

    @Test fun incompleteDraftFailsClosedAndIsNotReplaced() = runBlocking {
        val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) {
            File(temporary.root, "broken.preferences_pb")
        }
        try {
            data.edit { it[stringPreferencesKey("family_id")] = "fixture-family" }
            val original = data.data.first()
            assertTrue(runCatching { PreferencesOnboardingDraftStore(data).loadOrCreate() }.isFailure)
            assertEquals(original, data.data.first())
        } finally { job.cancelAndJoin() }
    }
}
