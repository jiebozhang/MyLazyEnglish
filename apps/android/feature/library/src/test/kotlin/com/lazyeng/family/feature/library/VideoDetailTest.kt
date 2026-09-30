package com.lazyeng.family.feature.library

import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.security.*
import java.time.Instant
import kotlinx.coroutines.*
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
class VideoDetailTest {
    private val profile = ProfileId("fixture-child")
    private val family = FamilyId("fixture-family")
    private val id = VideoId("fixture-video")
    private val video = Video(id, VideoSourceType.LOCAL_FILE, VideoSourceReference("content://fixture/video"), "fixture-hash",
        VideoMetadata("Synthetic detail", null, 5000, listOf("nature"), "A1", 999, null, "child", VideoLevelSource.ESTIMATED),
        VideoStatus.DRAFT, ImportStatus.Draft, SubtitleStatus.NoSubtitle, TranslationStatus.Unknown("NONE"), false,
        VideoRights("self", "Synthetic rights note", profile, true))

    @Test fun onlyReadyPlayablePublishedAndAccessibleEnablesWatch() {
        for (status in VideoStatus.entries) for (published in listOf(false, true)) for (playable in listOf(false, true)) {
            val result = VideoDetailData.availability(video.copy(status = status, playable = playable), published, null)
            assertEquals(status == VideoStatus.READY && published && playable, result == DetailAvailability.READY)
        }
        val ready = video.copy(status = VideoStatus.READY, playable = true)
        assertEquals(DetailAvailability.MISSING, VideoDetailData.availability(ready, true, ImportErrors.missing))
        assertEquals(DetailAvailability.PERMISSION, VideoDetailData.availability(ready, true, ImportErrors.permission))
        assertEquals(DetailAvailability.UNAVAILABLE, VideoDetailData.availability(ready, true, ImportErrors.io))
    }
    @Test fun gradesAlwaysIdentifyTheirSourceAndNeverGuessUnknownOrigin() {
        assertEquals("估算等级 A1", VideoDetailData.levelLabel(video.metadata))
        assertEquals("人工等级 A1", VideoDetailData.levelLabel(video.metadata.copy(levelSource = VideoLevelSource.MANUAL)))
        assertNull(VideoDetailData.levelLabel(video.metadata.copy(levelSource = null)))
    }
    @Test fun repositoryProjectionUsesRealWordsVersionsAndProfileProgress() = runTest { database { db ->
        val data = data(db)
        val child = data.child()!!
        assertTrue(child.canWatch)
        assertEquals(2000L, child.positionMs)
        val parent = data.parent()!!
        assertEquals(3, parent.wordCount)
        assertEquals(1, parent.versions.size)
        assertTrue(parent.versions.single().current)
        assertEquals("Synthetic rights note", parent.usageNote)
        assertNull(data(db, scope = { false }).child())
        assertNull(data(db, scope = { false }).parent())
        assertTrue(data(db, scope = { false }).archive() is AppResult.Failure)
        assertTrue(data.archive() is AppResult.Success)
        assertFalse(data.child()!!.canWatch)
        assertEquals(2000L, db.watchProgressRepository().getProgress(profile, id)!!.positionMs)
        assertEquals(1, data.parent()!!.versions.size)
    } }
    @Test fun revokedAndMissingAreDistinctAndScopeChangeDuringReadDropsResult() = runTest { database { db ->
        assertEquals("PERMISSION_REVOKED", data(db, access = { throw ImportException(ImportErrors.permission) }).parent()!!.errorCode)
        assertEquals("SOURCE_FILE_MISSING", data(db, access = { throw ImportException(ImportErrors.missing) }).parent()!!.errorCode)
        var active = true
        assertNull(data(db, access = { active = false }, scope = { active }).child())
    } }
    @Test fun pinGateLoadingAndBackgroundEraseParentProjection() = runTest { database { db ->
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val clock = Clock { Instant.EPOCH }
            val records = object : PinRecordStore {
                var record: PinRecord? = null
                override suspend fun read() = record
                override suspend fun write(record: PinRecord) { this.record = record }
            }
            val pins = PinService(records, clock, PinDerivation { _, _, _ -> ByteArray(32) { 7 } }, dispatcher)
            val syntheticPin = CharArray(4) { ('0'.code + it).toChar() }
            pins.setup(family, syntheticPin)
            syntheticPin.fill('\u0000')
            val parent = ParentSession(family, pins, clock)
            val session = VideoDetailSession(data(db, videos = db.videoRepository(parentSession = parent)), parent)
            val vm = VideoDetailViewModel({ session }, true, clock)
            store.put("detail", vm)
            runCurrent()
            assertFalse(vm.state.value.authorized)
            assertNull(vm.state.value.parent)
            vm.onEvent(VideoDetailEvent.ConfirmArchive)
            assertEquals(VideoStatus.READY, db.videoRepository().getVideo(profile, id)!!.status)
            repeat(4) { vm.onEvent(VideoDetailEvent.Digit(it)) }
            vm.onEvent(VideoDetailEvent.Verify)
            assertTrue(vm.state.value.loading)
            // Database tasks use a real dispatcher; wait with a bounded yield, not advanceUntilIdle (ticker).
            withTimeout(10_000) { while (vm.state.value.loading) { runCurrent(); delay(1) } }
            assertTrue(vm.state.value.authorized)
            assertNotNull(vm.state.value.parent)
            vm.onBackground()
            assertNull(vm.state.value.parent)
            assertFalse(vm.state.value.authorized)
            assertEquals(0, vm.state.value.enteredCount)
        } finally { store.clear(); Dispatchers.resetMain() }
    } }
    private fun data(db: FamilyDatabase, access: suspend (FileReference) -> Unit = {}, scope: suspend () -> Boolean = { true },
        videos: VideoCatalog = db.videoRepository()) =
        VideoDetailData(profile, id, videos, db.subtitleRepository(), db.watchProgressRepository(),
            db.subtitleReadinessChecker(), access, scope)
    private suspend fun database(block: suspend (FamilyDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java).build()
        try {
            db.familyRepository().saveFamily(Family(family, emptyMap(), Instant.EPOCH))
            db.profileRepository().saveProfile(family, profile, Profile(profile, family, "Fixture", "star", ProfileRole.CHILD,
                "child", "Power Up 2", emptyMap(), Instant.EPOCH, null))
            val videos = db.videoRepository(MediaReadinessChecker { _, _, _ -> true })
            videos.saveVideo(profile, video)
            val track = SubtitleTrack(SubtitleTrackId("track"), id, "en", "SRT", null)
            val draft = DraftSubtitleVersion(SubtitleVersionId("version"), track.id, "hash", "srt-1", "UTF-8")
            db.subtitleRepository().saveTrack(profile, track)
            db.subtitleRepository().saveDraft(profile, draft, listOf(SubtitleLine(SubtitleLineId("line"), draft.id, 1, 100, 1000, "Three synthetic words.")))
            db.subtitleRepository().publish(profile, draft.id, Instant.EPOCH)
            videos.transition(profile, id, VideoStatus.PROCESSING)
            videos.transition(profile, id, VideoStatus.READY)
            db.watchProgressRepository().saveProgress(profile, WatchProgress(profile, id, 2000, .4f, Instant.EPOCH))
            block(db)
        } finally { db.close() }
    }
}
