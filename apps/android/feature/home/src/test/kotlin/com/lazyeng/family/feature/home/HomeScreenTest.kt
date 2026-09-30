package com.lazyeng.family.feature.home

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.ProfileRole
import com.lazyeng.family.core.model.VideoId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val profileHeader = HomeProfileHeader(
        profileId = ProfileId("kid-1"),
        nickname = "Milo",
        avatarId = "moon",
        englishLevel = "A1",
        role = ProfileRole.CHILD,
    )

    private val sampleContinueWatching = ContinueWatchingModel(
        videoId = VideoId("video-continue"),
        title = "The Magic School Bus",
        durationMs = 600_000L,
        positionMs = 360_000L,
        completion = 0.6f,
        coverAssetId = null,
        levelLabel = "估算等级 A1",
    )

    private val sampleRecommendation = HomeVideoCardModel(
        videoId = VideoId("rec-1"),
        title = "Peppa Pig at the Beach",
        durationMs = 300_000L,
        durationLabel = "05:00",
        subtitleWordCount = 120,
        levelLabel = "估算等级 A1",
        coverAssetId = null,
        canWatch = true,
    )

    @Test
    fun contentStateWithContinueWatchingRendersCorrectly() {
        val events = mutableListOf<HomeUiEvent>()
        val data = HomeDashboardData(
            profile = profileHeader,
            continueWatching = sampleContinueWatching,
            streakInfo = StreakInfo(3, true, "连续打卡 3 天 · 今日已完成"),
            vocabularySummary = VocabularySummary(5, "生词复习"),
            recommendations = listOf(sampleRecommendation),
        )

        compose.setContent {
            LazyEngTheme {
                HomeScreen(
                    state = HomeUiState.Content(data),
                    onEvent = { events.add(it) },
                    listState = rememberLazyListState(),
                )
            }
        }

        compose.onNodeWithTag("home-content").assertIsDisplayed()
        compose.onNodeWithText("你好，Milo").assertIsDisplayed()
        compose.onNodeWithTag("home-level-badge").assertIsDisplayed()
        compose.onNodeWithTag("home-streak-badge").assertIsDisplayed()

        // Continue watching card
        compose.onNodeWithTag("continue-watching-card").assertIsDisplayed()
        compose.onNodeWithText("The Magic School Bus").assertIsDisplayed()
        compose.onNodeWithText("已看 60%").assertIsDisplayed()
        compose.onNodeWithTag("continue-watching-progress").assertIsDisplayed()

        // Click play button
        compose.onNodeWithTag("continue-play-button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, events.size)
        assertTrue(events[0] is HomeUiEvent.ContinueWatchingClicked)
        val event = events[0] as HomeUiEvent.ContinueWatchingClicked
        assertEquals(VideoId("video-continue"), event.videoId)
        assertEquals(360_000L, event.positionMs)

        // Vocabulary card
        compose.onNodeWithTag("home-content")
            .performScrollToNode(hasTestTag("vocabulary-card"))
        compose.onNodeWithTag("vocabulary-card")
            .assertIsDisplayed()
        compose.onNodeWithText("有 5 个生词等你复习").assertIsDisplayed()
        compose.onNodeWithTag("vocabulary-card").performClick()
        assertTrue(events.contains(HomeUiEvent.VocabularyClicked))

        // Recommendations
        compose.onNodeWithTag("home-content")
            .performScrollToNode(hasTestTag("recommendation-card-rec-1"))
        compose.onNodeWithTag("recommendation-card-rec-1")
            .assertIsDisplayed()
        compose.onNodeWithText("Peppa Pig at the Beach").assertIsDisplayed()
        compose.onNodeWithTag("recommendation-card-rec-1").performClick()
        assertTrue(events.contains(HomeUiEvent.VideoCardClicked(VideoId("rec-1"))))
    }

    @Test
    fun contentStateWithoutContinueWatchingRendersFirstVideoGuidance() {
        val events = mutableListOf<HomeUiEvent>()
        val data = HomeDashboardData(
            profile = profileHeader,
            continueWatching = null,
            streakInfo = StreakInfo(0, false, "开启学习打卡第一天"),
            vocabularySummary = VocabularySummary(0, "生词复习"),
            recommendations = listOf(sampleRecommendation),
        )

        compose.setContent {
            LazyEngTheme {
                HomeScreen(
                    state = HomeUiState.Content(data),
                    onEvent = { events.add(it) },
                    listState = rememberLazyListState(),
                )
            }
        }

        compose.onNodeWithTag("home-content").assertIsDisplayed()
        compose.onNodeWithTag("continue-watching-card").assertDoesNotExist()

        // First video guide card
        compose.onNodeWithTag("first-video-guide-card").assertIsDisplayed()
        compose.onNodeWithText("开启第一部视频").assertIsDisplayed()
        compose.onNodeWithTag("start-first-video-button").performClick()
        assertTrue(events.contains(HomeUiEvent.FirstVideoGuidanceClicked))
    }

    @Test
    fun loadingStateRendersSkeleton() {
        compose.setContent {
            LazyEngTheme {
                HomeScreen(
                    state = HomeUiState.Loading,
                    onEvent = {},
                    listState = rememberLazyListState(),
                )
            }
        }

        compose.onNodeWithTag("home-loading").assertIsDisplayed()
    }

    @Test
    fun errorStateRendersErrorMessageAndRetry() {
        val events = mutableListOf<HomeUiEvent>()
        compose.setContent {
            LazyEngTheme {
                HomeScreen(
                    state = HomeUiState.Error("测试连接失败"),
                    onEvent = { events.add(it) },
                    listState = rememberLazyListState(),
                )
            }
        }

        compose.onNodeWithTag("home-error").assertIsDisplayed()
        compose.onNodeWithText("无法加载首页").assertIsDisplayed()
        compose.onNodeWithText("测试连接失败").assertIsDisplayed()

        compose.onNodeWithText("重试").performClick()
        assertEquals(listOf(HomeUiEvent.Refresh), events)
    }

    @Test
    fun emptyStateRendersEmptyMessage() {
        val events = mutableListOf<HomeUiEvent>()
        compose.setContent {
            LazyEngTheme {
                HomeScreen(
                    state = HomeUiState.Empty(profileHeader),
                    onEvent = { events.add(it) },
                    listState = rememberLazyListState(),
                )
            }
        }

        compose.onNodeWithTag("home-empty").assertIsDisplayed()
        compose.onNodeWithText("还没有视频").assertIsDisplayed()
        compose.onNodeWithText("家长控制台").performClick()
        assertTrue(events.contains(HomeUiEvent.ParentConsoleClicked))
    }
}
