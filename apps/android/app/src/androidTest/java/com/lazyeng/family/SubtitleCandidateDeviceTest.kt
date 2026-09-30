package com.lazyeng.family

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.media.SafImportDocuments
import com.lazyeng.family.core.media.SafMediaReadiness
import com.lazyeng.family.core.model.*
import com.lazyeng.family.feature.importmedia.*
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real SAF provider acceptance, separate from fake-driven Compose screen tests.
 * Push the generated MP4/SRT fixtures into Download/LazyEng-E2T3-Fixtures before running.
 * Uses a disposable database and never reads or edits the installed family's records.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleCandidateDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun treeCandidatePreviewPublishAndRollback() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("safCandidateAcceptance") == "true")
        val context = compose.activity.applicationContext
        val database = Room.inMemoryDatabaseBuilder(context, FamilyDatabase::class.java).build()
        val documents = SafImportDocuments(context)
        val profileId = ProfileId("candidate-device-profile")
        val familyId = FamilyId("candidate-device-family")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var sequence = 0
        val importer = VideoImportCoordinator(profileId, documents,
            database.videoRepository(SafMediaReadiness(documents)), database.videoAssetRepository(),
            database.importJobRepository(), database.subtitleRepository(), database.importTransaction(),
            {}, Clock { Instant.EPOCH }, { "candidate-device-${++sequence}" })
        val treeSelection = mutableStateOf<Uri?>(null)
        val selected = mutableStateOf<SubtitleCandidate?>(null)
        var video: FileReference? = null
        var directory: FileReference? = null
        var acquiredTree = false
        val treeId = "primary:Download/LazyEng-E2T3-Fixtures"
        try {
            runBlocking {
                database.familyRepository().saveFamily(Family(familyId, emptyMap(), Instant.EPOCH))
                database.profileRepository().saveProfile(familyId, profileId, Profile(profileId, familyId,
                    "Fixture", "star", ProfileRole.CHILD, "child", "Power Up 2", emptyMap(), Instant.EPOCH, null))
            }
            compose.setContent {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeSelection.value = it }
                val state by importer.state.collectAsState()
                var rights by remember { mutableStateOf(false) }
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    LazyEngTheme {
                        if (treeSelection.value == null) {
                            Button(onClick = { picker.launch(DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents", treeId)) }, Modifier.testTag("fixture-tree")) {
                                Text("Select generated fixture directory")
                            }
                        } else ImportScreen(ImportScreenState(initialized = true, authorized = true,
                            videoSelected = true, directorySelected = true, subtitleSelected = selected.value != null,
                            selectedSubtitleName = selected.value?.displayName, rights = rights,
                            busy = state.phase in setOf(ImportPhase.READING, ImportPhase.SAVING), import = state), { event ->
                            when (event) {
                                is ImportUiEvent.SelectCandidate -> selected.value = event.candidate
                                is ImportUiEvent.Rights -> rights = event.value
                                ImportUiEvent.Inspect -> scope.launch { importer.prepare(video, selected.value?.reference, directory = directory) }
                                is ImportUiEvent.Confirm -> scope.launch { importer.confirm(event.choice, rights) }
                                ImportUiEvent.RollbackSubtitle -> scope.launch { importer.rollbackLastSubtitlePublication() }
                                else -> Unit
                            }
                        }, {})
                    }
                }
            }
            compose.onNodeWithTag("fixture-tree").performClick()
            clickSystemButton(setOf("使用此文件夹", "USE THIS FOLDER", "Use this folder"), ":id/action_menu_select")
            clickSystemButton(setOf("允许", "ALLOW", "Allow"), "android:id/button1")
            compose.waitUntil(10_000) { treeSelection.value != null }
            val tree = treeSelection.value!!
            assertEquals("Only the generated fixture directory may be granted", treeId, DocumentsContract.getTreeDocumentId(tree))
            directory = FileReference(tree.toString())
            video = FileReference(DocumentsContract.buildDocumentUriUsingTree(tree,
                "$treeId/lazyeng-import-fixture.mp4").toString())
            runBlocking {
                acquiredTree = documents.retain(directory!!)
                importer.prepare(video, directory = directory)
                assertEquals(ImportPhase.PREVIEW, importer.state.value.phase)
                assertNull(importer.state.value.preview!!.subtitle)
                assertEquals(listOf("lazyeng-import-fixture.srt"), importer.state.value.preview!!.candidates.map { it.displayName })
                assertTrue(database.videoRepository().listVideos(profileId).isEmpty())
            }
            click("subtitle-candidate-lazyeng-import-fixture.srt")
            evidence("real-tree-candidate")
            runBlocking { assertTrue(database.videoRepository().listVideos(profileId).isEmpty()) }
            click("inspect-selected-subtitle")
            compose.waitUntil(30_000) { importer.state.value.preview?.subtitle != null || importer.state.value.phase == ImportPhase.ERROR }
            assertNotNull(importer.state.value.preview?.subtitle)
            compose.onNodeWithText("100–2000 ms\nAn original test pattern.").performScrollTo().assertIsDisplayed()
            evidence("real-tree-parsed-preview")
            click("import-rights")
            click("confirm-import")
            compose.waitUntil(30_000) { importer.state.value.phase in setOf(ImportPhase.COMPLETE, ImportPhase.ERROR) }
            assertEquals(VideoStatus.READY, importer.state.value.video?.status)
            val savedVideo = importer.state.value.video!!
            val catalog = database.subtitleRepository()
            val publishedId = runBlocking { catalog.listTracks(profileId, savedVideo.id).single().currentVersionId!! }
            val oldLines = runBlocking { catalog.listLines(profileId, publishedId, 0, 20) }
            evidence("real-tree-published")
            click("rollback-subtitle")
            compose.waitUntil(30_000) { !importer.state.value.rollbackAvailable }
            assertEquals(VideoStatus.NEEDS_SUBTITLE, importer.state.value.video?.status)
            compose.onNodeWithTag("rollback-subtitle").assertDoesNotExist()
            runBlocking {
                assertEquals(oldLines, catalog.listLines(profileId, publishedId, 0, 20))
                assertNotNull(catalog.getPublishedVersion(profileId, publishedId))
                importer.rollbackLastSubtitlePublication()
                assertEquals(VideoStatus.NEEDS_SUBTITLE, importer.state.value.video?.status)
            }
            evidence("real-tree-rolled-back")
            File(context.filesDir, "subtitle-preview-evidence/real-tree-result.txt").writeText(
                "Real external-storage tree grant; one candidate; zero rows before confirmation; " +
                    "two parsed cues; READY after publication; NEEDS_SUBTITLE after rollback; immutable history preserved; repeated rollback is no-op.\n")
        } finally {
            runBlocking {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                importer.cancel()
                if (acquiredTree) directory?.let { documents.release(it) }
            }
            database.close()
        }
    }

    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun evidence(name: String) {
        val directory = File(compose.activity.filesDir, "subtitle-preview-evidence").apply { mkdirs() }
        directory.resolve("$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun clickSystemButton(labels: Set<String>, resourceSuffix: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val nodes = ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(nodes::add)
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if (node.isClickable && node.isEnabled &&
                    (node.text?.toString() in labels || node.viewIdResourceName?.endsWith(resourceSuffix) == true) &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
            }
            SystemClock.sleep(100)
        }
        fail("Generated-directory system picker did not expose its bounded confirmation action")
    }
}
