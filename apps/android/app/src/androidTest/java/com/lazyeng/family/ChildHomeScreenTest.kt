package com.lazyeng.family

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.model.*
import com.lazyeng.family.feature.home.*
import com.lazyeng.family.feature.library.ChildVideoDetail
import com.lazyeng.family.feature.library.DetailAvailability
import com.lazyeng.family.feature.player.PlayerPhase
import com.lazyeng.family.feature.player.PlayerScreen
import com.lazyeng.family.feature.player.PlayerUiState
import com.lazyeng.family.navigation.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChildHomeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-09-26T14:00:00Z")
    private val clock = Clock { now }
    private val profileId = ProfileId("kid-leo")
    private val familyId = FamilyId("family-1")
    private val videoId = VideoId("clip-1")

    private val clip = ChildVideoDetail(
        title = "Peppa Pig Ep 1",
        durationMs = 60_000L,
        level = "估算等级 A1",
        topics = listOf("nature"),
        positionMs = 30_000L,
        completion = 0.5f,
        availability = DetailAvailability.READY,
    )

    private fun fakeProfile() = Profile(
        id = profileId,
        familyId = familyId,
        nickname = "Leo",
        avatarId = "star",
        role = ProfileRole.CHILD,
        ageMode = "child",
        englishLevel = "A1",
        uiPreferences = emptyMap(),
        createdAt = now,
        deletedAt = null,
    )

    private fun fakeVideo(id: VideoId, title: String) = Video(
        id = id,
        sourceType = VideoSourceType.LOCAL_FILE,
        sourceReference = VideoSourceReference("content://media/${id.value}"),
        checksum = "hash-${id.value}",
        metadata = VideoMetadata(
            title = title,
            coverAssetId = null,
            durationMs = 60_000L,
            topicTags = listOf("nature"),
            cefrLevel = "A1",
            subtitleWordCount = 45,
            accentTag = "US",
            ageFit = "child",
            levelSource = VideoLevelSource.ESTIMATED,
        ),
        status = VideoStatus.READY,
        importStatus = ImportStatus.Ready,
        subtitleStatus = SubtitleStatus.Available,
        translationStatus = TranslationStatus.Unknown("NONE"),
        playable = true,
        rights = VideoRights("self", null, profileId, true),
    )

    private class FakeHomeData(
        private val profile: Profile?,
        private val videos: List<Video>,
        private val progress: List<WatchProgress>,
        private var validScopeFlag: Boolean = true,
        private val clock: Clock,
    ) {
        fun setScopeValid(valid: Boolean) { validScopeFlag = valid }

        fun toHomeData(): HomeData {
            val profilesRepo = object : ProfileRepository {
                override suspend fun getProfile(familyId: FamilyId, profileId: ProfileId) = profile
                override suspend fun saveProfile(familyId: FamilyId, profileId: ProfileId, profile: Profile) {}
                override suspend fun deleteProfile(familyId: FamilyId, profileId: ProfileId, deletedAt: Instant) {}
            }
            val videosRepo = object : com.lazyeng.family.core.common.VideoCatalog {
                override suspend fun getVideo(profileId: ProfileId, videoId: VideoId) = videos.firstOrNull { it.id == videoId }
                override suspend fun listVideos(profileId: ProfileId) = videos
                override suspend fun saveVideo(profileId: ProfileId, video: Video) {}
                override suspend fun archiveVideo(profileId: ProfileId, videoId: VideoId) {}
                override suspend fun markReadyIfEligible(profileId: ProfileId, videoId: VideoId) = true
                override suspend fun recommendations(profileId: ProfileId) = videos
                override suspend fun writeVideo(profileId: ProfileId, video: Video) = com.lazyeng.family.core.common.AppResult.Success(Unit)
                override suspend fun transition(profileId: ProfileId, videoId: VideoId, next: VideoStatus, errorCode: String?, repairAdvice: String?) =
                    com.lazyeng.family.core.common.AppResult.Success(videos.first { it.id == videoId })
            }
            val progressRepo = object : WatchProgressRepository {
                override suspend fun getProgress(profileId: ProfileId, videoId: VideoId) = progress.firstOrNull { it.videoId == videoId }
                override suspend fun listProgress(profileId: ProfileId) = progress
                override suspend fun saveProgress(profileId: ProfileId, progress: WatchProgress) {}
            }

            return HomeData(
                familyId = FamilyId("family-1"),
                profileId = ProfileId("kid-leo"),
                profiles = profilesRepo,
                videos = videosRepo,
                progress = progressRepo,
                scopeValid = { validScopeFlag },
                clock = clock,
            )
        }
    }

    private fun capture(name: String) {
        val folder = File(compose.activity.filesDir, "eh-t2-evidence").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun existingProgressDisplaysCardAndNavigatesToPlayer() {
        val fakeData = FakeHomeData(
            profile = fakeProfile(),
            videos = listOf(fakeVideo(videoId, "Peppa Pig Ep 1")),
            progress = listOf(WatchProgress(profileId, videoId, 30_000L, 0.5f, now)),
            clock = clock,
        )
        val viewModel = HomeViewModel({ HomeSession(fakeData.toHomeData()) }, clock)

        compose.setContent {
            LazyEngTheme {
                ProfileNavigationScope("scope-leo") {
                    ChildShell(
                        tabContent = { tab, memory, go ->
                            if (tab == ChildTab.HOME) {
                                HomeRoute(
                                    model = viewModel,
                                    listState = memory.list,
                                    onEffect = { effect ->
                                        when (effect) {
                                            is HomeUiEffect.NavigateToPlayer -> go(ShellDestination.Player(effect.videoId.value, effect.positionMs))
                                            is HomeUiEffect.NavigateToVideoDetail -> go(ShellDestination.Video(effect.videoId.value))
                                            HomeUiEffect.NavigateToLibrary -> go(ShellDestination.Tab(ChildTab.LIBRARY))
                                            HomeUiEffect.NavigateToVocabulary -> go(ShellDestination.Tab(ChildTab.VOCABULARY))
                                            HomeUiEffect.NavigateToProfiles -> go(ShellDestination.Profiles)
                                            HomeUiEffect.NavigateToParent -> go(ShellDestination.Parent)
                                        }
                                    }
                                )
                            } else {
                                TabPlaceholder(tab, go)
                            }
                        },
                        destinationContent = { target, back, go ->
                            when (target) {
                                is ShellDestination.Player -> PlayerScreen(
                                    state = PlayerUiState(PlayerPhase.READY, clip.title, durationMs = clip.durationMs ?: 0L),
                                    player = null,
                                    positionMs = MutableStateFlow(target.positionMs),
                                    subtitles = MutableStateFlow(emptyList()),
                                    activeSubtitleIndex = MutableStateFlow(null),
                                    onEvent = {},
                                )
                                else -> ExistingDestination(compose.activity.application as LazyEngApplication, target, back, go)
                            }
                        }
                    )
                }
            }
        }

        // Verify Home content rendered
        compose.onNodeWithTag("home-content").assertIsDisplayed()
        compose.onNodeWithText("你好，Leo").assertIsDisplayed()
        compose.onNodeWithTag("home-level-badge").assertIsDisplayed()

        // Continue watching card verification
        compose.onNodeWithTag("continue-watching-card").assertIsDisplayed()
        compose.onNodeWithText("Peppa Pig Ep 1").assertIsDisplayed()
        compose.onNodeWithText("已看 50%").assertIsDisplayed()
        compose.onNodeWithTag("continue-watching-progress").assertIsDisplayed()

        capture("eh-t2-continue-watching")

        // Click continue play button and verify player navigation with exact position
        compose.onNodeWithTag("continue-play-button")
            .performScrollTo()
            .performClick()

        compose.onNodeWithText("Peppa Pig Ep 1").assertIsDisplayed()
        compose.onNodeWithText("0:30 / 1:00").assertIsDisplayed()
        compose.onNodeWithTag("child-bottom-bar").assertDoesNotExist()

        capture("eh-t2-player-navigated")
    }

    @Test
    fun newProfileWithoutHistoryShowsGuidanceAndNavigatesToLibrary() {
        val fakeData = FakeHomeData(
            profile = fakeProfile(),
            videos = listOf(fakeVideo(videoId, "Peppa Pig Ep 1")),
            progress = emptyList(), // No history
            clock = clock,
        )
        val viewModel = HomeViewModel({ HomeSession(fakeData.toHomeData()) }, clock)

        compose.setContent {
            LazyEngTheme {
                ProfileNavigationScope("scope-leo") {
                    ChildShell(
                        tabContent = { tab, memory, go ->
                            if (tab == ChildTab.HOME) {
                                HomeRoute(
                                    model = viewModel,
                                    listState = memory.list,
                                    onEffect = { effect ->
                                        when (effect) {
                                            HomeUiEffect.NavigateToLibrary -> go(ShellDestination.Tab(ChildTab.LIBRARY))
                                            else -> {}
                                        }
                                    }
                                )
                            } else {
                                TabPlaceholder(tab, go)
                            }
                        },
                        destinationContent = { target, back, go ->
                            ExistingDestination(compose.activity.application as LazyEngApplication, target, back, go)
                        }
                    )
                }
            }
        }

        compose.onNodeWithTag("home-content").assertIsDisplayed()
        compose.onNodeWithTag("continue-watching-card").assertDoesNotExist()
        compose.onNodeWithTag("first-video-guide-card").assertIsDisplayed()
        compose.onNodeWithText("开启第一部视频").assertIsDisplayed()

        capture("eh-t2-new-profile-guide")

        // Click guidance button to navigate to library
        compose.onNodeWithTag("start-first-video-button").performClick()
        compose.onNodeWithTag("tab-library").assertIsSelected()
    }

    @Test
    fun scrollPositionPreservedAcrossTabSwitch() {
        val manyVideos = (0..20).map { i ->
            fakeVideo(VideoId("video-$i"), "Adventure Clip $i")
        }
        val fakeData = FakeHomeData(
            profile = fakeProfile(),
            videos = manyVideos,
            progress = listOf(WatchProgress(profileId, VideoId("video-0"), 10_000L, 0.2f, now)),
            clock = clock,
        )
        val viewModel = HomeViewModel({ HomeSession(fakeData.toHomeData()) }, clock)
        var homeMemory: TabMemory? = null

        compose.setContent {
            LazyEngTheme {
                ProfileNavigationScope("scope-leo") {
                    ChildShell(
                        tabContent = { tab, memory, go ->
                            if (tab == ChildTab.HOME) {
                                homeMemory = memory
                                HomeRoute(model = viewModel, listState = memory.list, onEffect = {})
                            } else {
                                TabPlaceholder(tab, go)
                            }
                        },
                        destinationContent = { target, back, go ->
                            ExistingDestination(compose.activity.application as LazyEngApplication, target, back, go)
                        }
                    )
                }
            }
        }

        compose.onNodeWithTag("home-content").assertIsDisplayed()

        // Scroll down to an item deep in the list
        compose.onNodeWithTag("home-content").performScrollToNode(hasTestTag("recommendation-card-video-10"))
        compose.runOnIdle {
            assertTrue("Expected list to scroll past 0", (homeMemory?.list?.firstVisibleItemIndex ?: 0) > 0)
        }
        val scrolledIndex = homeMemory!!.list.firstVisibleItemIndex

        // Switch to Library tab
        compose.onNodeWithTag("tab-library").performClick()
        compose.onNodeWithText("视频库").assertIsDisplayed()

        // Switch back to Home tab
        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithTag("home-content").assertIsDisplayed()

        // Verify scroll position is preserved exactly
        compose.runOnIdle {
            assertEquals(scrolledIndex, homeMemory!!.list.firstVisibleItemIndex)
        }

        capture("eh-t2-scroll-preserved")
    }

    @Test
    fun databaseErrorShowsRetryAndRecovers() {
        val fakeData = FakeHomeData(
            profile = fakeProfile(),
            videos = listOf(fakeVideo(videoId, "Peppa Pig Ep 1")),
            progress = emptyList(),
            validScopeFlag = false, // starts with invalid scope
            clock = clock,
        )
        val viewModel = HomeViewModel({ HomeSession(fakeData.toHomeData()) }, clock)

        compose.setContent {
            LazyEngTheme {
                ProfileNavigationScope("scope-leo") {
                    ChildShell(
                        tabContent = { tab, memory, go ->
                            if (tab == ChildTab.HOME) {
                                HomeRoute(model = viewModel, listState = memory.list, onEffect = {})
                            } else {
                                TabPlaceholder(tab, go)
                            }
                        },
                        destinationContent = { target, back, go ->
                            ExistingDestination(compose.activity.application as LazyEngApplication, target, back, go)
                        }
                    )
                }
            }
        }

        // Verify Error state
        compose.onNodeWithTag("home-error").assertIsDisplayed()
        compose.onNodeWithText("无法加载首页").assertIsDisplayed()
        capture("eh-t2-error-state")

        // Now recover scope and click retry
        fakeData.setScopeValid(true)
        compose.onNodeWithText("重试").performClick()

        // Verify content displays
        compose.onNodeWithTag("home-content").assertIsDisplayed()
        compose.onNodeWithText("你好，Leo").assertIsDisplayed()
        capture("eh-t2-error-recovered")
    }
}
