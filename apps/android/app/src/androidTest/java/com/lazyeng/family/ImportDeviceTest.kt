package com.lazyeng.family

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.media.SafImportDocuments
import com.lazyeng.family.core.media.SafMediaReadiness
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.security.*
import com.lazyeng.family.feature.importmedia.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real DocumentsUI + production resolver/repositories. Only authorization and household are fixtures.
 * Run importViaSystemPicker, force-stop the target, then recoverAfterProcessDeath separately.
 * New-ImportFixtures.ps1 outputs must be pushed to Download first. Never uses the installed family's DB.
 */
@RunWith(AndroidJUnit4::class)
class ImportDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val profile = ProfileId("fixture-import-device")
    private val databaseName = "e2-t2-acceptance.db"
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Test fun importViaSystemPicker() {
        val context = compose.activity.applicationContext
        context.deleteDatabase(databaseName)
        val db = Room.databaseBuilder(context, FamilyDatabase::class.java, databaseName).build()
        val docs = SafImportDocuments(context)
        val familyId = FamilyId("fixture-import-device-family")
        val parent = parentSession(familyId)
        val catalog = db.videoRepository(SafMediaReadiness(docs), parentSession = parent)
        val parentVideos = catalog as ParentVideoAccess
        val importer = VideoImportCoordinator(profile, docs, catalog,
            db.videoAssetRepository(), db.importJobRepository(), db.subtitleRepository(), db.importTransaction(),
            { parent.checkScope(parent.requireScope()) }, Clock { Instant.now() }, { UUID.randomUUID().toString() },
            listVideosInAuthorizedScope = { parentVideos.listForAuthorizedParent(profile, parent.requireScope()) },
            getVideoInAuthorizedScope = { id -> parentVideos.getForAuthorizedParent(profile, id, parent.requireScope()) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            runBlocking {
                val family = Family(familyId, emptyMap(), Instant.EPOCH)
                db.familyRepository().saveFamily(family)
                db.profileRepository().saveProfile(family.id, profile, Profile(profile, family.id, "Fixture", "star",
                    ProfileRole.CHILD, "child", "Power Up 2", emptyMap(), Instant.EPOCH, null))
            }
            compose.setContent {
                val state by importer.state.collectAsState()
                var video by remember { mutableStateOf<FileReference?>(null) }
                var subtitle by remember { mutableStateOf<FileReference?>(null) }
                var rights by remember { mutableStateOf(false) }
                val videoPicker = rememberLauncherForActivityResult(DownloadsPicker()) { video = it?.toString()?.let(::FileReference) }
                val subtitlePicker = rememberLauncherForActivityResult(DownloadsPicker()) { subtitle = it?.toString()?.let(::FileReference) }
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    LazyEngTheme { ImportScreen(ImportScreenState(initialized = true, authorized = true,
                        videoSelected = video != null, subtitleSelected = subtitle != null, rights = rights,
                        busy = state.phase in setOf(ImportPhase.READING, ImportPhase.SAVING), import = state), { event ->
                        when (event) {
                            is ImportUiEvent.Choose -> if (event.subtitle) subtitlePicker.launch(arrayOf("*/*")) else videoPicker.launch(arrayOf("video/*"))
                            ImportUiEvent.Inspect -> scope.launch { importer.prepare(video, subtitle) }
                            is ImportUiEvent.Rights -> rights = event.value
                            is ImportUiEvent.Confirm -> scope.launch { importer.confirm(event.choice, rights) }
                            ImportUiEvent.Reset -> { video = null; subtitle = null; rights = false; scope.launch { importer.cancel() } }
                            else -> Unit
                        }
                    }, {}) }
                }
            }
            choose("select-video", "lazyeng-import-fixture.mp4")
            choose("select-subtitle", "lazyeng-import-fixture.srt")
            click("inspect-import")
            waitPhase(importer, ImportPhase.PREVIEW)
            evidence("preview-font-1.3")
            click("import-rights")
            compose.onNodeWithTag("confirm-import").performScrollTo().assertIsDisplayed().assertIsEnabled()
            evidence("confirm-font-1.3")
            click("confirm-import")
            waitPhase(importer, ImportPhase.COMPLETE)
            val saved = importer.state.value.video!!
            assertEquals(VideoStatus.READY, saved.status)
            assertEquals(320, saved.metadata.technical!!.width)
            assertEquals(240, saved.metadata.technical!!.height)
            assertTrue(saved.metadata.durationMs!! in 4900..5200)
            context.getSharedPreferences("e2-t2-evidence", 0).edit().putString("video", saved.id.value).commit()
            evidence("ready-font-1.3")
            report("MP4: READY, duration=${saved.metadata.durationMs}, resolution=320x240, published subtitle=true")

            click("reset-import")
            waitPhase(importer, ImportPhase.SELECT)
            choose("select-video", "lazyeng-import-fixture.mkv")
            click("inspect-import"); waitPhase(importer, ImportPhase.PREVIEW)
            click("import-rights"); click("confirm-import"); waitPhase(importer, ImportPhase.COMPLETE)
            assertEquals(VideoStatus.NEEDS_SUBTITLE, importer.state.value.video!!.status)
            report("MKV: NEEDS_SUBTITLE, duration=${importer.state.value.video!!.metadata.durationMs}, resolution=${importer.state.value.video!!.metadata.technical}")
            evidence("needs-subtitle-font-1.3")

            for ((index, name) in listOf("lazyeng-import-broken.mp4", "lazyeng-import-empty.mp4").withIndex()) {
                if (index == 0) click("reset-import")
                else compose.onNodeWithText("重新选择").performScrollTo().performClick()
                waitPhase(importer, ImportPhase.SELECT)
                choose("select-video", name)
                click("inspect-import"); waitPhase(importer, ImportPhase.ERROR)
                assertEquals(ImportErrors.invalid, importer.state.value.failure)
                report("$name: MEDIA_INVALID; no uncompleted database record")
                evidence(name.substringBeforeLast('.') + "-error")
            }
            runBlocking { assertEquals(2, parentVideos.listForAuthorizedParent(profile, parent.requireScope()).size) }
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            db.close()
        }
    }

    @Test fun recoverAfterProcessDeath() = runBlocking {
        val context = compose.activity.applicationContext
        val id = context.getSharedPreferences("e2-t2-evidence", 0).getString("video", null)
        assertNotNull("Run picker test first, then force-stop without clearing app data", id)
        val db = Room.databaseBuilder(context, FamilyDatabase::class.java, databaseName).build()
        try {
            val familyId = FamilyId("fixture-import-device-family")
            val parent = parentSession(familyId)
            val parentVideos = db.videoRepository(parentSession = parent) as ParentVideoAccess
            val saved = parentVideos.getForAuthorizedParent(profile, VideoId(id!!), parent.requireScope())!!
            val reference = FileReference(saved.sourceReference.value)
            val documents = SafImportDocuments(context)
            assertTrue(documents.accessible(reference))
            val probed = documents.probe(reference) {}
            assertEquals(saved.checksum, probed.checksum)
            assertEquals(saved.metadata.technical, probed.technical)
            assertEquals(VideoStatus.READY, saved.status)
            report("After force-stop: persisted URI readable, metadata/checksum identical, Room READY preserved")
            documents.release(reference)
            try { documents.probe(reference) {}; fail("Revoked grant must fail") }
            catch (failure: ImportException) { assertEquals(ImportErrors.permission, failure.failure) }
            report("Explicit persisted-read-grant release: PERMISSION_REVOKED (not a manual Settings revocation test)")
        } finally { db.close() }
    }

    @Test fun persistGeneratedSourceForRemoval() = runBlocking {
        val context = compose.activity.applicationContext
        val missingName = InstrumentationRegistry.getArguments().getString("missing_fixture")
        assertNotNull("Pass the newly generated, uniquely named fixture filename", missingName)
        val docs = SafImportDocuments(context)
        compose.setContent {
            val picker = rememberLauncherForActivityResult(DownloadsPicker()) { uri ->
                if (uri != null) {
                    val reference = FileReference(uri.toString())
                    compose.activity.lifecycleScope.launch {
                        val retained = docs.retain(reference)
                        context.getSharedPreferences("e2-t2-evidence", 0).edit()
                            .putString("missingUri", reference.value).putBoolean("missingGrant", retained).commit()
                    }
                }
            }
            Button(onClick = { picker.launch(arrayOf("video/*")) }, modifier = androidx.compose.ui.Modifier.testTag("select-removal-fixture")) {
                Text("选择本次新建的文件")
            }
        }
        choose("select-removal-fixture", missingName!!, scrollLaunchButton = false)
        compose.waitUntil(10_000) {
            context.getSharedPreferences("e2-t2-evidence", 0).getBoolean("missingGrant", false)
        }
        val reference = FileReference(context.getSharedPreferences("e2-t2-evidence", 0)
            .getString("missingUri", null)!!)
        assertTrue(docs.accessible(reference))
        report("Unique generated source selected and persistable read grant retained")
    }

    @Test fun removedOriginalFileReturnsSourceMissing() = runBlocking {
        val context = compose.activity.applicationContext
        val preferences = context.getSharedPreferences("e2-t2-evidence", 0)
        val reference = FileReference(preferences.getString("missingUri", null)
            ?: throw AssertionError("Run persistGeneratedSourceForRemoval first"))
        val docs = SafImportDocuments(context)
        assertFalse("The selected original must be removed while its read grant remains", docs.accessible(reference))
        try {
            docs.probe(reference) {}
            fail("The removed original must return a classified missing-file error.")
        } catch (failure: ImportException) {
            assertEquals(ImportErrors.missing, failure.failure)
            report("Removed newly generated source: SOURCE_FILE_MISSING")
        } finally { docs.release(reference) }
    }

    private class DownloadsPicker : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"))
    }
    private fun parentSession(familyId: FamilyId): ParentSession {
        val store = object : PinRecordStore {
            private var record: PinRecord? = null
            override suspend fun read() = record
            override suspend fun write(record: PinRecord) { this.record = record }
        }
        val pins = PinService(store, Clock { Instant.now() }, PinDerivation { _, _, _ -> ByteArray(32) { 7 } }, Dispatchers.Unconfined)
        val pin = CharArray(4) { '7' }
        kotlinx.coroutines.runBlocking { pins.setup(familyId, pin) }
        return ParentSession(familyId, pins, Clock { Instant.now() }).also { session ->
            kotlinx.coroutines.runBlocking { session.unlock(pin) }
            pin.fill('\u0000')
        }
    }
    private fun choose(tag: String, filename: String, scrollLaunchButton: Boolean = true) {
        if (scrollLaunchButton) click(tag) else compose.onNodeWithTag(tag).performClick()
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val node = root?.findAccessibilityNodeInfosByText(filename)?.firstOrNull { it.text?.toString() == filename }
            if (node != null) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    compose.waitUntil(15_000) { automation.rootInActiveWindow?.packageName?.toString() == "com.lazyeng.family" }
                    return
                }
            }
            if (root != null) {
                val pending = ArrayDeque<AccessibilityNodeInfo>()
                pending.add(root)
                var advanced = false
                while (pending.isNotEmpty() && !advanced) {
                    val item = pending.removeFirst()
                    if (item.isScrollable) advanced = item.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    for (index in 0 until item.childCount) item.getChild(index)?.let(pending::addLast)
                }
            }
            SystemClock.sleep(200)
        }
        fail("DocumentsUI did not expose generated fixture: $filename")
    }
    private fun waitPhase(importer: VideoImportCoordinator, phase: ImportPhase) = compose.waitUntil(30_000) { importer.state.value.phase == phase }
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun evidence(name: String) {
        File(compose.activity.filesDir, "import-evidence").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun report(line: String) {
        File(compose.activity.filesDir, "import-evidence").apply { mkdirs() }.resolve("results.txt").appendText(line + "\n")
    }
}
