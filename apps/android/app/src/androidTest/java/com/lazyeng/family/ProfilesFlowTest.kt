package com.lazyeng.family

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.datastore.DataStoreCurrentProfileStore
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.security.*
import com.lazyeng.family.feature.profiles.*
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real repositories, DataStore, Keystore and PIN KDF; isolated from the installed family's files. */
@RunWith(AndroidJUnit4::class)
class ProfilesFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: ProfilesViewModel
    private lateinit var pins: CharArray
    private val family = FamilyId("fixture-profile-management")
    private val first = ProfileId("fixture-one")
    private val second = ProfileId("fixture-two")
    private val created = ProfileId("fixture-created")

    @Test fun realSwitchCreateEditAndFreshPinDeletion() = scenario {
        phase(ProfilesPhase.HOME)
        click("open-picker")
        phase(ProfilesPhase.PICKER)
        evidence("picker")
        click("choose-${second.value}")
        phase(ProfilesPhase.HOME)
        compose.onNodeWithText("示例成员乙").assertExists()
        compose.onNodeWithText("示例成员甲").assertDoesNotExist()
        evidence("switched-home")
        enterParent()
        click("create-profile"); phase(ProfilesPhase.EDIT)
        compose.onNodeWithTag("profile-nickname").performTextInput("示例新成员")
        click("avatar-moon")
        click("role-ADULT")
        compose.onNodeWithTag("profile-age").performScrollTo().performTextReplacement("adult")
        compose.onNodeWithTag("profile-level").performScrollTo().performTextReplacement("Starter")
        click("profile-save"); phase(ProfilesPhase.OVERVIEW)
        click("edit-${created.value}"); phase(ProfilesPhase.EDIT)
        compose.onNodeWithTag("profile-nickname").performScrollTo().performTextReplacement("示例已编辑")
        click("profile-save"); phase(ProfilesPhase.OVERVIEW)
        compose.onNodeWithText("示例已编辑").assertExists()
        evidence("parent-overview")
        click("delete-${created.value}"); phase(ProfilesPhase.DELETE_PIN)
        compose.onNodeWithTag("pin-submit").assertIsNotEnabled()
        evidence("delete-confirmation")
        // Wrong second confirmation cannot remove a record, even after a valid parent gate.
        val wrong = pins.copyOf().also { it[0] = ('0'.code + (it[0].digitToInt() + 1) % 10).toChar() }
        submit(wrong); wrong.fill('\u0000')
        compose.waitUntil(20_000) { vm.state.value.message != null && !vm.state.value.busy }
        assertEquals(ProfilesPhase.DELETE_PIN, vm.state.value.phase)
        click("cancel-pin"); phase(ProfilesPhase.PICKER)
        compose.onNodeWithText("示例已编辑").assertExists()
        enterParent()
        click("delete-${created.value}"); phase(ProfilesPhase.DELETE_PIN)
        submit(pins); phase(ProfilesPhase.OVERVIEW)
        compose.onNodeWithText("示例已编辑").assertDoesNotExist()
        assertEquals(2, vm.state.value.members.size)
        evidence("after-deletion")
    }

    @Test fun largeFontEditorAndSecondConfirmationActionsAreReachable() = scenario(1.3f) {
        phase(ProfilesPhase.HOME)
        click("open-picker"); phase(ProfilesPhase.PICKER)
        evidence("picker-font-1.3")
        enterParent()
        click("edit-${first.value}"); phase(ProfilesPhase.EDIT)
        compose.onNodeWithTag("profile-nickname").performScrollTo().assertIsDisplayed()
        evidence("editor-font-1.3-top")
        compose.onNodeWithTag("profile-save").performScrollTo().assertIsDisplayed().assertIsEnabled()
        evidence("editor-font-1.3-actions")
        click("profile-save"); phase(ProfilesPhase.OVERVIEW)
        click("delete-${first.value}"); phase(ProfilesPhase.DELETE_PIN)
        compose.onNodeWithTag("pin-submit").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        evidence("delete-font-1.3")
        click("cancel-pin"); phase(ProfilesPhase.PICKER)
    }

    @Test fun loadingDisablesPinAndBackgroundRevokesParentSession() = scenario {
        phase(ProfilesPhase.HOME)
        click("open-parent"); phase(ProfilesPhase.PIN)
        submit(pins)
        // KDF is genuinely pending on this device; no artificial busy duration is introduced.
        compose.onNodeWithTag("pin-submit").assertIsNotEnabled()
        phase(ProfilesPhase.OVERVIEW)
        compose.runOnIdle { vm.onBackground() }
        phase(ProfilesPhase.PICKER)
        compose.onNodeWithTag("create-profile").assertDoesNotExist()
        click("open-parent"); phase(ProfilesPhase.PIN)
        compose.onNodeWithTag("pin-submit").assertIsNotEnabled()
    }

    private fun scenario(scale: Float = 1f, body: () -> Unit) {
        val context = compose.activity.applicationContext
        val folder = File(context.cacheDir, "profiles-ui-${System.nanoTime()}").apply { mkdirs() }
        val pinContext = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }
        val db = Room.inMemoryDatabaseBuilder(context, FamilyDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder, "selection.preferences_pb") }
        val selection = DataStoreCurrentProfileStore(preferences, db.profileRepository())
        val clock = Clock { Instant.now() }
        val service = PinService(KeystorePinRecordStore.create(pinContext), clock)
        val random = SecureRandom()
        pins = CharArray(4) { ('0'.code + random.nextInt(10)).toChar() }
        val holder = ViewModelStore()
        try {
            runBlocking {
                db.familyRepository().saveFamily(Family(family, emptyMap(), Instant.EPOCH))
                listOf(first to "示例成员甲", second to "示例成员乙").forEach { (id, nickname) ->
                    db.profileRepository().saveProfile(family, id, Profile(id, family, nickname, "star", ProfileRole.CHILD,
                        "child", "Power Up 2", emptyMap(), Instant.EPOCH, null))
                }
                selection.selectProfile(family, first)
                service.setup(family, pins)
            }
            val deletions = ProfileDeletionCoordinator(db.profileDeletionTransaction()).apply {
                register("profile-body") { db.profileRepository().deleteProfile(it.familyId, it.profileId, it.requestedAt) }
            }
            val coordinator = ProfileCoordinator(family, db.profileRepository(), db.profileDirectory(), selection,
                ParentSession(family, service, clock), deletions, ProfileChanges(), clock) { created }
            compose.runOnUiThread {
                vm = ProfilesViewModel({ coordinator }, clock)
                holder.put("profiles", vm)
            }
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    LazyEngTheme { ProfilesRoute(vm) }
                }
            }
            body()
        } finally {
            compose.runOnUiThread { holder.clear() }
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            db.close()
            pins.fill('\u0000')
            check(folder.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            folder.deleteRecursively()
        }
    }
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun phase(phase: ProfilesPhase) = compose.waitUntil(30_000) { vm.state.value.phase == phase && !vm.state.value.busy }
    private fun enterParent() { click("open-parent"); phase(ProfilesPhase.PIN); submit(pins); phase(ProfilesPhase.OVERVIEW) }
    private fun submit(input: CharArray) {
        input.forEach { click("pin-digit-${it.digitToInt()}") }
        click("pin-submit")
    }
    private fun evidence(name: String) {
        val directory = File(compose.activity.filesDir, "profiles-evidence").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
