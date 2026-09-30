package com.lazyeng.family

import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.model.DictionaryEntry
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.SubtitleLineId
import com.lazyeng.family.core.model.VocabularyContext
import com.lazyeng.family.core.model.VocabularyItem
import com.lazyeng.family.core.model.VocabularyItemKey
import com.lazyeng.family.core.model.VocabularyStatus
import com.lazyeng.family.core.model.VideoId
import com.lazyeng.family.feature.player.PlayerPhase
import com.lazyeng.family.feature.player.PlayerScreen
import com.lazyeng.family.feature.player.PlayerSubtitleCue
import com.lazyeng.family.feature.player.PlayerUiEvent
import com.lazyeng.family.feature.player.PlayerUiState
import com.lazyeng.family.feature.player.PlayerWordSelection
import com.lazyeng.family.feature.profiles.ProfilesPhase
import com.lazyeng.family.feature.profiles.ProfilesScreen
import com.lazyeng.family.feature.profiles.ProfilesUiState
import com.lazyeng.family.feature.vocabulary.Flashcard
import com.lazyeng.family.feature.vocabulary.FlashcardReviewEvent
import com.lazyeng.family.feature.vocabulary.FlashcardReviewScreen
import com.lazyeng.family.feature.vocabulary.FlashcardReviewUiState
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class PhysicalRotationAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<PhysicalRotationHostActivity>()

    @After
    fun restoreAutomaticRotation() {
        shell("settings put system accelerometer_rotation 1; settings put system user_rotation 0")
    }

    @Test
    fun playerRetainsPositionSubtitleSpeedAndLoopAcrossPhysicalRotation() {
        val cue = cue()
        val state = PlayerUiState(
            phase = PlayerPhase.READY,
            title = "Rotation fixture",
            durationMs = 60_000L,
            isPlaying = true,
            speed = 1.25f,
            sentenceLoop = cue,
        )
        val position = MutableStateFlow(12_000L)
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides compose.activity.currentConfiguration) {
                LazyEngTheme {
                    PlayerScreen(state, null, position, MutableStateFlow(listOf(cue)), MutableStateFlow(0), {})
                }
            }
        }
        rotateAndAssert(0, Configuration.ORIENTATION_PORTRAIT)
        compose.onNodeWithTag("player-time").assertTextContains("0:12", substring = true)
        compose.onNodeWithTag("subtitle-line-1").assertExists()
        screenshot("player-portrait-before")

        rotateAndAssert(1, Configuration.ORIENTATION_LANDSCAPE)
        compose.onNodeWithTag("player-layout-phone-landscape").assertExists()
        compose.onNodeWithTag("player-time").assertTextContains("0:12", substring = true)
        compose.onNodeWithTag("subtitle-line-1").assertExists()
        compose.onNodeWithTag("player-speed").assertTextContains("1.25", substring = true)
        compose.onNodeWithTag("player-sentence-loop").assertExists()
        screenshot("player-landscape-after")

        rotateAndAssert(0, Configuration.ORIENTATION_PORTRAIT)
        compose.onNodeWithTag("player-time").assertTextContains("0:12", substring = true)
        compose.onNodeWithTag("subtitle-line-1").assertExists()
        compose.onNodeWithTag("player-sentence-loop").assertExists()
        screenshot("player-portrait-restored")
    }

    @Test
    fun openDictionarySurvivesPhysicalRotationAndDismissKeepsPlaybackPosition() {
        val cue = cue()
        var state by mutableStateOf(
            PlayerUiState(
                phase = PlayerPhase.READY,
                title = "Rotation fixture",
                durationMs = 60_000L,
                selectedWord = PlayerWordSelection("running", "run", 7, 14, cue, 12_000L),
                lookupEntry = DictionaryEntry("run", "verb", "/rʌn/", listOf("跑"), "A1", "fixture"),
                lookupLemma = "run",
            ),
        )
        val position = MutableStateFlow(12_000L)
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides compose.activity.currentConfiguration) {
                LazyEngTheme {
                    PlayerScreen(
                        state, null, position, MutableStateFlow(listOf(cue)), MutableStateFlow(0),
                        onEvent = { event -> if (event == PlayerUiEvent.CloseLookup) state = state.copy(selectedWord = null) },
                    )
                }
            }
        }
        rotateAndAssert(0, Configuration.ORIENTATION_PORTRAIT)
        compose.onNodeWithTag("lookup-bottom-sheet").assertExists()
        compose.onNodeWithTag("lookup-word").assertTextContains("running")
        screenshot("lookup-portrait-before")

        rotateAndAssert(1, Configuration.ORIENTATION_LANDSCAPE)
        compose.onNodeWithTag("lookup-bottom-sheet").assertExists()
        compose.onNodeWithTag("lookup-lemma").assertTextContains("run")
        screenshot("lookup-landscape-after")

        rotateAndAssert(0, Configuration.ORIENTATION_PORTRAIT)
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("lookup-bottom-sheet").assertDoesNotExist()
        assertEquals(12_000L, position.value)
        compose.onNodeWithTag("player-time").assertTextContains("0:12", substring = true)
        screenshot("lookup-dismiss-position-retained")
    }

    @Test
    fun flippedFlashcardRemainsOnBackAcrossPhysicalRotation() {
        val profileId = ProfileId("rotation-profile")
        val itemKey = VocabularyItemKey(profileId, "run")
        val card = Flashcard(
            VocabularyItem(profileId, "run", VocabularyStatus.LEARNING, null),
            VocabularyContext(
                itemKey, SubtitleLineId("rotation-line"), null, 12_000L,
                surface = "running", phonetic = "/rʌnɪŋ/", definition = "跑", videoId = VideoId("rotation-video"),
                contextSentence = "They are running home.", createdAt = Instant.parse("2026-09-27T00:00:00Z"),
            ),
            DictionaryEntry("run", "verb", "/rʌn/", listOf("跑"), "A1", "fixture"),
        )
        var state by mutableStateOf(FlashcardReviewUiState(loading = false, cards = listOf(card)))
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides compose.activity.currentConfiguration) {
                LazyEngTheme {
                    FlashcardReviewScreen(state, onEvent = { event ->
                        if (event == FlashcardReviewEvent.Flip) state = state.copy(flipped = !state.flipped)
                    }, onBack = {})
                }
            }
        }
        rotateAndAssert(0, Configuration.ORIENTATION_PORTRAIT)
        compose.onNodeWithTag("flashcard-front").performClick()
        compose.onNodeWithTag("flashcard-back").assertExists()
        screenshot("flashcard-back-portrait-before")

        rotateAndAssert(1, Configuration.ORIENTATION_LANDSCAPE)
        compose.onNodeWithTag("flashcard-back").assertExists()
        compose.onNodeWithText("跑").assertExists()
        screenshot("flashcard-back-landscape-after")

        rotateAndAssert(0, Configuration.ORIENTATION_PORTRAIT)
        compose.onNodeWithTag("flashcard-back").assertExists()
        screenshot("flashcard-back-portrait-restored")
    }

    @Test
    fun parentPinEntryRemainsMaskedAndDoesNotGrantAccessAcrossPhysicalRotation() {
        val state = ProfilesUiState(phase = ProfilesPhase.PIN, enteredCount = 2)
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides compose.activity.currentConfiguration) {
                LazyEngTheme { ProfilesScreen(state, {}) }
            }
        }
        rotateAndAssert(0, Configuration.ORIENTATION_PORTRAIT)
        compose.onNodeWithText("家长验证").assertExists()
        compose.onNodeWithTag("pin-dots").assertContentDescriptionEquals("PIN 输入，已输入 2 个，共 4 个")
        assertFalse(compose.onAllNodesWithText("12").fetchSemanticsNodes().isNotEmpty())
        screenshot("parent-pin-portrait-before")

        rotateAndAssert(1, Configuration.ORIENTATION_LANDSCAPE)
        compose.onNodeWithText("家长验证").assertExists()
        compose.onNodeWithTag("pin-dots").assertContentDescriptionEquals("PIN 输入，已输入 2 个，共 4 个")
        compose.onNodeWithTag("parent-dashboard").assertDoesNotExist()
        screenshot("parent-pin-landscape-after")

        rotateAndAssert(0, Configuration.ORIENTATION_PORTRAIT)
        compose.onNodeWithText("家长验证").assertExists()
        compose.onNodeWithTag("pin-dots").assertContentDescriptionEquals("PIN 输入，已输入 2 个，共 4 个")
        compose.onNodeWithTag("parent-dashboard").assertDoesNotExist()
        screenshot("parent-pin-portrait-restored")
    }

    private fun rotateAndAssert(rotation: Int, expectedOrientation: Int) {
        shell("settings put system accelerometer_rotation 0; settings put system user_rotation $rotation")
        compose.waitUntil(10_000) { compose.activity.currentConfiguration.orientation == expectedOrientation }
        compose.waitForIdle()
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun screenshot(name: String) {
        val directory = File(requireNotNull(compose.activity.getExternalFilesDir(null)), "int-2/physical-rotation")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$name.png").outputStream().use { output ->
            requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun cue() = PlayerSubtitleCue(
        SubtitleLineId("physical-rotation-line"), 1, 10_000L, 15_000L, "She is running home.", "她正在跑回家。",
    )
}
