package com.lazyeng.family.core.database

import androidx.room.Room
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

internal fun sampleVideo(id: String = "fixture-video") = Video(VideoId(id), VideoSourceType.LOCAL_FILE,
    VideoSourceReference("content://fixture.example/document/synthetic"), "fixture-checksum",
    VideoMetadata("Synthetic video", null, 12_000, listOf("example", "quotes: \"x\""), "A1", 8, null, "child", VideoLevelSource.MANUAL),
    VideoStatus.DRAFT, ImportStatus.Draft, SubtitleStatus.NoSubtitle, TranslationStatus.Unknown("NOT_REQUESTED"),
    false, VideoRights("FAMILY", "Synthetic rights note", sampleProfile().id, true))

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class VideoRepositoryTest {
    private val profile = sampleProfile().id
    private val yesMedia = MediaReadinessChecker { _, _, _ -> true }
    private val yesSubtitles = SubtitleReadinessChecker { _, _ -> true }

    @Test fun everyIllegalTransitionPreservesEntireDatabaseRecord() = runTest { database { db ->
        val repo = db.videoRepository(yesMedia, yesSubtitles)
        for (start in VideoStatus.entries) for (end in VideoStatus.entries) {
            val video = sampleVideo("${start.name}-${end.name}")
            repo.writeVideo(profile, video).success()
            if (start != VideoStatus.DRAFT) repo.transition(profile, video.id, VideoStatus.PROCESSING).success()
            when (start) {
                VideoStatus.READY, VideoStatus.ARCHIVED -> repo.transition(profile, video.id, VideoStatus.READY).success()
                VideoStatus.FAILED, VideoStatus.NEEDS_SUBTITLE -> repo.transition(profile, video.id, start, "NO_SUBTITLE", "Ask parent to attach subtitles").success()
                else -> Unit
            }
            if (start == VideoStatus.ARCHIVED) repo.transition(profile, video.id, start).success()
            val before = repo.getVideo(profile, video.id)!!
            if (!before.canTransitionTo(end)) {
                assertEquals(VideoErrors.invalidTransition, (repo.transition(profile, video.id, end) as AppResult.Failure).error)
                assertEquals(before, repo.getVideo(profile, video.id))
            }
        }
    } }

    @Test fun readinessChecksAreScopedFailClosedAndCannotBeBypassedBySaving() = runTest { database { db ->
        val video = sampleVideo()
        val calls = mutableListOf<Pair<ProfileId, VideoId>>()
        val repo = db.videoRepository(yesMedia, SubtitleReadinessChecker { p, v -> calls += p to v; false })
        repo.writeVideo(profile, video).success()
        repo.transition(profile, video.id, VideoStatus.PROCESSING).success()
        assertEquals(KnownAppErrors.noSubtitle, (repo.transition(profile, video.id, VideoStatus.READY) as AppResult.Failure).error)
        assertEquals(listOf(profile to video.id), calls)
        val before = repo.getVideo(profile, video.id)!!
        assertEquals(VideoStatus.PROCESSING, before.status)
        assertEquals(VideoErrors.invalidTransition, (repo.writeVideo(profile, before.copy(status = VideoStatus.READY,
            importStatus = ImportStatus.Ready, playable = true)) as AppResult.Failure).error)
        assertEquals(before, repo.getVideo(profile, video.id))
        assertEquals(KnownAppErrors.videoUnavailable, (db.videoRepository().transition(profile, video.id, VideoStatus.READY) as AppResult.Failure).error)
        assertEquals(KnownAppErrors.noSubtitle, (db.videoRepository(yesMedia).transition(profile, video.id, VideoStatus.READY) as AppResult.Failure).error)
        assertTrue(repo.writeVideo(profile, video.copy(id = VideoId("forged-ready"), status = VideoStatus.READY, importStatus = ImportStatus.Ready)) is AppResult.Failure)
    } }

    @Test fun archivedRecordsRemainResolvableButLeaveRecommendations() = runTest { database { db ->
        val repo = db.videoRepository(yesMedia, yesSubtitles)
        val video = sampleVideo()
        repo.writeVideo(profile, video).success()
        assertEquals(video, repo.getVideo(profile, video.id))
        assertTrue(repo.recommendations(profile).isEmpty())
        repo.transition(profile, video.id, VideoStatus.PROCESSING).success()
        repo.transition(profile, video.id, VideoStatus.READY).success()
        assertEquals(listOf(video.id), repo.recommendations(profile).map { it.id })
        val progress = WatchProgress(profile, video.id, 2000, 0.2f, createdAt)
        db.watchProgressRepository().saveProgress(profile, progress)
        repo.archiveVideo(profile, video.id)
        assertTrue(repo.recommendations(profile).isEmpty())
        assertEquals(VideoStatus.ARCHIVED, repo.getVideo(profile, video.id)?.status)
        assertEquals(progress, db.watchProgressRepository().getProgress(profile, video.id))
    } }

    @Test fun childRecommendationsHideAdultAndUnknownAgeWithoutBreakingHistoryLookup() = runTest { database { db ->
        val repo = db.videoRepository(yesMedia, yesSubtitles)
        for (age in listOf("adult", "unknown", null)) {
            val v = sampleVideo("age-$age").let { it.copy(metadata = it.metadata.copy(ageFit = age)) }
            repo.saveVideo(profile, v)
            repo.transition(profile, v.id, VideoStatus.PROCESSING).success()
            repo.transition(profile, v.id, VideoStatus.READY).success()
            assertNotNull(repo.getVideo(profile, v.id))
        }
        assertTrue(repo.recommendations(profile).isEmpty())
    } }

    @Test fun failureBranchesRequireErrorAndRepairActionAndUnitApiUsesClassifiedFailure() = runTest { database { db ->
        val repo = db.videoRepository()
        for (failure in listOf(VideoStatus.FAILED, VideoStatus.NEEDS_SUBTITLE)) {
            val v = sampleVideo(failure.name)
            repo.saveVideo(profile, v)
            repo.transition(profile, v.id, VideoStatus.PROCESSING).success()
            assertEquals(VideoErrors.invalidRecord, (repo.transition(profile, v.id, failure) as AppResult.Failure).error)
            val updated = repo.transition(profile, v.id, failure, "NO_SUBTITLE", "Parent: attach English subtitles").success()
            assertEquals("NO_SUBTITLE", updated.errorCode)
            assertNotNull(updated.repairAdvice)
            val thrown = runCatching { repo.archiveVideo(profile, v.id) }.exceptionOrNull()
            assertEquals(VideoErrors.invalidTransition, (thrown as VideoOperationException).error)
        }
    } }

    @Test fun assetAndJobRoundTripPreserveUnknownStatusAndCannotMoveOwnership() = runTest { database { db ->
        val video = sampleVideo()
        db.videoRepository().saveVideo(profile, video)
        val other = sampleVideo("other")
        db.videoRepository().saveVideo(profile, other)
        val assets = db.videoAssetRepository()
        val asset = VideoAsset(VideoAssetId("asset"), video.id, FileReference("content://fixture.example/document/synthetic"), "sample-hash", "video/avc", 128)
        assets.saveAsset(profile, asset)
        assertEquals(asset, assets.getAsset(profile, asset.id))
        assertEquals(listOf(asset), assets.listAssets(profile, video.id))
        val covered = video.copy(metadata = video.metadata.copy(coverAssetId = asset.id))
        db.videoRepository().saveVideo(profile, covered)
        assertEquals(covered, db.videoRepository().getVideo(profile, video.id))
        assertTrue(db.videoRepository().writeVideo(profile, other.copy(metadata = other.metadata.copy(coverAssetId = asset.id))) is AppResult.Failure)
        assertTrue(runCatching { assets.saveAsset(profile, asset.copy(videoId = other.id)) }.isFailure)
        val jobs = db.importJobRepository()
        val job = ImportJob(ImportJobId("job"), video.id, ImportStatus.Unknown("FUTURE_STAGE"), "synthetic-key")
        jobs.saveImportJob(profile, job)
        assertEquals(job, jobs.getImportJob(profile, job.id))
        assertTrue(runCatching { jobs.saveImportJob(profile, job.copy(id = ImportJobId("duplicate"))) }.isFailure)
        assertTrue(runCatching { jobs.saveImportJob(profile, job.copy(videoId = other.id)) }.isFailure)
    } }

    @Test fun familiesAndProfilesAreIsolatedAndStaleProgressCannotOverwriteNewerProgress() = runTest { database { db ->
        val sibling = sampleProfile(id = "sibling")
        db.profileRepository().saveProfile(familyA.id, sibling.id, sibling)
        val other = sampleProfile(familyB, "other-family-child")
        db.familyRepository().saveFamily(familyB)
        db.profileRepository().saveProfile(familyB.id, other.id, other)
        val v = sampleVideo()
        val repo = db.videoRepository()
        repo.saveVideo(profile, v)
        assertNull(repo.getVideo(other.id, v.id))
        assertTrue(repo.listVideos(other.id).isEmpty())
        assertTrue(repo.writeVideo(other.id, v) is AppResult.Failure)
        val progress = db.watchProgressRepository()
        val first = WatchProgress(profile, v.id, 1000, 0.1f, createdAt)
        val second = first.copy(profileId = sibling.id, positionMs = 2000)
        progress.saveProgress(profile, first); progress.saveProgress(sibling.id, second)
        progress.saveProgress(profile, first.copy(positionMs = 0, updatedAt = createdAt.minusSeconds(1)))
        assertEquals(first, progress.getProgress(profile, v.id))
        assertEquals(listOf(second), progress.listProgress(sibling.id))
        assertTrue(progress.listProgress(other.id).isEmpty())
        assertTrue(runCatching { progress.saveProgress(profile, second) }.isFailure)
        assertTrue(runCatching { progress.saveProgress(other.id, first.copy(profileId = other.id)) }.isFailure)
        db.profileRepository().deleteProfile(familyA.id, profile, createdAt.plusSeconds(1))
        assertNull(repo.getVideo(profile, v.id))
        assertTrue(progress.listProgress(profile).isEmpty())
        assertNotNull(repo.getVideo(sibling.id, v.id))
        // Deleting the uploader must not prevent a remaining member from editing shared metadata.
        val edited = v.copy(metadata = v.metadata.copy(title = "Edited synthetic title"))
        repo.saveVideo(sibling.id, edited)
        assertEquals(edited, repo.getVideo(sibling.id, v.id))
    } }

    @Test fun cancelledGuardRollsBackAndSimultaneousTransitionsCannotBothSucceed() = runTest { database { db ->
        val entered = CompletableDeferred<Unit>()
        val repo = db.videoRepository(yesMedia, SubtitleReadinessChecker { _, _ -> entered.complete(Unit); awaitCancellation() })
        val v = sampleVideo()
        repo.saveVideo(profile, v)
        repo.transition(profile, v.id, VideoStatus.PROCESSING).success()
        val pending = launch { repo.transition(profile, v.id, VideoStatus.READY) }
        entered.await(); pending.cancelAndJoin()
        assertEquals(VideoStatus.PROCESSING, repo.getVideo(profile, v.id)?.status)
        val valid = db.videoRepository(yesMedia, yesSubtitles)
        val results = listOf(async { valid.transition(profile, v.id, VideoStatus.READY) },
            async { valid.transition(profile, v.id, VideoStatus.READY) }).awaitAll()
        assertEquals(1, results.count { it is AppResult.Success })
        assertEquals(1, results.count { it is AppResult.Failure })
    } }

    private suspend fun database(block: suspend (FamilyDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java).build()
        try {
            db.familyRepository().saveFamily(familyA)
            db.profileRepository().saveProfile(familyA.id, profile, sampleProfile())
            block(db)
        } finally { db.close() }
    }
    private fun <T> AppResult<T>.success(): T {
        assertTrue("Expected success, got $this", this is AppResult.Success)
        return (this as AppResult.Success).value
    }
}
