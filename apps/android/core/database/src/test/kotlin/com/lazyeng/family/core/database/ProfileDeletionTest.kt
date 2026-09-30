package com.lazyeng.family.core.database

import androidx.room.Room
import com.lazyeng.family.core.common.ProfileDeletionCoordinator
import com.lazyeng.family.core.model.ProfileDeletionRequested
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileDeletionTest {
    @Test fun purgeIsProfileScopedAcrossProgressVocabularyAndLookupAndIsAtomic() = runTest {
        database { db ->
            val profileA = sampleProfile().id
            val profileB = ProfileId("fixture-sibling")
            db.profileRepository().saveProfile(familyA.id, profileB, sampleProfile(familyA, profileB.value))
            val video = sampleVideo()
            db.videoRepository().saveVideo(profileA, video)
            db.watchProgressRepository().saveProgress(profileA, WatchProgress(profileA, video.id, 83_000, .3f, createdAt))
            db.watchProgressRepository().saveProgress(profileB, WatchProgress(profileB, video.id, 12_000, .05f, createdAt))
            listOf(profileA, profileB).forEach { profile ->
                db.lookupEventRepository().append(profile, LookupEvent(
                    LookupEventId("lookup-${profile.value}"), EventUuid("event-${profile.value}"), profile,
                    "running", "run", SubtitleLineId("line-shared"), video.id, createdAt,
                ))
                assertTrue(db.vocabularyRepository().saveWord(VocabularyCapture(
                    profile, "running", "run", "/rʌn/", "跑", video.id,
                    SubtitleLineId("line-${profile.value}"), "She is running home.", 1_230, createdAt,
                )).isSuccess)
            }
            val itemA = checkNotNull(db.vocabularyRepository().getItem(profileA, "run"))
            db.vocabularyRepository().recordReview(
                profileA,
                ReviewAttempt(ReviewAttemptId("review-a"), EventUuid("review-event-a"), profileA,
                    itemA.key, ReviewRating.REMEMBERED, createdAt),
                itemA.copy(status = VocabularyStatus.REVIEW, reviewCount = 1),
            )

            val coordinator = ProfileDeletionCoordinator(db.profileDeletionTransaction()).apply {
                register("profile-private-data", RoomProfileCleaner(db))
            }
            coordinator.request(request)

            assertNull(db.profileDao().getRecord(familyA.id.value, profileA.value))
            assertNotNull(db.profileRepository().getProfile(familyA.id, profileB))
            assertNull(db.watchProgressRepository().getProgress(profileA, video.id))
            assertEquals(12_000L, db.watchProgressRepository().getProgress(profileB, video.id)?.positionMs)
            assertTrue(db.lookupEventRepository().listEvents(profileA, createdAt.minusSeconds(1), createdAt.plusSeconds(1)).isEmpty())
            assertEquals(1, db.lookupEventRepository().listEvents(profileB, createdAt.minusSeconds(1), createdAt.plusSeconds(1)).size)
            assertNull(db.vocabularyRepository().getItem(profileA, "run"))
            assertTrue(db.vocabularyRepository().listContexts(profileA, "run").isEmpty())
            assertTrue(db.reviewAttemptRepository().listAttempts(profileA, "run").isEmpty())
            assertNotNull(db.vocabularyRepository().getItem(profileB, "run"))
            assertEquals(1, db.vocabularyRepository().listContexts(profileB, "run").size)

            db.profileRepository().saveProfile(familyA.id, profileA, sampleProfile())
            db.vocabularyRepository().saveWord(VocabularyCapture(
                profileA, "running", "run", "/rʌn/", "跑", video.id,
                SubtitleLineId("rollback-line"), "She is running home.", 1_230, createdAt,
            ))
            db.watchProgressRepository().saveProgress(profileA, WatchProgress(profileA, video.id, 83_000, .3f, createdAt))
            db.lookupEventRepository().append(profileA, LookupEvent(
                LookupEventId("lookup-rollback"), EventUuid("event-rollback"), profileA,
                "running", "run", SubtitleLineId("line-rollback"), video.id, createdAt,
            ))
            val failedCoordinator = ProfileDeletionCoordinator(db.profileDeletionTransaction()).apply {
                register("profile-private-data", RoomProfileCleaner(db))
                register("forced-failure") { error("force transaction rollback") }
            }
            assertTrue(runCatching { failedCoordinator.request(request) }.isFailure)
            assertNotNull(db.profileRepository().getProfile(familyA.id, profileA))
            assertNotNull(db.vocabularyRepository().getItem(profileA, "run"))
            assertNotNull(db.watchProgressRepository().getProgress(profileA, video.id))
            assertEquals(1, db.lookupEventRepository().listEvents(profileA, createdAt.minusSeconds(1), createdAt.plusSeconds(1)).size)
        }
    }

    @Test fun directoryIsScopedAndExcludesTombstones() = runTest {
        database { db ->
            db.familyRepository().saveFamily(familyB)
            val other = sampleProfile(familyB, "other-fixture")
            db.profileRepository().saveProfile(familyB.id, other.id, other)
            assertEquals(listOf(sampleProfile().id), db.profileDirectory().choices(familyA.id).map { it.id })
            db.profileRepository().deleteProfile(familyA.id, sampleProfile().id, createdAt.plusSeconds(1))
            assertTrue(db.profileDirectory().choices(familyA.id).isEmpty())
            assertEquals(listOf(other.id), db.profileDirectory().choices(familyB.id).map { it.id })
        }
    }

    @Test fun failureInLaterCleanerRollsBackBodyAndPriorCleanerWrites() = runTest {
        database { db ->
            val coordinator = coordinator(db)
            coordinator.register("other-transactional-work") {
                db.familyRepository().saveFamily(familyA.copy(settings = mapOf("fixture" to "changed")))
            }
            coordinator.register("failing-fixture") { error("synthetic failure") }
            assertTrue(runCatching { coordinator.request(request) }.isFailure)
            assertEquals(sampleProfile(), db.profileRepository().getProfile(familyA.id, sampleProfile().id))
            assertEquals(familyA, db.familyRepository().getFamily(familyA.id))
        }
    }

    @Test fun cancellationAfterBodyAndExpiredAuthorizationRollBack() = runTest {
        database { db ->
            val entered = CompletableDeferred<Unit>()
            val coordinator = coordinator(db)
            coordinator.register("suspended-fixture") { entered.complete(Unit); awaitCancellation() }
            val job = launch { coordinator.request(request) }
            entered.await(); job.cancelAndJoin()
            assertEquals(sampleProfile(), db.profileRepository().getProfile(familyA.id, sampleProfile().id))
            var checks = 0
            assertTrue(runCatching { coordinator(db).request(request) { check(++checks == 1) } }.isFailure)
            assertEquals(sampleProfile(), db.profileRepository().getProfile(familyA.id, sampleProfile().id))
        }
    }

    @Test fun multipleRegisteredCleanersCommitOnceAndRejectDuplicateNames() = runTest {
        database { db ->
            val coordinator = coordinator(db)
            val seen = mutableListOf<ProfileDeletionRequested>()
            coordinator.register("observer-fixture") { seen += it }
            assertTrue(runCatching { coordinator.register("profile-body") {} }.isFailure)
            coordinator.request(request)
            assertEquals(listOf(request), seen)
            assertNull(db.profileRepository().getProfile(familyA.id, sampleProfile().id))
            assertNull(db.profileDao().getRecord(familyA.id.value, sampleProfile().id.value))
        }
    }

    private val request = ProfileDeletionRequested(familyA.id, sampleProfile().id, createdAt.plusSeconds(1))
    private fun coordinator(db: FamilyDatabase) = ProfileDeletionCoordinator(db.profileDeletionTransaction()).apply {
        register("profile-body", RoomProfileCleaner(db))
    }
    private suspend fun database(block: suspend (FamilyDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java).build()
        try {
            db.familyRepository().saveFamily(familyA)
            db.profileRepository().saveProfile(familyA.id, sampleProfile().id, sampleProfile())
            block(db)
        } finally { db.close() }
    }
}
