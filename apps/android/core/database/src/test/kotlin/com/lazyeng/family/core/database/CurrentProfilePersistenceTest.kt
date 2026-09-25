package com.lazyeng.family.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import com.lazyeng.family.core.datastore.DataStoreCurrentProfileStore
import com.lazyeng.family.core.model.ProfileId
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CurrentProfilePersistenceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var database: FamilyDatabase
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var store: DataStoreCurrentProfileStore
    private lateinit var storeJob: Job

    @Before fun openAndSeed() = runBlocking {
        open()
        database.familyRepository().saveFamily(familyA)
        database.familyRepository().saveFamily(familyB)
        for (profile in listOf(sampleProfile(), sampleProfile(familyB, "fixture-profile-b"))) {
            database.profileRepository().saveProfile(profile.familyId, profile.id, profile)
        }
    }
    @After fun close() = runBlocking { closeStorage() }

    private fun open() {
        database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java,
            File(temporaryFolder.root, "family.db").absolutePath).build()
        storeJob = SupervisorJob()
        preferences = PreferenceDataStoreFactory.create(scope = CoroutineScope(storeJob + Dispatchers.IO)) {
            File(temporaryFolder.root, "current.preferences_pb")
        }
        store = DataStoreCurrentProfileStore(preferences, database.profileRepository())
    }
    private suspend fun closeStorage() {
        storeJob.cancelAndJoin()
        database.close()
    }
    private suspend fun recreate() {
        closeStorage()
        open()
    }

    @Test fun restoresPersistedSelectionUsingNewDatabaseAndDataStoreInstances() = runTest {
        val profile = sampleProfile()
        assertTrue(store.selectProfile(familyA.id, profile.id))
        recreate()
        assertEquals(profile.id, store.getCurrentProfileId(familyA.id))
        assertEquals(profile, database.profileRepository().getProfile(familyA.id, profile.id))
        assertEquals(setOf("current_family_id", "current_profile_id"), preferences.data.first().asMap().keys.map { it.name }.toSet())
    }

    @Test fun deletedSelectionAfterRecreationReturnsNullAndClearsStalePointer() = runTest {
        val profile = sampleProfile()
        store.selectProfile(familyA.id, profile.id)
        database.profileRepository().deleteProfile(familyA.id, profile.id, createdAt.plusSeconds(10))
        recreate()
        assertNull(store.getCurrentProfileId(familyA.id))
        assertTrue(preferences.data.first().asMap().isEmpty())
        assertNotNull(database.profileDao().getRecord(familyA.id.value, profile.id.value)?.deletedAt)
        assertNotNull(database.profileRepository().getProfile(familyB.id, ProfileId("fixture-profile-b")))
    }

    @Test fun missingSelectionDoesNotChooseAnotherExistingProfile() = runTest {
        writePointer("missing-profile")
        recreate()
        assertNull(store.getCurrentProfileId(familyA.id))
        assertTrue(preferences.data.first().asMap().isEmpty())
    }

    @Test fun forgedCrossFamilyPointerReturnsNull() = runTest {
        writePointer("fixture-profile-b")
        recreate()
        assertNull(store.getCurrentProfileId(familyA.id))
        assertTrue(preferences.data.first().asMap().isEmpty())
    }

    @Test fun invalidSelectionsCannotReplaceAnExistingValidSelection() = runTest {
        val profile = sampleProfile()
        store.selectProfile(familyA.id, profile.id)
        assertFalse(store.selectProfile(familyA.id, ProfileId("missing")))
        assertFalse(store.selectProfile(familyB.id, profile.id))
        assertFalse(store.selectProfile(familyA.id, ProfileId("fixture-profile-b")))
        assertEquals(profile.id, store.getCurrentProfileId(familyA.id))
        database.profileRepository().deleteProfile(familyB.id, ProfileId("fixture-profile-b"), createdAt.plusSeconds(2))
        assertFalse(store.selectProfile(familyB.id, ProfileId("fixture-profile-b")))
        assertEquals(profile.id, store.getCurrentProfileId(familyA.id))
    }

    @Test fun clearAndRestoreAreScopedToRequestedHousehold() = runTest {
        val profile = sampleProfile()
        store.selectProfile(familyA.id, profile.id)
        assertNull(store.getCurrentProfileId(familyB.id))
        store.clearSelection(familyB.id)
        assertEquals(profile.id, store.getCurrentProfileId(familyA.id))
        store.clearSelection(familyA.id)
        recreate()
        assertNull(store.getCurrentProfileId(familyA.id))
    }

    @Test fun switchingSelectionWritesTheNewFamilyAndProfileTogether() = runTest {
        store.selectProfile(familyA.id, sampleProfile().id)
        val secondId = ProfileId("fixture-profile-b")
        assertTrue(store.selectProfile(familyB.id, secondId))
        recreate()
        assertNull(store.getCurrentProfileId(familyA.id))
        assertEquals(secondId, store.getCurrentProfileId(familyB.id))
    }

    @Test fun emptyAndIncompletePreferencesYieldSafeSelection() = runTest {
        assertNull(store.getCurrentProfileId(familyA.id))
        preferences.edit { it[stringPreferencesKey("current_family_id")] = familyA.id.value }
        assertNull(store.getCurrentProfileId(familyA.id))
        assertTrue(preferences.data.first().asMap().isEmpty())
    }

    @Test fun databaseFailureDoesNotEraseSelectionOrFallBackToAnotherProfile() = runTest {
        store.selectProfile(familyA.id, sampleProfile().id)
        val saved = preferences.data.first()
        database.close()
        assertTrue(runCatching { store.getCurrentProfileId(familyA.id) }.isFailure)
        assertEquals(saved, preferences.data.first())
    }

    private suspend fun writePointer(profileId: String) {
        preferences.edit {
            it[stringPreferencesKey("current_family_id")] = familyA.id.value
            it[stringPreferencesKey("current_profile_id")] = profileId
        }
    }
}
