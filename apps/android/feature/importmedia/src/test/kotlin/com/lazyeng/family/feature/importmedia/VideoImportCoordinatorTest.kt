package com.lazyeng.family.feature.importmedia

import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.security.*
import com.lazyeng.family.core.testing.TestDispatchers
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
class VideoImportCoordinatorTest {
    private val profile = ProfileId("fixture-importer")
    private val source = FileReference("content://fixture.example/video")
    private val subtitle = FileReference("content://fixture.example/subtitle")
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private lateinit var authorizedCatalog: VideoCatalog
    private lateinit var parentVideos: ParentVideoAccess
    private lateinit var parentSession: ParentSession

    @Test fun previewAndCancellationNeverWriteRowsAndReleaseOnlyNewGrants() = runTest { scenario(testScheduler) { db, docs, create ->
        docs.grants += source
        val importer = create()
        importer.prepare(source, subtitle)
        assertEquals(ImportPhase.PREVIEW, importer.state.value.phase)
        assertTrue(db.videoRepository().listVideos(profile).isEmpty())
        importer.cancel()
        assertEquals(setOf(source), docs.grants)
        assertEquals(ImportPhase.SELECT, importer.state.value.phase)
    } }

    @Test fun selectedSubtitlePublishesAndActualMetadataRoundTripsWithReadyJob() = runTest { scenario(testScheduler) { db, docs, create ->
        val importer = create()
        importer.prepare(source, subtitle)
        importer.confirm(DuplicateChoice.ImportNew, true)
        assertEquals(ImportPhase.COMPLETE, importer.state.value.phase)
        val video = importer.state.value.video!!
        assertEquals(VideoStatus.READY, video.status)
        assertEquals(docs.media.technical, parentVideos.getForAuthorizedParent(profile, video.id, parentSession.requireScope())!!.metadata.technical)
        assertEquals(ImportStatus.Ready, db.importJobRepository().getImportJob(profile, ImportJobId("fixture-2"))!!.status)
        val track = db.subtitleRepository().listTracks(profile, video.id).single()
        assertNotNull(db.subtitleRepository().getPublishedVersion(profile, track.currentVersionId!!))
        assertEquals(setOf(source), docs.grants)
    } }

    @Test fun publicationCanBeRolledBackOnceWithoutMutatingPublishedHistory() = runTest { scenario(testScheduler) { db, _, create ->
        val importer = create()
        importer.prepare(source, subtitle)
        importer.confirm(DuplicateChoice.ImportNew, true)
        val imported = importer.state.value.video!!
        val catalog = db.subtitleRepository()
        val track = catalog.listTracks(profile, imported.id).single()
        val immutableId = track.currentVersionId!!
        val immutableLines = catalog.listLines(profile, immutableId, 0, 20)
        assertTrue(importer.state.value.rollbackAvailable)
        importer.rollbackLastSubtitlePublication()
        assertFalse(importer.state.value.rollbackAvailable)
        assertEquals(VideoStatus.NEEDS_SUBTITLE, parentVideos.getForAuthorizedParent(profile, imported.id, parentSession.requireScope())!!.status)
        assertEquals(ImportStatus.NeedsSubtitle, db.importJobRepository().getImportJob(profile, ImportJobId("fixture-2"))!!.status)
        assertEquals(null, catalog.getTrack(profile, track.id)!!.currentVersionId)
        assertEquals(immutableLines, catalog.listLines(profile, immutableId, 0, 20))
        importer.rollbackLastSubtitlePublication()
        assertEquals(VideoStatus.NEEDS_SUBTITLE, parentVideos.getForAuthorizedParent(profile, imported.id, parentSession.requireScope())!!.status)
    } }

    @Test fun replacingSubtitleCreatesNewVersionAndRollbackPublishesHistoricalContentAsANewVersion() = runTest { scenario(testScheduler) { db, docs, create ->
        val first = create()
        first.prepare(source, subtitle)
        first.confirm(DuplicateChoice.ImportNew, true)
        val video = first.state.value.video!!
        val catalog = db.subtitleRepository()
        val track = catalog.listTracks(profile, video.id).single()
        val firstVersion = track.currentVersionId!!
        val firstLines = catalog.listLines(profile, firstVersion, 0, 20)

        docs.text = "1\n00:00:00,300 --> 00:00:02,200\nA replacement subtitle.\n".toByteArray()
        val replacement = create()
        replacement.prepare(source, subtitle)
        replacement.confirm(DuplicateChoice.Reuse(video.id), true)
        val replacementVersion = catalog.getTrack(profile, track.id)!!.currentVersionId!!
        assertNotEquals(firstVersion, replacementVersion)
        assertEquals("A synthetic example.", catalog.listLines(profile, firstVersion, 0, 20).first().text)

        replacement.rollbackLastSubtitlePublication()
        val restoredVersion = catalog.getTrack(profile, track.id)!!.currentVersionId!!
        assertNotEquals(firstVersion, restoredVersion)
        assertNotEquals(replacementVersion, restoredVersion)
        assertEquals(firstLines.map { it.text }, catalog.listLines(profile, restoredVersion, 0, 20).map { it.text })
        assertEquals(firstLines, catalog.listLines(profile, firstVersion, 0, 20))
    } }

    @Test fun overallOffsetIsValidatedAndStoredOnlyAfterPreviewAndConfirmation() = runTest { scenario(testScheduler) { db, _, create ->
        val importer = create()
        importer.prepare(source, subtitle, offsetMs = 500)
        assertEquals(600L, importer.state.value.preview!!.subtitle!!.cues.first().startMs)
        importer.confirm(DuplicateChoice.ImportNew, true)
        val track = db.subtitleRepository().listTracks(profile, importer.state.value.video!!.id).single()
        val versionId = track.currentVersionId!!
        assertEquals(600L, db.subtitleRepository().listLines(profile, versionId, 0, 1).single().startMs)
    } }

    @Test fun noSubtitleCannotBecomeReadyAndDuplicateReuseDoesNotAddRows() = runTest { scenario(testScheduler) { db, _, create ->
        val first = create()
        first.prepare(source)
        first.confirm(DuplicateChoice.ImportNew, true)
        val saved = first.state.value.video!!
        assertEquals(VideoStatus.NEEDS_SUBTITLE, saved.status)
        val next = create()
        next.prepare(source, subtitle)
        assertEquals(listOf(saved.id), next.state.value.preview!!.duplicates.map { it.id })
        next.confirm(DuplicateChoice.Reuse(saved.id), true)
        assertEquals(saved.id, next.state.value.video!!.id)
        assertEquals(VideoStatus.READY, next.state.value.video!!.status)
        assertEquals(ImportStatus.Ready, db.importJobRepository().getImportJob(profile, ImportJobId("fixture-2"))!!.status)
        assertEquals(1, parentVideos.listForAuthorizedParent(profile, parentSession.requireScope()).size)
        next.prepare(source)
        next.confirm(DuplicateChoice.ImportNew, true)
        assertEquals(2, parentVideos.listForAuthorizedParent(profile, parentSession.requireScope()).size)
    } }

    @Test fun permissionMissingCorruptionAndReadErrorsStayClassifiedWithoutRows() = runTest { scenario(testScheduler) { db, docs, create ->
        for (error in listOf(ImportErrors.permission, ImportErrors.missing, ImportErrors.invalid, ImportErrors.io)) {
            docs.failure = error
            val importer = create()
            importer.prepare(source)
            assertEquals(error, importer.state.value.failure)
            assertTrue(docs.grants.isEmpty())
            assertTrue(db.videoRepository().listVideos(profile).isEmpty())
        }
    } }

    @Test fun lowStorageAndDurationPolicyRejectBeforeWriting() = runTest { scenario(testScheduler) { db, docs, create ->
        docs.space = 0
        val importer = create()
        importer.prepare(source)
        assertEquals(ImportErrors.storage, importer.state.value.failure)
        docs.space = Long.MAX_VALUE
        docs.media = docs.media.copy(durationMs = 0)
        importer.prepare(source)
        assertEquals(ImportErrors.duration, importer.state.value.failure)
        assertTrue(db.videoRepository().listVideos(profile).isEmpty())
    } }

    @Test fun configuredDurationCapIsEnforcedWithoutInventingAGlobalLimit() = runTest { scenario(testScheduler) { db, docs, _ ->
        val importer = coordinator(db, docs, policy = ImportPolicy(maxDurationMs = 4000))
        importer.prepare(source)
        assertEquals(ImportErrors.duration, importer.state.value.failure)
        assertTrue(db.videoRepository().listVideos(profile).isEmpty())
    } }

    @Test fun invalidSubtitleIsNotSilentlyDiscarded() = runTest { scenario(testScheduler) { db, docs, create ->
        docs.text = "not a subtitle file".toByteArray()
        val importer = create()
        importer.prepare(source, subtitle)
        assertEquals(ImportPhase.PREVIEW, importer.state.value.phase)
        assertNotNull(importer.state.value.preview?.subtitleFailure)
        assertTrue(db.videoRepository().listVideos(profile).isEmpty())
        importer.cancel()
        assertTrue(docs.grants.isEmpty())
    } }

    @Test fun matchingSubtitleIsOnlyACandidateUntilExplicitlySelectedAndPreviewed() = runTest { scenario(testScheduler) { db, docs, create ->
        val importer = create()
        importer.prepare(source, directory = FileReference("content://fixture.example/tree"))
        val preview = importer.state.value.preview!!
        assertEquals(listOf("fixture.srt"), preview.candidates.map { it.displayName })
        assertNull(preview.subtitle)
        assertNull(preview.subtitleFailure)
        assertTrue(db.videoRepository().listVideos(profile).isEmpty())
        importer.cancel()
        assertTrue(docs.grants.isEmpty())
    } }

    @Test fun sourceChangedAfterConfirmationCreatesOnlyAFailedReceipt() = runTest { scenario(testScheduler) { db, docs, create ->
        val importer = create()
        importer.prepare(source)
        docs.media = docs.media.copy(checksum = "fixture-changed")
        importer.confirm(DuplicateChoice.ImportNew, true)
        assertEquals(ImportErrors.changed, importer.state.value.failure)
        assertTrue(importer.state.value.failureRecorded)
        val record = parentVideos.listForAuthorizedParent(profile, parentSession.requireScope()).single()
        assertEquals(VideoStatus.FAILED, record.status)
        assertEquals("SOURCE_CHANGED", record.errorCode)
        assertTrue(docs.grants.isEmpty())
    } }

    @Test fun authorizationLossPreventsCommitAndFailedReceipt() = runTest { scenario(testScheduler) { db, docs, create ->
        val importer = create()
        importer.prepare(source)
        docs.authorized = false
        importer.confirm(DuplicateChoice.ImportNew, true)
        assertEquals(ImportErrors.scope, importer.state.value.failure)
        assertFalse(importer.state.value.failureRecorded)
        assertTrue(db.videoRepository().listVideos(profile).isEmpty())
        assertTrue(docs.grants.isEmpty())
    } }

    @Test fun publicationFailureRollsBackTrackLinesAssetsAndProcessingState() = runTest { scenario(testScheduler) { db, docs, _ ->
        val catalog = db.subtitleRepository()
        val rejecting = object : SubtitleCatalog by catalog {
            override suspend fun publish(profileId: ProfileId, versionId: SubtitleVersionId, publishedAt: Instant): SubtitlePublishResult =
                throw ImportException(ImportErrors.subtitle)
        }
        val importer = coordinator(db, docs, rejecting)
        importer.prepare(source, subtitle)
        importer.confirm(DuplicateChoice.ImportNew, true)
        assertEquals(ImportPhase.ERROR, importer.state.value.phase)
        val only = parentVideos.listForAuthorizedParent(profile, parentSession.requireScope()).single()
        assertEquals(VideoStatus.FAILED, only.status)
        db.openHelper.readableDatabase.query("SELECT count(*) FROM subtitle_tracks").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.openHelper.readableDatabase.query("SELECT count(*) FROM video_assets").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    } }

    @Test fun cancelDuringProbeIsNotConvertedToCorruption() = runTest { scenario(testScheduler) { db, docs, create ->
        val started = CompletableDeferred<Unit>()
        docs.beforeProbe = { started.complete(Unit); awaitCancellation() }
        val importer = create()
        val task = launch { importer.prepare(source) }
        started.await()
        assertEquals(ImportPhase.READING, importer.state.value.phase)
        task.cancelAndJoin()
        assertEquals(ImportPhase.SELECT, importer.state.value.phase)
        assertTrue(docs.grants.isEmpty())
        assertTrue(db.videoRepository().listVideos(profile).isEmpty())
    } }

    @Test fun consentIsMandatoryAndForeignHouseholdDoesNotSeeImportedVideo() = runTest { scenario(testScheduler) { db, _, create ->
        val importer = create()
        importer.prepare(source)
        importer.confirm(DuplicateChoice.ImportNew, false)
        assertTrue(db.videoRepository().listVideos(profile).isEmpty())
        importer.prepare(source)
        importer.confirm(DuplicateChoice.ImportNew, true)
        val foreign = ProfileId("fixture-other-household")
        val family = Family(FamilyId("fixture-other-family"), emptyMap(), now)
        db.familyRepository().saveFamily(family)
        db.profileRepository().saveProfile(family.id, foreign,
            Profile(foreign, family.id, "Other fixture", null, ProfileRole.CHILD, "child", "Power Up 2", emptyMap(), now, null))
        assertNull(db.videoRepository().getVideo(foreign, importer.state.value.video!!.id))
        assertTrue(db.videoRepository().listVideos(foreign).isEmpty())
    } }

    @Test fun viewModelLoadingExitsOnFailureAndResetKeepsCollectorAlive() = runTest { scenario(testScheduler) { _, docs, create ->
        val dispatchers = TestDispatchers(testScheduler)
        Dispatchers.setMain(dispatchers.dispatcher)
        val holder = ViewModelStore()
        try {
            var stored: PinRecord? = null
            val store = object : PinRecordStore {
                override suspend fun read() = stored
                override suspend fun write(record: PinRecord) { stored = record }
            }
            val clock = Clock { now }
            val family = FamilyId("fixture-import-family")
            val pins = PinService(store, clock, PinDerivation { _, _, _ -> ByteArray(32) { 1 } }, dispatchers.dispatcher)
            val input = CharArray(4) { ('0'.code + it).toChar() }
            pins.setup(family, input)
            val parent = ParentSession(family, pins, clock)
            val importer = create()
            val vm = ImportViewModel({ ImportSession(parent, importer) }, clock)
            holder.put("import", vm)
            runCurrent()
            input.forEach { vm.onEvent(ImportUiEvent.Digit(it.digitToInt())) }
            vm.onEvent(ImportUiEvent.Verify)
            assertTrue(vm.state.value.busy)
            runCurrent()
            assertTrue(vm.state.value.authorized)
            assertFalse(vm.state.value.busy)
            val gate = CompletableDeferred<Unit>()
            docs.beforeProbe = { gate.await() }
            docs.failure = ImportErrors.invalid
            vm.onEvent(ImportUiEvent.Selected(false, source))
            vm.onEvent(ImportUiEvent.Inspect)
            runCurrent()
            assertTrue(vm.state.value.busy)
            assertEquals(ImportPhase.READING, vm.state.value.import.phase)
            gate.complete(Unit); runCurrent()
            assertFalse(vm.state.value.busy)
            assertEquals(ImportPhase.ERROR, vm.state.value.import.phase)
            vm.onEvent(ImportUiEvent.Reset); runCurrent()
            assertEquals(ImportPhase.SELECT, vm.state.value.import.phase)
            vm.onEvent(ImportUiEvent.Selected(false, source))
            vm.onEvent(ImportUiEvent.Inspect); runCurrent()
            assertEquals(ImportPhase.ERROR, vm.state.value.import.phase)
            vm.onEvent(ImportUiEvent.Reset); runCurrent()
            vm.onEvent(ImportUiEvent.Choose(false))
            parent.revoke()
            vm.onEvent(ImportUiEvent.Selected(false, source)); runCurrent()
            assertFalse(vm.state.value.authorized)
            assertFalse(vm.state.value.videoSelected)
            vm.onBackground(); runCurrent()
            assertFalse(vm.state.value.authorized)
            assertFalse(vm.state.value.videoSelected)
            docs.failure = null
            docs.beforeProbe = {}
            importer.prepare(source)
            assertTrue(source in docs.grants)
            holder.clear(); runCurrent()
            assertTrue(docs.grants.isEmpty())
            input.fill('\u0000')
        } finally { holder.clear(); runCurrent(); Dispatchers.resetMain() }
    } }

    private var next = 0
    private fun coordinator(db: FamilyDatabase, docs: Documents, subtitles: SubtitleCatalog = db.subtitleRepository(), policy: ImportPolicy = ImportPolicy()) =
        VideoImportCoordinator(profile, docs, authorizedCatalog,
            db.videoAssetRepository(), db.importJobRepository(), subtitles, db.importTransaction(),
            { if (!docs.authorized) throw ImportException(ImportErrors.scope); parentSession.checkScope(parentSession.requireScope()) },
            Clock { now }, { "fixture-${++next}" }, policy,
            listVideosInAuthorizedScope = { parentVideos.listForAuthorizedParent(profile, parentSession.requireScope()) },
            getVideoInAuthorizedScope = { id -> parentVideos.getForAuthorizedParent(profile, id, parentSession.requireScope()) })

    private suspend fun scenario(scheduler: TestCoroutineScheduler,
        block: suspend (FamilyDatabase, Documents, () -> VideoImportCoordinator) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java).build()
        try {
            val family = Family(FamilyId("fixture-import-family"), emptyMap(), now)
            db.familyRepository().saveFamily(family)
            db.profileRepository().saveProfile(family.id, profile,
                Profile(profile, family.id, "Fixture", null, ProfileRole.CHILD, "child", "Power Up 2", emptyMap(), now, null))
            val store = object : PinRecordStore {
                var record: PinRecord? = null
                override suspend fun read() = record
                override suspend fun write(record: PinRecord) { this.record = record }
            }
            val dispatchers = TestDispatchers(scheduler)
            val pins = PinService(store, Clock { now }, PinDerivation { _, _, _ -> ByteArray(32) { 5 } }, dispatchers.dispatcher)
            val fakePin = CharArray(4) { '5' }
            pins.setup(family.id, fakePin)
            parentSession = ParentSession(family.id, pins, Clock { now })
            parentSession.unlock(fakePin)
            fakePin.fill('\u0000')
            authorizedCatalog = db.videoRepository(MediaReadinessChecker { _, _, _ -> true }, parentSession = parentSession)
            parentVideos = authorizedCatalog as ParentVideoAccess
            val docs = Documents()
            block(db, docs) { coordinator(db, docs) }
        } finally { db.close() }
    }

    private class Documents : ImportDocuments {
        val grants = mutableSetOf<FileReference>()
        var authorized = true
        var failure: ImportFailure? = null
        var space = Long.MAX_VALUE
        var media = ProbedVideo(VideoTechnicalMetadata("fixture.mp4", 320, 240, "video/mp4"), 5000, 1024, "fixture-hash", "video/avc")
        var beforeProbe: suspend () -> Unit = {}
        var text = "1\n00:00:00,100 --> 00:00:02,000\nA synthetic example.\n\n2\n00:00:02,100 --> 00:00:04,000\nAnother example.\n".toByteArray()
        override suspend fun retain(reference: FileReference) = grants.add(reference)
        override suspend fun release(reference: FileReference) { grants.remove(reference) }
        override suspend fun probe(reference: FileReference, progress: (Long) -> Unit): ProbedVideo {
            beforeProbe(); failure?.let { throw ImportException(it) }; progress(media.bytes); return media
        }
        override suspend fun readSubtitle(reference: FileReference) = SelectedSubtitle(text, "fixture.srt")
        override suspend fun findSubtitleCandidates(directory: FileReference, videoDisplayName: String) =
            if (videoDisplayName.substringBeforeLast('.') == "fixture")
                listOf(SubtitleCandidate(FileReference("content://fixture.example/candidate.srt"), "fixture.srt")) else emptyList()
        override suspend fun accessible(reference: FileReference) = reference in grants
        override suspend fun availableStorageBytes() = space
    }
}
