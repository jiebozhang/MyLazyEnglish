package com.lazyeng.family.core.database

import androidx.room.Room
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.common.subtitle.*
import com.lazyeng.family.core.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubtitlePublishTest {
    @get:Rule val temporary = TemporaryFolder()
    private val profile = sampleProfile().id
    private val track = SubtitleTrack(SubtitleTrackId("track"), sampleVideo().id, "en", "SRT", null)
    private val version = DraftSubtitleVersion(SubtitleVersionId("version"), track.id, "synthetic-hash", "srt-vtt-1", "UTF-8")
    private val line = SubtitleLine(SubtitleLineId("line"), version.id, 1, 1000, 2000, "A synthetic example.")

    @Test fun draftsSupportAddEditRemoveLinesAndDeleteVersion() = runTest { database { db ->
        val repo = db.subtitleRepository()
        repo.saveDraft(profile, version, listOf(line))
        val second = line.copy(id = SubtitleLineId("second"), sequence = 2, startMs = 2000, endMs = 3000)
        repo.saveDraft(profile, version.copy(sourceHash = "edited"), listOf(line.copy(text = "Edited."), second))
        assertEquals(2, repo.listLines(profile, version.id, 0, 10).size)
        repo.saveDraft(profile, version, emptyList())
        assertTrue(repo.listLines(profile, version.id, 0, 10).isEmpty())
        assertTrue(repo.deleteDraft(profile, version.id) is AppResult.Success)
        assertNull(repo.getDraft(profile, version.id))
        assertNotNull(repo.getTrack(profile, track.id))
    } }

    @Test fun publishIsAtomicAndBothRepositoryAndDaoRejectAllPublishedMutations() = runTest { database { db ->
        val repo = db.subtitleRepository()
        val ready = db.subtitleReadinessChecker()
        assertFalse(ready.hasPublishedEnglish(profile, track.videoId))
        repo.saveDraft(profile, version, listOf(line))
        assertFalse(ready.hasPublishedEnglish(profile, track.videoId))
        val published = (repo.publish(profile, version.id, createdAt) as SubtitlePublishResult.Success).version
        assertEquals(createdAt, published.publishedAt)
        assertEquals(version.id, repo.getTrack(profile, track.id)?.currentVersionId)
        assertTrue(ready.hasPublishedEnglish(profile, track.videoId))
        assertEquals(SubtitleErrors.immutableVersion, (repo.writeDraft(profile, version.copy(sourceHash = "tampered"), listOf(line.copy(text = "Changed."))) as AppResult.Failure).error)
        assertEquals(SubtitleErrors.immutableVersion, (repo.deleteDraft(profile, version.id) as AppResult.Failure).error)
        val dao = db.subtitleDao()
        val entity = dao.version(profile.value, version.id.value)!!
        val mutations: List<suspend () -> Unit> = listOf(
            { dao.updateDraft(profile.value, entity.copy(status = "DRAFT", publishedAt = null, sourceHash = "tampered")) },
            { dao.clearDraftLines(profile.value, version.id.value) },
            { dao.appendDraftLines(profile.value, listOf(line.copy(id = SubtitleLineId("injected")).toEntity())) },
            { dao.deleteDraft(profile.value, version.id.value) },
        )
        for (mutation in mutations) {
            val failure = runCatching { mutation() }.exceptionOrNull()
            assertTrue(failure is SubtitleOperationException)
            assertEquals(SubtitleErrors.immutableVersion, (failure as SubtitleOperationException).error)
        }
        assertEquals(0, dao.markPublished(profile.value, version.id.value, createdAt.plusSeconds(99)))
        assertEquals(entity, dao.version(profile.value, version.id.value))
        assertEquals(listOf(line), repo.listLines(profile, version.id, 0, 10))
        val videoRepo = db.videoRepository(MediaReadinessChecker { _, _, _ -> true })
        videoRepo.transition(profile, track.videoId, VideoStatus.PROCESSING)
        assertTrue(videoRepo.transition(profile, track.videoId, VideoStatus.READY) is AppResult.Success)
    } }

    @Test fun invalidTimelinesNeverPublishAndWarningsRemainVisible() = runTest { database { db ->
        val repo = db.subtitleRepository()
        repo.saveDraft(profile, version, listOf(line.copy(endMs = 500)))
        val rejected = repo.publish(profile, version.id, createdAt) as SubtitlePublishResult.Failure
        assertEquals(SubtitleErrors.invalidTimeline, rejected.error)
        assertTrue(rejected.validation!!.findings.any { it.issue == TimelineIssue.REVERSED_TIME })
        assertNotNull(repo.getDraft(profile, version.id))
        assertNull(repo.getTrack(profile, track.id)?.currentVersionId)
        repo.saveDraft(profile, version, emptyList())
        assertTrue(repo.publish(profile, version.id, createdAt) is SubtitlePublishResult.Failure)
        val overlap = line.copy(id = SubtitleLineId("overlap"), sequence = 2, startMs = 1500, endMs = 2500)
        repo.saveDraft(profile, version, listOf(line, overlap))
        val published = repo.publish(profile, version.id, createdAt) as SubtitlePublishResult.Success
        assertTrue(published.validation.canPublish)
        assertTrue(published.validation.findings.any { it.issue == TimelineIssue.OVERLAP && it.severity == TimelineSeverity.WARNING })
    } }

    @Test fun publishingReplacementPreservesHistoryAndRetryCannotReactivateOldVersion() = runTest { database { db ->
        val repo = db.subtitleRepository()
        repo.saveDraft(profile, version, listOf(line))
        repo.publishDraft(profile, version.id, createdAt)
        val next = version.copy(id = SubtitleVersionId("next"))
        val shifted = TimelineValidator.previewOffset(listOf(line), 300).lines.single()
            .copy(id = SubtitleLineId("next-line"), versionId = next.id)
        repo.saveDraft(profile, next, listOf(shifted))
        repo.publishDraft(profile, next.id, createdAt.plusSeconds(1))
        assertEquals(createdAt, repo.publishDraft(profile, version.id, createdAt.plusSeconds(10)).publishedAt)
        assertEquals(next.id, repo.getTrack(profile, track.id)?.currentVersionId)
        assertEquals(listOf(line), repo.listLines(profile, version.id, 0, 10))
        assertEquals(listOf(shifted), repo.listLines(profile, next.id, 0, 10))
    } }

    @Test fun readinessRequiresCurrentEnglishValidVersionAndActiveHouseholdScope() = runTest { database { db ->
        val repo = db.subtitleRepository()
        val ready = db.subtitleReadinessChecker()
        repo.saveTrack(profile, track.copy(language = "zh"))
        repo.saveDraft(profile, version, listOf(line))
        repo.publishDraft(profile, version.id, createdAt)
        assertFalse(ready.hasPublishedEnglish(profile, track.videoId))
        repo.saveTrack(profile, track.copy(language = "en-GB", currentVersionId = version.id))
        assertTrue(ready.hasPublishedEnglish(profile, track.videoId))
        val other = sampleProfile(familyB, "other")
        db.familyRepository().saveFamily(familyB)
        db.profileRepository().saveProfile(familyB.id, other.id, other)
        assertFalse(ready.hasPublishedEnglish(other.id, track.videoId))
        assertEquals(SubtitleErrors.scopeUnavailable, (repo.publish(other.id, version.id, createdAt) as SubtitlePublishResult.Failure).error)
        assertEquals(SubtitleErrors.scopeUnavailable, (repo.deleteDraft(other.id, version.id) as AppResult.Failure).error)
        assertFalse(ready.hasPublishedEnglish(profile, VideoId("missing")))
        db.profileRepository().deleteProfile(familyA.id, profile, createdAt.plusSeconds(1))
        assertFalse(ready.hasPublishedEnglish(profile, track.videoId))
    } }

    @Test fun legacyPublishedButUnboundEmptyOrInvalidDataIsNotReady() = runTest { database { db ->
        val repo = db.subtitleRepository()
        val dao = db.subtitleDao()
        val ready = db.subtitleReadinessChecker()
        repo.saveDraft(profile, version, emptyList())
        // Deliberately malformed legacy storage, outside the production publisher.
        dao.markPublished(profile.value, version.id.value, createdAt)
        assertFalse(ready.hasPublishedEnglish(profile, track.videoId))
        dao.selectPublished(profile.value, track.id.value, version.id.value)
        assertFalse(ready.hasPublishedEnglish(profile, track.videoId))
        db.openHelper.writableDatabase.execSQL("INSERT INTO subtitle_lines VALUES ('broken', 'version', 1, 1000, 500, 'Synthetic invalid cue.')")
        assertFalse(ready.hasPublishedEnglish(profile, track.videoId))
    } }

    @Test fun currentPointerWriteFailureRollsBackPublication() = runTest { database { db ->
        val repo = db.subtitleRepository()
        repo.saveDraft(profile, version, listOf(line))
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_pointer BEFORE UPDATE OF current_version_id ON subtitle_tracks BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        assertTrue(repo.publish(profile, version.id, createdAt) is SubtitlePublishResult.Failure)
        assertNotNull(repo.getDraft(profile, version.id))
        assertNull(repo.getPublishedVersion(profile, version.id))
        assertNull(repo.getTrack(profile, track.id)?.currentVersionId)
        assertEquals(listOf(line), repo.listLines(profile, version.id, 0, 10))
    } }

    @Test fun competingEditsAndPublishersCannotMutateAfterPublication() = runTest { database { db ->
        val repo = db.subtitleRepository()
        repo.saveDraft(profile, version, listOf(line))
        coroutineScope {
            val first = async { repo.publishDraft(profile, version.id, createdAt) }
            val edit = async { repo.writeDraft(profile, version, listOf(line.copy(text = "Concurrent edit."))) }
            val retry = async { repo.publishDraft(profile, version.id, createdAt.plusSeconds(5)) }
            assertEquals(first.await().publishedAt, retry.await().publishedAt)
            val edited = edit.await()
            val expected = if (edited is AppResult.Success) "Concurrent edit." else line.text
            assertEquals(expected, repo.listLines(profile, version.id, 0, 10).single().text)
            if (edited is AppResult.Failure) assertEquals(SubtitleErrors.immutableVersion, edited.error)
        }
    } }

    @Test fun publicationAndImmutabilitySurviveDatabaseRecreation() = runTest {
        val file = File(temporary.root, "published.db")
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java, file.absolutePath).build()
        val first = open()
        try {
            first.familyRepository().saveFamily(familyA)
            first.profileRepository().saveProfile(familyA.id, profile, sampleProfile())
            first.videoRepository().saveVideo(profile, sampleVideo())
            first.subtitleRepository().saveTrack(profile, track)
            first.subtitleRepository().saveDraft(profile, version, listOf(line))
            first.subtitleRepository().publishDraft(profile, version.id, createdAt)
        } finally { first.close() }
        val reopened = open()
        try {
            val repo = reopened.subtitleRepository()
            assertEquals(createdAt, repo.getPublishedVersion(profile, version.id)?.publishedAt)
            assertEquals(version.id, repo.getTrack(profile, track.id)?.currentVersionId)
            assertEquals(listOf(line), repo.listLines(profile, version.id, 0, 10))
            assertTrue(reopened.subtitleReadinessChecker().hasPublishedEnglish(profile, track.videoId))
            assertEquals(SubtitleErrors.immutableVersion, (repo.writeDraft(profile, version, listOf(line)) as AppResult.Failure).error)
        } finally { reopened.close() }
    }

    private suspend fun database(block: suspend (FamilyDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java).build()
        try {
            db.familyRepository().saveFamily(familyA)
            db.profileRepository().saveProfile(familyA.id, profile, sampleProfile())
            db.videoRepository().saveVideo(profile, sampleVideo())
            db.subtitleRepository().saveTrack(profile, track)
            block(db)
        } finally { db.close() }
    }
}
