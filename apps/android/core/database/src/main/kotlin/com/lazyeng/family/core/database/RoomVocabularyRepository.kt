package com.lazyeng.family.core.database

import androidx.room.withTransaction
import com.lazyeng.family.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

internal class RoomVocabularyRepository(
    private val db: FamilyDatabase,
) : com.lazyeng.family.core.model.VocabularyRepository {
    private val dao get() = db.vocabularyDao()

    override suspend fun saveWord(capture: VocabularyCapture): Result<Unit> = try {
        require(capture.surface.isNotBlank() && capture.lemma.isNotBlank())
        db.withTransaction {
            val item = VocabularyItemEntity(
                profileId = capture.profileId.value,
                lemma = normalizeLemma(capture.lemma),
                status = VocabularyStatus.LEARNING.name,
                stage = 0,
                nextReviewAtMs = null,
            )
            dao.insertItem(item)
            dao.insertContext(
                VocabularyContextEntity(
                    profileId = capture.profileId.value,
                    lemma = item.lemma,
                    surface = capture.surface,
                    phonetic = capture.phonetic,
                    definition = capture.definition,
                    senseId = null,
                    contextSentence = capture.contextSentence,
                    videoId = capture.videoId.value,
                    lineId = capture.lineId.value,
                    timestampMs = capture.timestampMs,
                    createdAtMs = capture.createdAt.toEpochMilli(),
                ),
            )
        }
        Result.success(Unit)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    override fun isWordSaved(profileId: ProfileId, lemma: String): Flow<Boolean> =
        dao.isSaved(profileId.value, normalizeLemma(lemma))

    override fun getWordsByProfile(profileId: ProfileId): Flow<List<VocabularyItem>> =
        dao.items(profileId.value).map { rows -> rows.map(VocabularyItemEntity::toDomain) }

    override suspend fun listSavedLemmas(profileId: ProfileId): Set<String> = dao.lemmas(profileId.value).toSet()

    override suspend fun deleteWord(profileId: ProfileId, id: Long) {
        db.withTransaction {
            dao.itemById(profileId.value, id)?.let { dao.deleteContexts(profileId.value, it.lemma) }
            dao.deleteItem(profileId.value, id)
        }
    }

    override suspend fun recordReview(
        profileId: ProfileId,
        attempt: ReviewAttempt,
        updatedItem: VocabularyItem,
    ): Boolean {
        require(attempt.profileId == profileId && attempt.vocabularyItem.profileId == profileId)
        require(updatedItem.profileId == profileId && updatedItem.lemma == attempt.vocabularyItem.lemma)
        return db.withTransaction {
            val inserted = dao.appendAttempt(
                ReviewAttemptEntity(
                    id = attempt.id.value,
                    eventUuid = attempt.eventUuid.value,
                    profileId = profileId.value,
                    lemma = attempt.vocabularyItem.lemma,
                    rating = attempt.rating.name,
                    occurredAtMs = attempt.occurredAt.toEpochMilli(),
                ),
            )
            if (inserted == -1L) return@withTransaction false
            val existing = dao.item(profileId.value, updatedItem.lemma)
                ?: error("Cannot record a review for a missing vocabulary item")
            dao.updateItem(updatedItem.toEntity().copy(id = existing.id))
            true
        }
    }

    override suspend fun listItems(profileId: ProfileId): List<VocabularyItem> =
        dao.items(profileId.value).first().map(VocabularyItemEntity::toDomain)

    override suspend fun getItem(profileId: ProfileId, lemma: String): VocabularyItem? =
        dao.item(profileId.value, normalizeLemma(lemma))?.toDomain()

    override suspend fun saveSnapshot(profileId: ProfileId, item: VocabularyItem) {
        require(item.profileId == profileId)
        val normalized = item.toEntity()
        val existing = dao.item(profileId.value, normalized.lemma)
        if (existing == null) dao.insertItem(normalized) else dao.updateItem(normalized.copy(id = existing.id))
    }

    override suspend fun deleteItem(profileId: ProfileId, lemma: String) {
        val item = dao.item(profileId.value, normalizeLemma(lemma)) ?: return
        deleteWord(profileId, item.id)
    }

    override suspend fun listContexts(profileId: ProfileId, lemma: String): List<VocabularyContext> =
        dao.contexts(profileId.value, normalizeLemma(lemma)).map(VocabularyContextEntity::toDomain)

    override suspend fun addContext(profileId: ProfileId, context: VocabularyContext) {
        require(context.vocabularyItem.profileId == profileId)
        val lemma = normalizeLemma(context.vocabularyItem.lemma)
        dao.insertContext(
            VocabularyContextEntity(
                id = context.id,
                profileId = profileId.value,
                lemma = lemma,
                surface = context.surface,
                phonetic = context.phonetic,
                definition = context.definition,
                senseId = context.senseId,
                contextSentence = context.contextSentence,
                videoId = context.videoId?.value.orEmpty(),
                lineId = context.lineId.value,
                timestampMs = context.timestampMs,
                createdAtMs = context.createdAt?.toEpochMilli() ?: 0L,
            ),
        )
    }

    private fun normalizeLemma(value: String): String = value.trim().lowercase(java.util.Locale.ROOT)
}

internal class RoomReviewAttemptRepository(private val db: FamilyDatabase) : ReviewAttemptRepository {
    override suspend fun append(profileId: ProfileId, attempt: ReviewAttempt) {
        require(attempt.profileId == profileId && attempt.vocabularyItem.profileId == profileId)
        db.vocabularyDao().appendAttempt(
            ReviewAttemptEntity(
                id = attempt.id.value,
                eventUuid = attempt.eventUuid.value,
                profileId = profileId.value,
                lemma = attempt.vocabularyItem.lemma,
                rating = attempt.rating.name,
                occurredAtMs = attempt.occurredAt.toEpochMilli(),
            ),
        )
    }

    override suspend fun listAttempts(profileId: ProfileId, lemma: String): List<ReviewAttempt> =
        db.vocabularyDao().attempts(profileId.value, lemma).map { row ->
            ReviewAttempt(
                id = ReviewAttemptId(row.id),
                eventUuid = EventUuid(row.eventUuid),
                profileId = ProfileId(row.profileId),
                vocabularyItem = VocabularyItemKey(ProfileId(row.profileId), row.lemma),
                rating = ReviewRating.valueOf(row.rating),
                occurredAt = Instant.ofEpochMilli(row.occurredAtMs),
            )
        }
}

private fun VocabularyItemEntity.toDomain() = VocabularyItem(
    profileId = ProfileId(profileId),
    lemma = lemma,
    status = VocabularyStatus.valueOf(status),
    nextReviewAt = nextReviewAtMs?.let(Instant::ofEpochMilli),
    id = id,
    stage = stage,
    reviewCount = reviewCount,
    lapseCount = lapseCount,
    consecutiveRemembered = consecutiveRemembered,
    lastReviewedAt = lastReviewedAtMs?.let(Instant::ofEpochMilli),
)

private fun VocabularyItem.toEntity() = VocabularyItemEntity(
    id = id,
    profileId = profileId.value,
    lemma = lemma.trim().lowercase(java.util.Locale.ROOT),
    status = status.name,
    stage = stage,
    nextReviewAtMs = nextReviewAt?.toEpochMilli(),
    reviewCount = reviewCount,
    lapseCount = lapseCount,
    consecutiveRemembered = consecutiveRemembered,
    lastReviewedAtMs = lastReviewedAt?.toEpochMilli(),
)

private fun VocabularyContextEntity.toDomain() = VocabularyContext(
    vocabularyItem = VocabularyItemKey(ProfileId(profileId), lemma),
    lineId = SubtitleLineId(lineId),
    senseId = senseId,
    timestampMs = timestampMs,
    id = id,
    surface = surface,
    phonetic = phonetic,
    definition = definition,
    videoId = videoId.takeIf(String::isNotEmpty)?.let(::VideoId),
    contextSentence = contextSentence,
    createdAt = Instant.ofEpochMilli(createdAtMs),
)
