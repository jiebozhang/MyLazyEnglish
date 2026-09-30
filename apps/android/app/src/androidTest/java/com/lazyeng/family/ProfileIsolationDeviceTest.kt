package com.lazyeng.family

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.common.ProfileDeletionCoordinator
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.database.RoomProfileCleaner
import com.lazyeng.family.core.model.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileIsolationDeviceTest {
    @Test fun deletingOneProfilePurgesItsPrivateRowsAndPreservesSiblingData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, FamilyDatabase::class.java).build()
        val family = FamilyId("device-family")
        val first = ProfileId("device-child-a")
        val second = ProfileId("device-child-b")
        val now = Instant.parse("2026-09-27T12:00:00Z")
        val videoId = VideoId("device-video")
        try {
            database.familyRepository().saveFamily(Family(family, emptyMap(), now))
            listOf(first, second).forEach { id ->
                database.profileRepository().saveProfile(family, id, Profile(
                    id, family, id.value, null, ProfileRole.CHILD, "child", "A1", emptyMap(), now, null,
                ))
            }
            val video = Video(
                id = videoId, sourceType = VideoSourceType.LOCAL_FILE,
                sourceReference = VideoSourceReference("content://fixture.example/document/synthetic"),
                checksum = "fixture-checksum",
                metadata = VideoMetadata("Fixture video", null, 300_000, listOf("fixture"), "A1", 1, null,
                    "child", VideoLevelSource.MANUAL),
                status = VideoStatus.DRAFT, importStatus = ImportStatus.Draft,
                subtitleStatus = SubtitleStatus.NoSubtitle, translationStatus = TranslationStatus.Unknown("NOT_REQUESTED"),
                playable = false, rights = VideoRights("FAMILY", "Synthetic fixture", first, true),
            )
            database.videoRepository().saveVideo(first, video)
            database.watchProgressRepository().saveProgress(first, WatchProgress(first, videoId, 83_000, .3f, now))
            database.watchProgressRepository().saveProgress(second, WatchProgress(second, videoId, 12_000, .05f, now))
            listOf(first, second).forEach { profile ->
                database.vocabularyRepository().saveWord(VocabularyCapture(
                    profile, "running", "run", "/rʌn/", "跑", videoId,
                    SubtitleLineId("line-${profile.value}"), "She is running home.", 1_230, now,
                )).getOrThrow()
                database.lookupEventRepository().append(profile, LookupEvent(
                    LookupEventId("lookup-${profile.value}"), EventUuid("event-${profile.value}"), profile,
                    "running", "run", SubtitleLineId("line-${profile.value}"), videoId, now,
                ))
            }
            val item = checkNotNull(database.vocabularyRepository().getItem(first, "run"))
            database.vocabularyRepository().recordReview(
                first, ReviewAttempt(ReviewAttemptId("review-a"), EventUuid("review-a-event"), first,
                    item.key, ReviewRating.REMEMBERED, now), item.copy(status = VocabularyStatus.REVIEW, reviewCount = 1),
            )

            ProfileDeletionCoordinator(database.profileDeletionTransaction()).apply {
                register("private-profile-data", RoomProfileCleaner(database))
            }.request(ProfileDeletionRequested(family, first, now.plusSeconds(1)))

            assertNull(database.profileRepository().getProfile(family, first))
            assertNotNull(database.profileRepository().getProfile(family, second))
            assertNull(database.watchProgressRepository().getProgress(first, videoId))
            assertEquals(12_000L, database.watchProgressRepository().getProgress(second, videoId)?.positionMs)
            assertNull(database.vocabularyRepository().getItem(first, "run"))
            assertEquals("run", database.vocabularyRepository().getItem(second, "run")?.lemma)
            assertEquals(1, database.vocabularyRepository().listContexts(second, "run").size)
            assertTrue(database.reviewAttemptRepository().listAttempts(first, "run").isEmpty())
            assertTrue(database.lookupEventRepository().listEvents(first, now.minusSeconds(1), now.plusSeconds(1)).isEmpty())
            assertEquals(1, database.lookupEventRepository().listEvents(second, now.minusSeconds(1), now.plusSeconds(1)).size)
            database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            database.close()
        }
    }
}
