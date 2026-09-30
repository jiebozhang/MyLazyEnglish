package com.lazyeng.family.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewScheduleTest {
    private val now = Instant.parse("2026-09-27T12:00:00Z")
    private val newWord = VocabularyItem(ProfileId("child-a"), "run", VocabularyStatus.LEARNING, null)

    @Test fun forgotSchedulesTenMinutesAndResetsRememberedRun() {
        val changed = ReviewSchedule.apply(newWord.copy(consecutiveRemembered = 2), ReviewRating.FORGOT, now)
        assertEquals(now.plusSeconds(600), changed.nextReviewAt)
        assertEquals(VocabularyStatus.LEARNING, changed.status)
        assertEquals(1, changed.lapseCount)
        assertEquals(0, changed.consecutiveRemembered)
        assertEquals(1, changed.reviewCount)
        assertEquals(now, changed.lastReviewedAt)
    }

    @Test fun uncertainIsTomorrowAndRememberedIntervalsGrowDeterministically() {
        assertEquals(now.plusSeconds(86_400), ReviewSchedule.apply(newWord, ReviewRating.UNCERTAIN, now).nextReviewAt)
        val first = ReviewSchedule.apply(newWord, ReviewRating.REMEMBERED, now)
        val second = ReviewSchedule.apply(first, ReviewRating.REMEMBERED, now)
        val third = ReviewSchedule.apply(second, ReviewRating.REMEMBERED, now)
        assertEquals(now.plusSeconds(3 * 86_400L), first.nextReviewAt)
        assertEquals(now.plusSeconds(7 * 86_400L), second.nextReviewAt)
        assertEquals(now.plusSeconds(14 * 86_400L), third.nextReviewAt)
        assertEquals(VocabularyStatus.MASTERED, third.status)
        assertEquals(3, third.stage)
    }

    @Test fun dueWordsIncludeUnscheduledNewItemsButExcludePausedAndFuture() {
        assertTrue(ReviewSchedule.isDue(newWord, now))
        assertFalse(ReviewSchedule.isDue(newWord.copy(nextReviewAt = now.plusSeconds(1)), now))
        assertFalse(ReviewSchedule.isDue(newWord.copy(status = VocabularyStatus.PAUSED), now))
    }
}
