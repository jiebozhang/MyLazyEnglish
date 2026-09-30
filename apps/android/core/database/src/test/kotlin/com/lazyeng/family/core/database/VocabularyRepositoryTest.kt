package com.lazyeng.family.core.database

import androidx.room.Room
import com.lazyeng.family.core.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class VocabularyRepositoryTest {
    private val profileA = ProfileId("profile-a")
    private val profileB = ProfileId("profile-b")
    private val capturedAt = Instant.parse("2026-09-27T10:00:00Z")

    @Test fun captureIsIdempotentPerOccurrenceAndKeepsDistinctContexts() = runTest {
        database { db ->
            val repository = db.vocabularyRepository()
            val first = capture(profileA, line = "line-1", sentence = "She is running home.")
            assertTrue(repository.saveWord(first).isSuccess)
            assertTrue(repository.saveWord(first).isSuccess)
            assertTrue(repository.saveWord(capture(profileA, line = "line-2", sentence = "Run to the park.")).isSuccess)

            val items = repository.getWordsByProfile(profileA).first()
            assertEquals(1, items.size)
            assertTrue(repository.getWordsByProfile(profileB).first().isEmpty())
            assertEquals(VocabularyStatus.LEARNING, items.single().status)
            assertTrue(repository.isWordSaved(profileA, "RUN").first())
            assertFalse(repository.isWordSaved(profileB, "run").first())
            assertEquals(setOf("run"), repository.listSavedLemmas(profileA))

            val contexts = repository.listContexts(profileA, "run")
            assertEquals(2, contexts.size)
            assertTrue(repository.listContexts(profileB, "run").isEmpty())
            assertEquals("running", contexts.first().surface)
            assertEquals("/rʌn/", contexts.first().phonetic)
            assertEquals("She is running home.", contexts.first().contextSentence)
            assertEquals(VideoId("video-a"), contexts.first().videoId)
            assertEquals(1_240L, contexts.first().timestampMs)

            repository.saveSnapshot(profileA, items.single().copy(status = VocabularyStatus.REVIEW))
            repository.saveWord(first)
            assertEquals(VocabularyStatus.REVIEW, repository.getItem(profileA, "run")?.status)
            assertEquals(2, repository.listContexts(profileA, "run").size)
        }
    }

    @Test fun deletingByIdIsProfileScopedAndRemovesOnlyItsContexts() = runTest {
        database { db ->
            val repository = db.vocabularyRepository()
            repository.saveWord(capture(profileA, "line-a", "Run away."))
            repository.saveWord(capture(profileB, "line-b", "Run home."))
            val own = repository.getItem(profileA, "run")!!
            repository.deleteWord(profileB, own.id)
            assertTrue(repository.isWordSaved(profileA, "run").first())
            repository.deleteItem(profileA, "run")
            assertFalse(repository.isWordSaved(profileA, "run").first())
            assertTrue(repository.isWordSaved(profileB, "run").first())
            assertEquals(1, repository.listContexts(profileB, "run").size)
        }
    }

    @Test fun reviewAttemptAndSnapshotUpdateAreAtomicAndIdempotentByEventUuid() = runTest {
        database { db ->
            val repository = db.vocabularyRepository()
            repository.saveWord(capture(profileA, "line-review", "They run home."))
            val original = repository.getItem(profileA, "run")!!
            val reviewedAt = capturedAt.plusSeconds(600)
            val updated = original.copy(
                status = VocabularyStatus.REVIEW,
                stage = 1,
                nextReviewAt = reviewedAt.plusSeconds(3 * 86_400),
                reviewCount = 1,
                consecutiveRemembered = 1,
                lastReviewedAt = reviewedAt,
            )
            val attempt = ReviewAttempt(
                ReviewAttemptId("attempt-1"), EventUuid("event-1"), profileA, original.key,
                ReviewRating.REMEMBERED, reviewedAt,
            )
            assertTrue(repository.recordReview(profileA, attempt, updated))
            assertFalse(repository.recordReview(profileA, attempt.copy(id = ReviewAttemptId("attempt-retry")), original))
            assertEquals(updated, repository.getItem(profileA, "run"))
            assertEquals(1, db.reviewAttemptRepository().listAttempts(profileA, "run").size)
            assertTrue(repository.getWordsByProfile(profileB).first().isEmpty())
        }
    }

    @Test fun capturedWordAndSourceSnapshotSurviveDatabaseReopen() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "vocabulary-persistence-test.db"
        context.deleteDatabase(name)
        try {
            val db = Room.databaseBuilder(context, FamilyDatabase::class.java, name).build()
            try {
                db.familyRepository().saveFamily(familyA)
                db.profileRepository().saveProfile(familyA.id, profileA, sampleProfile(familyA, profileA.value))
                assertTrue(db.vocabularyRepository().saveWord(
                    capture(profileA, "line-reopen", "She is running home."),
                ).isSuccess)
            } finally {
                db.close()
            }
            val reopened = Room.databaseBuilder(context, FamilyDatabase::class.java, name).build()
            try {
                val repository = reopened.vocabularyRepository()
                assertTrue(repository.isWordSaved(profileA, "run").first())
                val saved = repository.listContexts(profileA, "run").single()
                assertEquals("running", saved.surface)
                assertEquals("跑", saved.definition)
                assertEquals("She is running home.", saved.contextSentence)
                assertEquals("video-a", saved.videoId?.value)
                assertEquals(1_240L, saved.timestampMs)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun capture(profileId: ProfileId, line: String, sentence: String) = VocabularyCapture(
        profileId = profileId,
        surface = if (sentence.contains("running")) "running" else "run",
        lemma = "run",
        phonetic = "/rʌn/",
        definition = "跑",
        videoId = VideoId("video-a"),
        lineId = SubtitleLineId(line),
        contextSentence = sentence,
        timestampMs = 1_240L,
        createdAt = capturedAt,
    )

    private suspend fun database(block: suspend (FamilyDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), FamilyDatabase::class.java,
        ).build()
        try {
            db.familyRepository().saveFamily(familyA)
            listOf(profileA, profileB).forEach { id ->
                db.profileRepository().saveProfile(familyA.id, id, sampleProfile(familyA, id.value))
            }
            block(db)
        } finally { db.close() }
    }
}
