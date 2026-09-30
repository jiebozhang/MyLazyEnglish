package com.lazyeng.family

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.model.*
import com.lazyeng.family.feature.library.LibraryFilters
import com.lazyeng.family.feature.library.LibraryScreen
import com.lazyeng.family.feature.library.LibraryUiState
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun searchRemainsVisibleWhileChangingTopicFilter() {
        val videos = listOf(video("kite", "Red Kite"), video("ocean", "Blue Ocean"))
        compose.setContent {
            LazyEngTheme {
                var state by remember { mutableStateOf(LibraryUiState(loading = false, videos = videos, isChild = false)) }
                LibraryScreen(state, { event ->
                    state = when (event) {
                        is com.lazyeng.family.feature.library.LibraryUiEvent.QueryChanged -> state.copy(filters = state.filters.copy(query = event.value))
                        is com.lazyeng.family.feature.library.LibraryUiEvent.TopicSelected -> state.copy(filters = state.filters.copy(topic = event.value))
                        else -> state
                    }
                }, androidx.compose.foundation.lazy.rememberLazyListState())
            }
        }
        compose.onNodeWithTag("library-search").performTextInput("red")
        compose.onNodeWithTag("filter-topic-nature").performScrollTo().performClick()
        compose.onNodeWithTag("library-search").assertIsDisplayed()
        compose.onNodeWithText("Red Kite").assertIsDisplayed()
        assertFalse(compose.onAllNodesWithText("Blue Ocean").fetchSemanticsNodes().isNotEmpty())
    }

    private fun video(id: String, title: String) = Video(
        VideoId(id), VideoSourceType.LOCAL_FILE, VideoSourceReference("content://fixture/$id"), "fixture-hash",
        VideoMetadata(title, null, 20_000, listOf("nature"), "A1", 12, null, "child"),
        VideoStatus.READY, ImportStatus.Ready, SubtitleStatus.Available, TranslationStatus.Unknown("NONE"), true,
        VideoRights("fixture", null, null, true),
    )
}
