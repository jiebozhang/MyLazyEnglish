package com.lazyeng.family.feature.library

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.common.RestrictedContentOverrideAudit
import com.lazyeng.family.core.common.RestrictedContentOverrideEvent
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.security.*
import com.lazyeng.family.core.testing.TestDispatchers
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @Test fun authorizedOverrideIsAuditedAndLeavingScopeErasesRestrictedRows() = runTest {
        val dispatchers = TestDispatchers(testScheduler)
        Dispatchers.setMain(dispatchers.dispatcher)
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java)
            .allowMainThreadQueries().build()
        val store = object : PinRecordStore {
            var record: PinRecord? = null
            override suspend fun read() = record
            override suspend fun write(record: PinRecord) { this.record = record }
        }
        val now = Instant.parse("2026-09-27T00:00:00Z")
        val clock = Clock { now }
        val family = Family(FamilyId("fixture-family"), emptyMap(), now)
        val profileId = ProfileId("fixture-child")
        val profile = Profile(profileId, family.id, "Fixture", null, ProfileRole.CHILD, "child", "A1", emptyMap(), now, null)
        val pin = CharArray(4) { '4' }
        val pins = PinService(store, clock, PinDerivation { _, _, _ -> ByteArray(32) { 9 } }, dispatchers.dispatcher)
        val parent = ParentSession(family.id, pins, clock)
        val auditEvents = mutableListOf<RestrictedContentOverrideEvent>()
        val audit = object : RestrictedContentOverrideAudit {
            override suspend fun append(event: RestrictedContentOverrideEvent) { auditEvents += event }
            override suspend fun list(profileId: ProfileId) = auditEvents.filter { it.profileId == profileId }
        }
        val viewModels = ViewModelStore()
        try {
            db.familyRepository().saveFamily(family)
            db.profileRepository().saveProfile(family.id, profileId, profile)
            val videos = db.videoRepository()
            videos.saveVideo(profileId, fixtureVideo("child", "unknown"))
            val hidden = fixtureVideo("adult", "adult")
            videos.saveVideo(profileId, hidden.copy(metadata = hidden.metadata.copy(hiddenFromChildren = true)))
            pins.setup(family.id, pin)
            val catalog = db.videoRepository(parentSession = parent)
            val access = catalog as ParentVideoAccess
            val session = LibrarySession(
                LibraryData(family.id, profileId, profile.role, catalog, access) { true }, parent, true,
            )
            val model = LibraryViewModel({ session }, audit, clock)
            viewModels.put("library", model)
            awaitState { !model.state.value.loading }
            assertEquals(listOf(VideoId("child")), model.state.value.videos.map { it.id })

            model.onEvent(LibraryUiEvent.RequestOverride)
            runCurrent()
            repeat(4) { model.onEvent(LibraryUiEvent.Digit(4)) }
            model.onEvent(LibraryUiEvent.SubmitPin)
            awaitState { !model.state.value.loading && model.state.value.overrideActive }
            assertEquals(setOf(VideoId("child"), VideoId("adult")), model.state.value.videos.map { it.id }.toSet())
            assertEquals(listOf(RestrictedContentOverrideEvent(family.id, profileId, now)), auditEvents)

            model.onBackground()
            assertTrue(model.state.value.videos.isEmpty())
            assertFalse(parent.authorized())
            pin.fill('\u0000')
        } finally {
            viewModels.clear()
            db.close()
            Dispatchers.resetMain()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.awaitState(predicate: () -> Boolean) {
        withTimeout(10_000) {
            while (!predicate()) {
                runCurrent()
                delay(1)
            }
        }
    }

    private fun fixtureVideo(id: String, age: String) = Video(
        VideoId(id), VideoSourceType.LOCAL_FILE, VideoSourceReference("content://fixture/$id"), "fixture-hash",
        VideoMetadata("Fixture $id", null, 12_000, listOf("nature"), "A1", 5, null, age),
        VideoStatus.DRAFT, ImportStatus.Draft, SubtitleStatus.NoSubtitle, TranslationStatus.Unknown("NONE"), false,
        VideoRights("fixture", null, null, true),
    )
}
