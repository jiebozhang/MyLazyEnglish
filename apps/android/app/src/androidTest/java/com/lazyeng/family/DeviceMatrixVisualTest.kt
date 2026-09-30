package com.lazyeng.family

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.designsystem.LazyEngTouchTarget
import com.lazyeng.family.core.model.DictionaryEntry
import com.lazyeng.family.core.model.SubtitleLineId
import com.lazyeng.family.feature.player.PlayerScreen
import com.lazyeng.family.feature.player.PlayerPhase
import com.lazyeng.family.feature.player.PlayerSubtitleCue
import com.lazyeng.family.feature.player.PlayerUiState
import com.lazyeng.family.feature.player.PlayerWordSelection
import com.lazyeng.family.feature.player.PronunciationPlayer
import com.lazyeng.family.feature.player.PronunciationState
import com.lazyeng.family.feature.player.PronunciationAccent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceMatrixVisualTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun orientationSwitchRetainsPlayerPositionAndChangesAdaptiveLayout() {
        var landscape by mutableStateOf(false)
        val position = MutableStateFlow(12_000L)
        compose.setContent {
            LazyEngTheme(darkTheme = true) {
                Box(Modifier.size(if (landscape) 820.dp else 360.dp, if (landscape) 360.dp else 820.dp)) {
                    PlayerScreen(
                        state = PlayerUiState(phase = PlayerPhase.READY, title = "Fixture", durationMs = 60_000L),
                        player = null,
                        positionMs = position,
                        subtitles = MutableStateFlow(listOf(cue())),
                        activeSubtitleIndex = MutableStateFlow(0),
                        onEvent = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("player-layout-portrait").assertExists()
        compose.runOnUiThread { landscape = true }
        compose.waitUntil(8_000) {
            runCatching { compose.onNodeWithTag("player-layout-phone-landscape").assertExists() }.isSuccess
        }
        compose.onNodeWithTag("player-time").assertTextContains("0:12", substring = true)
    }

    @Test
    fun playbackAndLookupActionsHaveLabelsAndMinimumTouchBounds() {
        val line = cue()
        val selection = PlayerWordSelection("running", "run", 7, 14, line, 1_500L)
        compose.setContent {
            LazyEngTheme(darkTheme = false) {
                PlayerScreen(
                    state = PlayerUiState(
                        phase = PlayerPhase.READY,
                        title = "Fixture",
                        durationMs = 60_000L,
                        selectedWord = selection,
                        lookupEntry = DictionaryEntry("run", "verb", "/rʌn/", listOf("跑"), "A1", "fixture"),
                        lookupLemma = "run",
                        lookupCanSave = true,
                    ),
                    player = null,
                    positionMs = MutableStateFlow(1_500L),
                    subtitles = MutableStateFlow(listOf(line)),
                    activeSubtitleIndex = MutableStateFlow(0),
                    onEvent = {},
                    pronunciationPlayer = ReadyPronunciation,
                )
            }
        }
        compose.onNodeWithContentDescription("播放英文发音").assertExists()
        assertTouchTarget("player-play-pause")
        assertTouchTarget("player-fullscreen")
        assertTouchTarget("player-sentence-loop")
        assertTouchTarget("lookup-pronounce")
        assertTouchTarget("lookup-save")
    }

    @Test
    fun dictionaryContentRemainsScrollableAtTwoTimesFontScaleAndCapturesEvidence() {
        val line = cue().copy(englishText = "A long sentence stays readable when the system text size is increased for accessibility.")
        val selection = PlayerWordSelection("sentence", "sentence", 7, 15, line, 1_500L)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides remember(density) { Density(density.density, 2f) }) {
                LazyEngTheme(darkTheme = true) {
                    PlayerScreen(
                        state = PlayerUiState(
                            phase = PlayerPhase.READY,
                            title = "Fixture",
                            durationMs = 60_000L,
                            selectedWord = selection,
                            lookupEntry = DictionaryEntry("sentence", "noun", "/ˈsentəns/", listOf("句子".repeat(12)), "A1", "fixture"),
                            lookupLemma = "sentence",
                        ),
                        player = null,
                        positionMs = MutableStateFlow(1_500L),
                        subtitles = MutableStateFlow(listOf(line)),
                        activeSubtitleIndex = MutableStateFlow(0),
                        onEvent = {},
                        pronunciationPlayer = ReadyPronunciation,
                    )
                }
            }
        }
        compose.onNodeWithTag("lookup-context").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lookup-sense-0").performScrollTo().assertIsDisplayed()
        saveScreenshot("lookup-bottom-sheet", "dark-dictionary-font-2x")
    }

    @Test
    fun primaryTextRolesMeetWcagAaInBothThemes() {
        val pairs = mutableListOf<Pair<Color, Color>>()
        compose.setContent {
            Column {
                LazyEngTheme(darkTheme = false) {
                    pairs += MaterialTheme.colorScheme.onBackground to MaterialTheme.colorScheme.background
                    androidx.compose.material3.Text("Light")
                }
                LazyEngTheme(darkTheme = true) {
                    pairs += MaterialTheme.colorScheme.onBackground to MaterialTheme.colorScheme.background
                    androidx.compose.material3.Text("Dark")
                }
            }
        }
        compose.waitForIdle()
        assertTrue("Expected captured light and dark color roles", pairs.size >= 2)
        pairs.takeLast(2).forEach { (foreground, background) ->
            val lighter = maxOf(foreground.luminance(), background.luminance())
            val darker = minOf(foreground.luminance(), background.luminance())
            assertTrue("Contrast ratio ${(lighter + 0.05) / (darker + 0.05)}", (lighter + 0.05) / (darker + 0.05) >= 4.5)
        }
    }

    private fun assertTouchTarget(tag: String) {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag width=${bounds.width}", with(compose.density) { bounds.width.toDp() >= LazyEngTouchTarget.minimum })
        assertTrue("$tag height=${bounds.height}", with(compose.density) { bounds.height.toDp() >= LazyEngTouchTarget.minimum })
    }

    private fun saveScreenshot(nodeTag: String, name: String) {
        val directory = File(compose.activity.getExternalFilesDir(null), "int-2")
        check(directory.mkdirs() || directory.isDirectory)
        val file = File(directory, "$name.png")
        file.outputStream().use { output ->
            compose.onNodeWithTag(nodeTag).captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun cue() = PlayerSubtitleCue(
        SubtitleLineId("matrix-line"), 1, 0L, 5_000L, "She is running home.", "她正在跑回家。",
    )

    private object ReadyPronunciation : PronunciationPlayer {
        override val state = MutableStateFlow(PronunciationState.READY)
        override fun speak(text: String, accent: PronunciationAccent) = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }
}
