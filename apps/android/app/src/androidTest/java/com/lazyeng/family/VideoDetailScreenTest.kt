package com.lazyeng.family

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.model.*
import com.lazyeng.family.feature.library.*
import com.lazyeng.family.feature.importmedia.rememberImportDocumentPicker
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoDetailScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val child = ChildVideoDetail("Synthetic nature clip", 5000, "估算等级 A1", listOf("自然"), 2000, .4f, DetailAvailability.READY)
    private val parent = ParentVideoDetail(child, "content://synthetic/source", "LOCAL_FILE", VideoStatus.READY,
        listOf(VersionDetail("fixture-version", "en", true, true, "2026-01-01T00:00:00Z", "UTF-8")),
        3, "self", "Synthetic rights", true, null, null, true)
    private fun show(content: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
            LazyEngTheme { content() }
        }
    }
    @Test fun childCanContinueWithoutSeeingAnyParentFields() {
        val events = mutableListOf<VideoDetailEvent>()
        show { ChildVideoDetailScreen(VideoDetailState(loading = false, child = child), events::add, {}, {}) }
        compose.onNodeWithText("估算等级 A1").assertExists()
        compose.onNodeWithTag("watch-video").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(VideoDetailEvent.Watch), events)
        compose.onNodeWithTag("detail-source").assertDoesNotExist()
        compose.onNodeWithText("fixture-version", substring = true).assertDoesNotExist()
        capture("child-ready-font-1.3")
    }
    @Test fun everyUnavailableStateHidesWatchAndTechnicalDetails() {
        val availability = mutableStateOf(DetailAvailability.PROCESSING)
        show { ChildVideoDetailScreen(VideoDetailState(loading = false, child = child.copy(availability = availability.value)), {}, {}, {}) }
        for (value in DetailAvailability.entries.filter { it != DetailAvailability.READY }) {
            compose.runOnIdle { availability.value = value }
            compose.onNodeWithTag("watch-video").assertDoesNotExist()
            compose.onNodeWithText(value.label()).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("detail-source").assertDoesNotExist()
        }
        capture("child-unavailable-font-1.3")
    }
    @Test fun parentGateHidesLoadedDataAndProvidesLoadingFeedback() {
        // Even accidental retained data must not render before authorization.
        show { ParentVideoDetailScreen(VideoDetailState(loading = true, parent = parent), {}, {}) }
        compose.onNodeWithText("验证中…").performScrollTo().assertExists()
        compose.onNodeWithTag("detail-source").assertDoesNotExist()
        compose.onNodeWithTag("archive-video").assertDoesNotExist()
        capture("parent-gate-font-1.3")
    }
    @Test fun parentShowsHistoryRightsAndRequiresArchiveConfirmation() {
        val events = mutableListOf<VideoDetailEvent>()
        show {
            var state by remember { mutableStateOf(VideoDetailState(loading = false, authorized = true, parent = parent)) }
            ParentVideoDetailScreen(state, { event ->
                events += event
                if (event == VideoDetailEvent.RequestArchive) state = state.copy(archiveConfirmation = true)
            }, {})
        }
        compose.onNodeWithTag("detail-source").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("fixture-version", substring = true).performScrollTo().assertIsDisplayed()
        capture("parent-history-font-1.3")
        compose.onNodeWithText("权利备注：Synthetic rights").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("repair-subtitle").performScrollTo().performClick()
        assertTrue(events.contains(VideoDetailEvent.Repair))
        compose.onNodeWithTag("archive-video").performScrollTo().performClick()
        assertFalse(events.contains(VideoDetailEvent.ConfirmArchive))
        compose.onNodeWithText("确认归档").assertIsDisplayed().performClick()
        assertTrue(events.contains(VideoDetailEvent.ConfirmArchive))
        capture("archive-confirmation-font-1.3")
    }
    @Test fun missingSourceOffersActionableParentAdviceWithoutSubtitleRepair() {
        show { ParentVideoDetailScreen(VideoDetailState(loading = false, authorized = true,
            parent = parent.copy(child = child.copy(availability = DetailAvailability.MISSING),
                errorCode = "SOURCE_FILE_MISSING", repairAdvice = "原文件已不存在，请重新选择文件。", canRepairSubtitle = false)), {}, {}) }
        compose.onNodeWithText("原文件已不存在，请重新选择文件。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("repair-subtitle").assertDoesNotExist()
        capture("parent-missing-font-1.3")
    }
    @Test fun repairDocumentPickerIsInterceptedWithoutSystemUi() {
        val file = File(compose.activity.cacheDir, "synthetic-detail-subtitle.srt")
        file.writeText("1\n00:00:00,100 --> 00:00:01,000\nSynthetic fixture only.\n")
        val resultUri = Uri.fromFile(file)
        var selected: FileReference? = null
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(resultUri)))
            show {
                val picker = rememberImportDocumentPicker { selected = it }
                Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("选择修复字幕") }
            }
            compose.onNodeWithText("选择修复字幕").performClick()
            compose.waitUntil(5000) { selected != null }
            intended(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            assertEquals(resultUri.toString(), selected!!.value)
        } finally { Intents.release(); file.delete() }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(compose.activity.filesDir, "video-detail-evidence").apply { mkdirs() }
        // Dialogs use a separate window; root capture coordinates can refer to the underlying Activity.
        val bitmap = if (name.startsWith("archive-confirmation")) {
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        } else compose.onRoot().captureToImage().asAndroidBitmap()
        directory.resolve("$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
