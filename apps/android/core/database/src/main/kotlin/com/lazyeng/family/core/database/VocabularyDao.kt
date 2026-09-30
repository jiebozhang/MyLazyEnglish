package com.lazyeng.family.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class VocabularyDao {
    @Query("SELECT * FROM vocabulary_items WHERE profile_id = :profileId AND lemma = :lemma LIMIT 1")
    abstract suspend fun item(profileId: String, lemma: String): VocabularyItemEntity?

    @Query("SELECT * FROM vocabulary_items WHERE profile_id = :profileId AND id = :id LIMIT 1")
    abstract suspend fun itemById(profileId: String, id: Long): VocabularyItemEntity?

    @Query("SELECT * FROM vocabulary_items WHERE profile_id = :profileId ORDER BY id")
    abstract fun items(profileId: String): Flow<List<VocabularyItemEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM vocabulary_items WHERE profile_id = :profileId AND lemma = :lemma)")
    abstract fun isSaved(profileId: String, lemma: String): Flow<Boolean>

    @Query("SELECT lemma FROM vocabulary_items WHERE profile_id = :profileId")
    abstract suspend fun lemmas(profileId: String): List<String>

    @Query("SELECT * FROM vocabulary_contexts WHERE profile_id = :profileId AND lemma = :lemma ORDER BY created_at_ms, id")
    abstract suspend fun contexts(profileId: String, lemma: String): List<VocabularyContextEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertItem(item: VocabularyItemEntity): Long

    @Update
    abstract suspend fun updateItem(item: VocabularyItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertContext(context: VocabularyContextEntity): Long

    @Query("DELETE FROM vocabulary_items WHERE profile_id = :profileId AND id = :id")
    abstract suspend fun deleteItem(profileId: String, id: Long)

    @Query("DELETE FROM vocabulary_contexts WHERE profile_id = :profileId AND lemma = :lemma")
    abstract suspend fun deleteContexts(profileId: String, lemma: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun appendAttempt(attempt: ReviewAttemptEntity): Long

    @Query("SELECT * FROM review_attempts WHERE profile_id = :profileId AND lemma = :lemma ORDER BY occurred_at_ms, id")
    abstract suspend fun attempts(profileId: String, lemma: String): List<ReviewAttemptEntity>

    @Transaction
    open suspend fun saveCapture(item: VocabularyItemEntity, context: VocabularyContextEntity) {
        insertItem(item)
        insertContext(context)
    }
}
