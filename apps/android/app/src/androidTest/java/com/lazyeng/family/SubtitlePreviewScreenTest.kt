package com.lazyeng.family

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.common.subtitle.*
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.model.*
import com.lazyeng.family.feature.importmedia.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitlePreviewScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val profileId = ProfileId("preview-fixture-profile")
    private val videoId = VideoId("preview-fixture-video")
    private val candidate = SubtitleCandidate(FileReference("content://fixture/subtitle.srt"), "lesson.srt")

    private fun preview(failure: ImportFailure? = null) = ImportPreview(
        ProbedVideo(VideoTechnicalMetadata("lesson.mp4", 320, 240, "video/mp4"), 5_000, 1_024, "fixture-hash", "video/avc"),
        null, null, emptyList(), 1_048_576, listOf(candidate), candidate.displayName, failure,
    )

    private fun show(state: ImportScreenState, events: MutableList<ImportUiEvent>) {
        compose.setContent {
            var current by remember { mutableStateOf(state) }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                LazyEngTheme { ImportScreen(current, { event ->
                    events.add(event)
                    if (event is ImportUiEvent.Rights) current = current.copy(rights = event.value)
                }, {}) }
            }
        }
    }

    private fun evidence(name: String) {
        val directory = File(compose.activity.filesDir, "subtitle-preview-evidence").apply { mkdirs() }
        directory.resolve("$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun candidateRequiresExplicitSelectionAndParentPublishConfirmation() {
        val events = mutableListOf<ImportUiEvent>()
        show(ImportScreenState(initialized = true, authorized = true, videoSelected = true,
            import = ImportUiState(phase = ImportPhase.PREVIEW, preview = preview())), events)

        compose.onNodeWithText("候选字幕：lesson.srt", substring = true).performScrollTo().performClick()
        assertTrue(events.last() == ImportUiEvent.SelectCandidate(candidate))
        evidence("candidate-font-1.3")
        compose.onNodeWithTag("confirm-import").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("import-rights").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-import").performScrollTo().assertIsEnabled().performClick()
        assertTrue(events.last() == ImportUiEvent.Confirm(DuplicateChoice.ImportNew))
        evidence("confirmation-font-1.3")
    }

    @Test fun parseFailureIsSpecificAndCannotBePublished() {
        val events = mutableListOf<ImportUiEvent>()
        val failure = ImportFailure(
            AppError(AppErrorCode("SUBTITLE_ENCODING_UNCERTAIN"), ErrorHandling.USER_ACTION, UserMessageKey("subtitle.encoding")),
            "无法可靠识别编码。请选择实际编码后重新解析。",
        )
        show(ImportScreenState(initialized = true, authorized = true, videoSelected = true,
            subtitleSelected = true, import = ImportUiState(phase = ImportPhase.PREVIEW, preview = preview(failure))), events)

        compose.onNodeWithText(failure.message).performScrollTo().assertIsDisplayed()
        evidence("encoding-error-font-1.3")
        listOf("UTF-8", "GBK", "UTF-16LE", "UTF-16BE").forEach { encoding ->
            compose.onNodeWithText(encoding).performScrollTo().assertIsDisplayed().performClick()
            assertTrue(events.last() is ImportUiEvent.Encoding)
        }
        compose.onNodeWithTag("confirm-import").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("reparse-subtitle").performScrollTo().assertIsDisplayed()
    }

    @Test fun publishedSubtitleOffersSingleRollbackAction() {
        val events = mutableListOf<ImportUiEvent>()
        val video = Video(videoId, VideoSourceType.LOCAL_FILE, VideoSourceReference("content://fixture/video"),
            "fixture-hash", VideoMetadata("lesson.mp4", null, 5_000, emptyList(), null, null, null, null),
            VideoStatus.READY, ImportStatus.Ready, SubtitleStatus.Available, TranslationStatus.Unknown("NOT_REQUESTED"), true,
            VideoRights("FAMILY", null, profileId, true))
        show(ImportScreenState(initialized = true, authorized = true,
            import = ImportUiState(phase = ImportPhase.COMPLETE, video = video, rollbackAvailable = true)), events)

        compose.onNodeWithTag("rollback-subtitle").performScrollTo().performClick()
        assertTrue(events.last() == ImportUiEvent.RollbackSubtitle)
        evidence("rollback-font-1.3")
    }

    @Test fun manualFileAndDirectorySelectionOnlyDispatchEvents() {
        val events = mutableListOf<ImportUiEvent>()
        show(ImportScreenState(initialized = true, authorized = true, videoSelected = true), events)
        compose.onNodeWithTag("select-subtitle").performScrollTo().performClick()
        assertTrue(events.single() == ImportUiEvent.Choose(true))
        compose.onNodeWithTag("select-subtitle-directory").performScrollTo().performClick()
        assertTrue(events.last() == ImportUiEvent.ChooseDirectory)
        evidence("selection-font-1.3")
    }

    @Test fun previewShowsOnlyFirstTenCuesAndManualReplacementDispatchesAnEvent() {
        val events = mutableListOf<ImportUiEvent>()
        val parsed = ParsedSubtitle((1..12).map { sequence ->
            ParsedCue(sequence, sequence * 1_000L, sequence * 1_000L + 800, "Synthetic cue $sequence.")
        }, SubtitleEncoding.UTF8, false, "fixture-hash", "fixture-parser")
        val preview = preview().let { it.copy(media = it.media.copy(durationMs = 20_000), subtitle = parsed) }
        show(ImportScreenState(initialized = true, authorized = true, videoSelected = true,
            subtitleSelected = true, import = ImportUiState(phase = ImportPhase.PREVIEW, preview = preview)), events)
        compose.onNodeWithTag("replace-subtitle-selection").performScrollTo().performClick()
        assertTrue(events.single() == ImportUiEvent.Choose(true))
        compose.onNodeWithText("1000–1800 ms\nSynthetic cue 1.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("10000–10800 ms\nSynthetic cue 10.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic cue 11.", substring = true).assertDoesNotExist()
        evidence("first-ten-cues-font-1.3")
    }
}
