package com.lazyeng.family

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.model.*
import com.lazyeng.family.feature.player.PronunciationAccent
import com.lazyeng.family.feature.player.PronunciationPlayer
import com.lazyeng.family.feature.player.PronunciationState
import com.lazyeng.family.feature.vocabulary.*
import java.time.Instant
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlashcardReviewScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val utterances = mutableListOf<String>()
    private val events = mutableListOf<FlashcardReviewEvent>()
    private val pronunciation = FakePronunciation(utterances)
    private val card = Flashcard(
        VocabularyItem(ProfileId("fixture-child"), "run", VocabularyStatus.LEARNING, null),
        VocabularyContext(
            VocabularyItemKey(ProfileId("fixture-child"), "run"), SubtitleLineId("fixture-line"), null, 1500,
            surface = "running", phonetic = "/rʌnɪŋ/", definition = "跑", videoId = VideoId("fixture-video"),
            contextSentence = "They are running home.", createdAt = Instant.parse("2026-09-27T00:00:00Z"),
        ),
        DictionaryEntry("run", "verb", "/rʌn/", listOf("跑"), null, "test"),
    )

    @Test fun flashcardFlipsSpeaksAndShowsChildFriendlyRatings() {
        var state by mutableStateOf(FlashcardReviewUiState(loading = false, cards = listOf(card)))
        compose.setContent {
            LazyEngTheme {
                FlashcardReviewScreen(state, pronunciation, PronunciationState.READY, { event ->
                    events += event
                    if (event == FlashcardReviewEvent.Flip) state = state.copy(flipped = true)
                }, onBack = {})
            }
        }
        compose.onNodeWithText("running").assertExists()
        compose.onNodeWithTag("review-pronounce").performClick()
        assertEquals(listOf("running"), utterances)
        compose.onNodeWithTag("review-flip").performClick()
        compose.waitUntil(timeoutMillis = 2_000) { events.lastOrNull() == FlashcardReviewEvent.Flip }
        assertEquals(FlashcardReviewEvent.Flip, events.lastOrNull())
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard-back").assertExists()
        compose.onNodeWithTag("review-rating-REMEMBERED").assertExists()
        compose.onNodeWithText("跑").assertExists()
        assertEquals(FlashcardReviewEvent.Flip, events.last())
    }

    @Test fun emptyAndCompletionStatesHaveReturnActions() {
        compose.setContent {
            LazyEngTheme {
                FlashcardReviewScreen(FlashcardReviewUiState(loading = false), onEvent = events::add, onBack = {})
            }
        }
        compose.onNodeWithText("太棒了，今日没有待复习单词！").assertExists()
        compose.onNodeWithTag("review-empty-back").assertExists()
    }

    @Test fun completionSummarizesRatingsAndOffersReturn() {
        compose.setContent {
            LazyEngTheme {
                FlashcardReviewScreen(
                    FlashcardReviewUiState(loading = false, ratings = mapOf(
                        ReviewRating.FORGOT to 0,
                        ReviewRating.UNCERTAIN to 1,
                        ReviewRating.REMEMBERED to 2,
                    )),
                    onEvent = events::add,
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("恭喜完成复习").assertExists()
        compose.onNodeWithText("本次完成 3 个词").assertExists()
        compose.onNodeWithTag("review-completion-back").assertExists()
    }

    @Test fun longFlashcardContentRemainsScrollableAtTwoTimesFontScale() {
        val longSentence = "They are running home after their very long day. ".repeat(8)
        val largeCard = card.copy(context = card.context?.copy(contextSentence = longSentence))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides remember(density) { Density(density.density, 2f) }) {
                LazyEngTheme(darkTheme = true) {
                    FlashcardReviewScreen(
                        FlashcardReviewUiState(loading = false, cards = listOf(largeCard), flipped = true),
                        onEvent = events::add,
                        onBack = {},
                    )
                }
            }
        }
        compose.onNodeWithText(longSentence).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("原形 · run").performScrollTo().assertIsDisplayed()
        val evidence = File(compose.activity.getExternalFilesDir(null), "int-2")
        check(evidence.mkdirs() || evidence.isDirectory)
        File(evidence, "dark-flashcard-font-2x.png").outputStream().use { output ->
            compose.onNodeWithTag("flashcard-review").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private class FakePronunciation(private val utterances: MutableList<String>) : PronunciationPlayer {
        private val mutableState = MutableStateFlow(PronunciationState.READY)
        override val state: StateFlow<PronunciationState> = mutableState
        override fun speak(text: String, accent: PronunciationAccent) { utterances += text }
        override fun stop() = Unit
        override fun release() = Unit
    }
}
