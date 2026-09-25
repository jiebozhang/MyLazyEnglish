package com.lazyeng.family.core.database

import androidx.room.Room
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.model.*
import kotlinx.coroutines.test.runTest
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
class SubtitleRepositoryTest {
    private val profile = sampleProfile().id
    private val other = sampleProfile(familyB, "other").id
    private val track = SubtitleTrack(SubtitleTrackId("track"), sampleVideo().id, "en", "SRT", null)
    private val version = DraftSubtitleVersion(SubtitleVersionId("version"), track.id, "synthetic-hash", "srt-vtt-1", "UTF-8")
    private val lines = listOf(SubtitleLine(SubtitleLineId("line-2"), version.id, 2, 4000, 6000, "Second example."),
        SubtitleLine(SubtitleLineId("line-1"), version.id, 1, 1250, 3500, "First example."))

    @Test fun roundTripSortingPaginationAndDraftReplacementAreAtomic() = runTest { database { db ->
        val repo = db.subtitleRepository()
        repo.saveTrack(profile, track)
        repo.saveDraft(profile, version, lines)
        assertEquals(track, repo.getTrack(profile, track.id))
        assertEquals(listOf(track), repo.listTracks(profile, track.videoId))
        assertEquals(version, repo.getDraft(profile, version.id))
        assertEquals(SubtitleVersionStatus.DRAFT, repo.getDraft(profile, version.id)?.status)
        assertNull(repo.getPublishedVersion(profile, version.id))
        assertEquals(lines.sortedBy { it.sequence }, repo.listLines(profile, version.id, 0, 10))
        assertEquals(listOf(lines[0]), repo.listLines(profile, version.id, 1, 1))
        val updated = version.copy(sourceHash = "replacement-hash")
        repo.saveDraft(profile, updated, listOf(lines[0]))
        assertEquals(updated, repo.getDraft(profile, version.id))
        assertEquals(listOf(lines[0]), repo.listLines(profile, version.id, 0, 10))
        val bad = listOf(lines[0], lines[0].copy(id = SubtitleLineId("duplicate-seq")))
        assertTrue(repo.writeDraft(profile, version, bad) is AppResult.Failure)
        assertEquals(updated, repo.getDraft(profile, version.id))
        assertEquals(listOf(lines[0]), repo.listLines(profile, version.id, 0, 10))
    } }

    @Test fun everyReadAndWriteRequiresActiveHouseholdScope() = runTest { database { db ->
        val repo = db.subtitleRepository()
        repo.saveTrack(profile, track)
        repo.saveDraft(profile, version, lines)
        for (requester in listOf(other, ProfileId("missing"))) {
            assertNull(repo.getTrack(requester, track.id))
            assertTrue(repo.listTracks(requester, track.videoId).isEmpty())
            assertNull(repo.getDraft(requester, version.id))
            assertNull(repo.getPublishedVersion(requester, version.id))
            assertTrue(repo.listLines(requester, version.id, 0, 10).isEmpty())
            assertEquals(SubtitleErrors.scopeUnavailable, (repo.writeDraft(requester, version, lines) as AppResult.Failure).error)
            assertTrue(repo.writeTrack(requester, track) is AppResult.Failure)
        }
        db.profileRepository().deleteProfile(familyA.id, profile, createdAt.plusSeconds(1))
        assertNull(repo.getTrack(profile, track.id))
        assertNull(repo.getDraft(profile, version.id))
        assertTrue(repo.listLines(profile, version.id, 0, 10).isEmpty())
        assertTrue(repo.writeDraft(profile, version, lines) is AppResult.Failure)
    } }

    @Test fun globalIdCollisionCannotOverwriteAnotherHouseholdsTrackVersionOrLines() = runTest { database { db ->
        val repo = db.subtitleRepository()
        repo.saveTrack(profile, track)
        repo.saveDraft(profile, version, lines)
        val otherVideo = sampleVideo("other-video").copy(rights = sampleVideo().rights.copy(addedBy = other))
        db.videoRepository().saveVideo(other, otherVideo)
        assertTrue(repo.writeTrack(other, track.copy(videoId = otherVideo.id)) is AppResult.Failure)
        val otherTrack = track.copy(id = SubtitleTrackId("other-track"), videoId = otherVideo.id)
        repo.saveTrack(other, otherTrack)
        assertTrue(repo.writeDraft(other, version.copy(trackId = otherTrack.id), lines) is AppResult.Failure)
        val otherVersion = version.copy(id = SubtitleVersionId("other-version"), trackId = otherTrack.id)
        assertTrue(repo.writeDraft(other, otherVersion, lines.map { it.copy(versionId = otherVersion.id) }) is AppResult.Failure)
        assertNull(repo.getDraft(other, otherVersion.id)) // Failed insert rolls back the version too.
        assertEquals(version, repo.getDraft(profile, version.id))
        assertEquals(lines.sortedBy { it.sequence }, repo.listLines(profile, version.id, 0, 10))
    } }

    @Test fun publishedReadShapeAndCurrentVersionOwnershipAreReadyForLaterPublisher() = runTest { database { db ->
        val repo = db.subtitleRepository()
        repo.saveTrack(profile, track)
        repo.saveDraft(profile, version, lines)
        val sql = db.openHelper.writableDatabase
        // Test-only seed of the read model, not a production publication implementation.
        sql.execSQL("UPDATE subtitle_versions SET status='PUBLISHED', published_at=? WHERE id=?", arrayOf(createdAt.toString(), version.id.value))
        val published = repo.getPublishedVersion(profile, version.id)!!
        assertEquals(SubtitleVersionStatus.PUBLISHED, published.status)
        assertEquals(createdAt, published.publishedAt)
        assertEquals("UTF-8", published.sourceEncoding)
        assertNull(repo.getDraft(profile, version.id))
        assertNull(repo.getPublishedVersion(other, version.id))
        val second = track.copy(id = SubtitleTrackId("second-track"))
        repo.saveTrack(profile, second)
        assertTrue(runCatching { sql.execSQL("UPDATE subtitle_tracks SET current_version_id=? WHERE id=?", arrayOf(version.id.value, second.id.value)) }.isFailure)
        assertNull(repo.getTrack(profile, second.id)?.currentVersionId)
        assertTrue(repo.writeTrack(profile, track.copy(currentVersionId = version.id)) is AppResult.Failure)
        assertTrue(repo.writeDraft(profile, version, lines) is AppResult.Failure)
        val defaultVideoRepo = db.videoRepository(MediaReadinessChecker { _, _, _ -> true })
        defaultVideoRepo.transition(profile, track.videoId, VideoStatus.PROCESSING)
        assertEquals(KnownAppErrors.noSubtitle, (defaultVideoRepo.transition(profile, track.videoId, VideoStatus.READY) as AppResult.Failure).error)
    } }

    private suspend fun database(block: suspend (FamilyDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java).build()
        try {
            db.familyRepository().saveFamily(familyA)
            db.familyRepository().saveFamily(familyB)
            db.profileRepository().saveProfile(familyA.id, profile, sampleProfile())
            db.profileRepository().saveProfile(familyB.id, other, sampleProfile(familyB, "other"))
            db.videoRepository().saveVideo(profile, sampleVideo())
            block(db)
        } finally { db.close() }
    }
}
