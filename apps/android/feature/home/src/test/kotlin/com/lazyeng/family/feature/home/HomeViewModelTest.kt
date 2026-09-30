package com.lazyeng.family.feature.home

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.testing.TestDispatchers
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val familyId = FamilyId("test-family")
    private val profileId = ProfileId("child-profile-1")
    private val videoId1 = VideoId("video-1")
    private val videoId2 = VideoId("video-2")
    private val now = Instant.parse("2026-09-26T12:00:00Z")
    private val clock = Clock { now }

    private fun sampleVideo(
        id: VideoId,
        title: String,
        cefrLevel: String = "A1",
    ) = Video(
        id = id,
        sourceType = VideoSourceType.LOCAL_FILE,
        sourceReference = VideoSourceReference("content://media/$id"),
        checksum = "hash-$id",
        metadata = VideoMetadata(
            title = title,
            coverAssetId = null,
            durationMs = 60_000L,
            topicTags = listOf("nature"),
            cefrLevel = cefrLevel,
            subtitleWordCount = 50,
            accentTag = "US",
            ageFit = "child",
            levelSource = VideoLevelSource.ESTIMATED,
        ),
        status = VideoStatus.DRAFT,
        importStatus = ImportStatus.Draft,
        subtitleStatus = SubtitleStatus.NoSubtitle,
        translationStatus = TranslationStatus.Unknown("NONE"),
        playable = false,
        rights = VideoRights("self", null, profileId, true),
    )

    private suspend fun setupDatabase(
        withVideos: Boolean = true,
        withProgress: Boolean = true,
        block: suspend (FamilyDatabase) -> Unit,
    ) {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            FamilyDatabase::class.java,
        ).build()
        try {
            db.familyRepository().saveFamily(Family(familyId, emptyMap(), now))
            db.profileRepository().saveProfile(
                familyId, profileId,
                Profile(
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
            )

            if (withVideos) {
                val videos = db.videoRepository(MediaReadinessChecker { _, _, _ -> true })
                val v1 = sampleVideo(videoId1, "Peppa Pig Ep 1", "A1")
                val v2 = sampleVideo(videoId2, "Bluey Advanced", "B1")
                videos.saveVideo(profileId, v1)
                videos.saveVideo(profileId, v2)

                // Add subtitles and mark ready
                for (v in listOf(v1, v2)) {
                    val track = SubtitleTrack(SubtitleTrackId("track-${v.id.value}"), v.id, "en", "SRT", null)
                    val draft = DraftSubtitleVersion(SubtitleVersionId("ver-${v.id.value}"), track.id, "hash", "srt-1", "UTF-8")
                    db.subtitleRepository().saveTrack(profileId, track)
                    db.subtitleRepository().saveDraft(profileId, draft, listOf(SubtitleLine(SubtitleLineId("line-${v.id.value}"), draft.id, 1, 0, 1000, "Hello world")))
                    db.subtitleRepository().publish(profileId, draft.id, now)
                    videos.transition(profileId, v.id, VideoStatus.PROCESSING)
                    videos.transition(profileId, v.id, VideoStatus.READY)
                }

                if (withProgress) {
                    db.watchProgressRepository().saveProgress(
                        profileId,
                        WatchProgress(profileId, videoId1, 30_000L, 0.5f, now),
                    )
                }
            }
            block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun contentStateWithWatchProgressAndStreak() = runTest {
        setupDatabase(withVideos = true, withProgress = true) { db ->
            val dispatcher = TestDispatchers(testScheduler).dispatcher
            Dispatchers.setMain(dispatcher)
            val store = ViewModelStore()
            try {
                val session = HomeSession(
                    HomeData(
                        familyId = familyId,
                        profileId = profileId,
                        profiles = db.profileRepository(),
                        videos = db.videoRepository(MediaReadinessChecker { _, _, _ -> true }),
                        progress = db.watchProgressRepository(),
                        scopeValid = { true },
                        clock = clock,
                    )
                )
                val vm = HomeViewModel({ session }, clock)
                store.put("home", vm)

                withTimeout(10_000) {
                    while (vm.state.value is HomeUiState.Loading) {
                        runCurrent()
                        delay(1)
                    }
                }

                val state = vm.state.value
                assertTrue("Expected Content state but was $state", state is HomeUiState.Content)
                val content = (state as HomeUiState.Content).dashboard

                assertEquals("Leo", content.profile.nickname)
                assertEquals("A1", content.profile.englishLevel)
                assertNotNull(content.continueWatching)
                assertEquals(videoId1, content.continueWatching?.videoId)
                assertEquals(30_000L, content.continueWatching?.positionMs)
                assertEquals(50, content.continueWatching?.progressPercent)
                assertEquals(1, content.streakInfo.streakDays)
                assertTrue(content.streakInfo.hasWatchedToday)
                assertTrue(content.streakInfo.message.contains("连续打卡 1 天"))
                assertEquals(2, content.recommendations.size)
                // A1 should be prioritized
                assertEquals(videoId1, content.recommendations[0].videoId)
                assertEquals(videoId2, content.recommendations[1].videoId)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
    }

    @Test
    fun newProfileWithoutWatchProgressShowsGuidance() = runTest {
        setupDatabase(withVideos = true, withProgress = false) { db ->
            val dispatcher = TestDispatchers(testScheduler).dispatcher
            Dispatchers.setMain(dispatcher)
            val store = ViewModelStore()
            try {
                val session = HomeSession(
                    HomeData(
                        familyId = familyId,
                        profileId = profileId,
                        profiles = db.profileRepository(),
                        videos = db.videoRepository(MediaReadinessChecker { _, _, _ -> true }),
                        progress = db.watchProgressRepository(),
                        scopeValid = { true },
                        clock = clock,
                    )
                )
                val vm = HomeViewModel({ session }, clock)
                store.put("home", vm)

                withTimeout(10_000) {
                    while (vm.state.value is HomeUiState.Loading) {
                        runCurrent()
                        delay(1)
                    }
                }

                val state = vm.state.value
                assertTrue(state is HomeUiState.Content)
                val content = (state as HomeUiState.Content).dashboard

                assertNull("Continue watching should be null for new user", content.continueWatching)
                assertEquals(0, content.streakInfo.streakDays)
                assertFalse(content.streakInfo.hasWatchedToday)
                assertEquals("开启学习打卡第一天", content.streakInfo.message)
                assertTrue(content.recommendations.isNotEmpty())
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
    }

    @Test
    fun emptyVideoLibraryShowsEmptyState() = runTest {
        setupDatabase(withVideos = false, withProgress = false) { db ->
            val dispatcher = TestDispatchers(testScheduler).dispatcher
            Dispatchers.setMain(dispatcher)
            val store = ViewModelStore()
            try {
                val session = HomeSession(
                    HomeData(
                        familyId = familyId,
                        profileId = profileId,
                        profiles = db.profileRepository(),
                        videos = db.videoRepository(),
                        progress = db.watchProgressRepository(),
                        scopeValid = { true },
                        clock = clock,
                    )
                )
                val vm = HomeViewModel({ session }, clock)
                store.put("home", vm)

                withTimeout(10_000) {
                    while (vm.state.value is HomeUiState.Loading) {
                        runCurrent()
                        delay(1)
                    }
                }

                val state = vm.state.value
                assertTrue("Expected Empty state but was $state", state is HomeUiState.Empty)
                assertEquals("Leo", (state as HomeUiState.Empty).profile.nickname)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
    }

    @Test
    fun scopeFailureShowsErrorAndRetryRecovers() = runTest {
        setupDatabase(withVideos = true, withProgress = true) { db ->
            val dispatcher = TestDispatchers(testScheduler).dispatcher
            Dispatchers.setMain(dispatcher)
            val store = ViewModelStore()
            try {
                var scopeValidFlag = false
                val session = HomeSession(
                    HomeData(
                        familyId = familyId,
                        profileId = profileId,
                        profiles = db.profileRepository(),
                        videos = db.videoRepository(MediaReadinessChecker { _, _, _ -> true }),
                        progress = db.watchProgressRepository(),
                        scopeValid = { scopeValidFlag },
                        clock = clock,
                    )
                )
                val vm = HomeViewModel({ session }, clock)
                store.put("home", vm)

                withTimeout(10_000) {
                    while (vm.state.value is HomeUiState.Loading) {
                        runCurrent()
                        delay(1)
                    }
                }

                assertTrue(vm.state.value is HomeUiState.Error)

                // Now recover scope
                scopeValidFlag = true
                vm.onEvent(HomeUiEvent.Refresh)

                withTimeout(10_000) {
                    while (vm.state.value is HomeUiState.Loading) {
                        runCurrent()
                        delay(1)
                    }
                }

                assertTrue(vm.state.value is HomeUiState.Content)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
    }

    @Test
    fun uiEventsEmitCorrespondingEffects() = runTest {
        setupDatabase(withVideos = true, withProgress = true) { db ->
            val dispatcher = TestDispatchers(testScheduler).dispatcher
            Dispatchers.setMain(dispatcher)
            val store = ViewModelStore()
            try {
                val session = HomeSession(
                    HomeData(
                        familyId = familyId,
                        profileId = profileId,
                        profiles = db.profileRepository(),
                        videos = db.videoRepository(MediaReadinessChecker { _, _, _ -> true }),
                        progress = db.watchProgressRepository(),
                        scopeValid = { true },
                        clock = clock,
                    )
                )
                val vm = HomeViewModel({ session }, clock)
                store.put("home", vm)

                // Test ContinueWatchingClicked
                launch { vm.onEvent(HomeUiEvent.ContinueWatchingClicked(videoId1, 15000L)) }
                val effect1 = vm.effects.first()
                assertEquals(HomeUiEffect.NavigateToPlayer(videoId1, 15000L), effect1)

                // Test VideoCardClicked
                launch { vm.onEvent(HomeUiEvent.VideoCardClicked(videoId2)) }
                val effect2 = vm.effects.first()
                assertEquals(HomeUiEffect.NavigateToVideoDetail(videoId2), effect2)

                // Test VocabularyClicked
                launch { vm.onEvent(HomeUiEvent.VocabularyClicked) }
                val effect3 = vm.effects.first()
                assertEquals(HomeUiEffect.NavigateToVocabulary, effect3)

                // Test FirstVideoGuidanceClicked
                launch { vm.onEvent(HomeUiEvent.FirstVideoGuidanceClicked) }
                val effect4 = vm.effects.first()
                assertEquals(HomeUiEffect.NavigateToLibrary, effect4)

                // Test SwitchProfileClicked
                launch { vm.onEvent(HomeUiEvent.SwitchProfileClicked) }
                val effect5 = vm.effects.first()
                assertEquals(HomeUiEffect.NavigateToProfiles, effect5)

                // Test ParentConsoleClicked
                launch { vm.onEvent(HomeUiEvent.ParentConsoleClicked) }
                val effect6 = vm.effects.first()
                assertEquals(HomeUiEffect.NavigateToParent, effect6)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
    }

    @Test
    fun invalidationFlowTriggersRefresh() = runTest {
        setupDatabase(withVideos = true, withProgress = true) { db ->
            val dispatcher = TestDispatchers(testScheduler).dispatcher
            Dispatchers.setMain(dispatcher)
            val store = ViewModelStore()
            try {
                val invalidations = MutableSharedFlow<Unit>()
                val session = HomeSession(
                    HomeData(
                        familyId = familyId,
                        profileId = profileId,
                        profiles = db.profileRepository(),
                        videos = db.videoRepository(MediaReadinessChecker { _, _, _ -> true }),
                        progress = db.watchProgressRepository(),
                        scopeValid = { true },
                        clock = clock,
                    )
                )
                val vm = HomeViewModel({ session }, clock, invalidations)
                store.put("home", vm)

                withTimeout(10_000) {
                    while (vm.state.value is HomeUiState.Loading) {
                        runCurrent()
                        delay(1)
                    }
                }
                assertTrue(vm.state.value is HomeUiState.Content)

                // Emit invalidation
                invalidations.emit(Unit)
                runCurrent()

                withTimeout(10_000) {
                    while (vm.state.value is HomeUiState.Loading) {
                        runCurrent()
                        delay(1)
                    }
                }
                assertTrue(vm.state.value is HomeUiState.Content)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
    }
}
