package com.lazyeng.family.core.model

import java.time.Instant

/** Deterministic MVP intervals from PRD 8.15; callers supply time instead of reading the system clock. */
object ReviewSchedule {
    fun apply(item: VocabularyItem, rating: ReviewRating, now: Instant): VocabularyItem {
        val nextStage: Int
        val status: VocabularyStatus
        val intervalMinutes: Long
        val remembered: Int
        val lapses = item.lapseCount + if (rating == ReviewRating.FORGOT) 1 else 0
        when (rating) {
            ReviewRating.FORGOT -> {
                nextStage = 0
                status = VocabularyStatus.LEARNING
                intervalMinutes = 10
                remembered = 0
            }
            ReviewRating.UNCERTAIN -> {
                nextStage = maxOf(1, item.stage)
                status = VocabularyStatus.REVIEW
                intervalMinutes = 24 * 60
                remembered = 0
            }
            ReviewRating.REMEMBERED -> {
                nextStage = (item.stage + 1).coerceAtMost(6)
                status = if (nextStage >= 3) VocabularyStatus.MASTERED else VocabularyStatus.REVIEW
                intervalMinutes = when (nextStage) {
                    1 -> 3 * 24 * 60L
                    2 -> 7 * 24 * 60L
                    3 -> 14 * 24 * 60L
                    else -> 14 * 24 * 60L * (1L shl (nextStage - 3).coerceAtMost(3))
                }
                remembered = item.consecutiveRemembered + 1
            }
        }
        return item.copy(
            status = status,
            stage = nextStage,
            nextReviewAt = now.plusSeconds(intervalMinutes * 60),
            reviewCount = item.reviewCount + 1,
            lapseCount = lapses,
            consecutiveRemembered = remembered,
            lastReviewedAt = now,
        )
    }

    fun isDue(item: VocabularyItem, now: Instant): Boolean =
        item.status != VocabularyStatus.PAUSED && (item.nextReviewAt == null || !item.nextReviewAt.isAfter(now))
}
