package com.lazyeng.family

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.security.*
import com.lazyeng.family.feature.profiles.*
import com.lazyeng.family.feature.parent.ParentDashboardData
import com.lazyeng.family.feature.parent.ParentDashboardDataSource
import com.lazyeng.family.feature.parent.RecentLearningItem
import com.lazyeng.family.feature.parent.ParentDashboardViewModel
import com.lazyeng.family.navigation.*
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationParentGateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun parentDeepLinkSavedStateAndBackgroundNeverRestoreAuthorization() {
        val db = Room.inMemoryDatabaseBuilder(compose.activity, FamilyDatabase::class.java).build()
        val family = FamilyId("navigation-fixture")
        val clock = Clock { Instant.EPOCH }
        val records = object : PinRecordStore {
            var record = PinRecord(family, 1, PinPolicy.algorithm, PinPolicy.minimumIterations, ByteArray(16), ByteArray(32))
            override suspend fun read() = record
            override suspend fun write(record: PinRecord) { this.record = record }
        }
        // Only KDF is substituted; the production ParentSession, PIN service and Profiles VM gate run.
        val service = PinService(records, clock, PinDerivation { _, _, _ -> ByteArray(32) })
        val selection = object : CurrentProfileStore {
            override suspend fun getCurrentProfileId(familyId: FamilyId): ProfileId? = null
            override suspend fun selectProfile(familyId: FamilyId, profileId: ProfileId) = false
            override suspend fun clearSelection(familyId: FamilyId) = Unit
        }
        val store = ViewModelStore()
        lateinit var model: ProfilesViewModel
        try {
            runBlocking {
                db.familyRepository().saveFamily(Family(family, emptyMap(), Instant.EPOCH))
                val profile = Profile(ProfileId("parent-dashboard-child"), family, "测试档案", null, ProfileRole.CHILD,
                    "child", "A1", emptyMap(), Instant.EPOCH, null)
                db.profileRepository().saveProfile(family, profile.id, profile)
                val otherProfile = Profile(ProfileId("parent-dashboard-other-child"), family, "另一个测试档案", null, ProfileRole.CHILD,
                    "child", "A2", emptyMap(), Instant.EPOCH, null)
                db.profileRepository().saveProfile(family, otherProfile.id, otherProfile)
            }
            compose.runOnUiThread {
                model = ProfilesViewModel({ ProfileCoordinator(family, db.profileRepository(), db.profileDirectory(), selection,
                    ParentSession(family, service, clock), ProfileDeletionCoordinator(db.profileDeletionTransaction()),
                    ProfileChanges(), clock, { ProfileId("fixture-new") }) }, clock)
                store.put("parent", model)
            }
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                LazyEngTheme {
                    ChildShell(pendingLink = "lazyeng://app/parent") { _, back, go ->
                        ProfilesNavigation(model, true, back, go) { profiles ->
                            ParentDashboardViewModel(
                                profiles, profiles.firstOrNull()?.id,
                                ParentDashboardDataSource { id ->
                                    ParentDashboardData(
                                        id, 2, if (id.value == "parent-dashboard-other-child") 3 else 1, 1,
                                        listOf(RecentLearningItem(VideoId("fixture-video"), "虚构示例视频", Instant.EPOCH, 35)),
                                    )
                                },
                                object : DispatcherProvider {
                                    override val io = Dispatchers.IO
                                    override val computation = Dispatchers.Default
                                    override val main = Dispatchers.Main
                                },
                            )
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { model.state.value.phase == ProfilesPhase.PIN }
            compose.onNodeWithTag("child-bottom-bar").assertDoesNotExist()
            compose.onNodeWithTag("create-profile").assertDoesNotExist()
            compose.runOnIdle { repeat(4) { model.onEvent(ProfilesUiEvent.Digit(it)) }; model.onEvent(ProfilesUiEvent.SubmitPin) }
            compose.waitUntil(10_000) { model.state.value.phase == ProfilesPhase.OVERVIEW }
            compose.onNodeWithText("家长控制台").assertIsDisplayed()
            compose.onNodeWithTag("parent-dashboard-content").assertIsDisplayed()
            compose.onNodeWithTag("parent-import-video").assertIsDisplayed()
            compose.onNodeWithText("虚构示例视频").assertIsDisplayed()
            compose.onNodeWithTag("parent-profile-picker").performClick()
            compose.onNodeWithTag("parent-profile-parent-dashboard-other-child").performClick()
            compose.onNodeWithText("3 词 · 已掌握 1").assertIsDisplayed()
            compose.onNodeWithTag("child-bottom-bar").assertDoesNotExist()
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(10_000) { model.state.value.phase == ProfilesPhase.PIN }
            compose.onNodeWithTag("parent-dashboard").assertDoesNotExist()
            compose.onNodeWithTag("create-profile").assertDoesNotExist()
            compose.runOnIdle { repeat(4) { model.onEvent(ProfilesUiEvent.Digit(it)) }; model.onEvent(ProfilesUiEvent.SubmitPin) }
            compose.waitUntil(10_000) { model.state.value.phase == ProfilesPhase.OVERVIEW }
            compose.runOnIdle { model.onBackground() }
            compose.waitUntil(10_000) { model.state.value.phase == ProfilesPhase.PIN }
            assertEquals(0, model.state.value.enteredCount)
            compose.onNodeWithTag("parent-dashboard").assertDoesNotExist()
        } finally { compose.runOnUiThread { store.clear() }; db.close() }
    }
}
