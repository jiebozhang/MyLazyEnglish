package com.lazyeng.family

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.model.*
import com.lazyeng.family.feature.player.PronunciationAccent
import com.lazyeng.family.feature.player.PronunciationPlayer
import com.lazyeng.family.feature.player.PronunciationState
import com.lazyeng.family.feature.vocabulary.*
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VocabularyScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val events = mutableListOf<VocabularyUiEvent>()
    private val pronunciation = FakePronunciation()
    private val row = VocabularyRow(
        item = VocabularyItem(ProfileId("test-child"), "run", VocabularyStatus.LEARNING, null, 1L),
        context = VocabularyContext(
            VocabularyItemKey(ProfileId("test-child"), "run"), SubtitleLineId("line-1"), null, 12_500L,
            id = 1L, surface = "running", phonetic = "/ˈrʌnɪŋ/", definition = "跑", videoId = VideoId("video-1"),
            contextSentence = "They are running home.", createdAt = Instant.parse("2026-09-27T00:00:00Z"),
        ),
        entry = DictionaryEntry("run", "verb", "/rʌn/", listOf("跑"), "A1", "test"),
    )

    @Test
    fun savedWordRendersContextAndPronunciationAndWatchAction() {
        compose.setContent {
            LazyEngTheme {
                VocabularyScreen(
                    VocabularyUiState(loading = false, rows = listOf(row)),
                    rememberLazyListState(),
                    events::add,
                    pronunciation,
                    PronunciationState.READY,
                )
            }
        }
        compose.onNodeWithTag("vocabulary-word-1").assertTextContains("running")
        compose.onNodeWithTag("vocabulary-context-1").assertExists()
        compose.onNodeWithTag("vocabulary-pronounce-1").performClick()
        compose.onNodeWithTag("vocabulary-watch-1").performClick()
        assertEquals("running", pronunciation.spoken)
        assertEquals(VocabularyUiEvent.WatchContext(1L), events.last())
    }

    @Test
    fun emptyProfileShowsGuidance() {
        compose.setContent {
            LazyEngTheme {
                VocabularyScreen(VocabularyUiState(loading = false), rememberLazyListState(), events::add)
            }
        }
        compose.onNodeWithTag("vocabulary-empty").assertExists()
        compose.onNodeWithText("这里暂时没有单词").assertExists()
    }

    private class FakePronunciation : PronunciationPlayer {
        private val mutableState = MutableStateFlow(PronunciationState.READY)
        override val state: StateFlow<PronunciationState> = mutableState
        var spoken: String? = null
        override fun speak(text: String, accent: PronunciationAccent) { spoken = text }
        override fun stop() = Unit
        override fun release() = Unit
    }
}
