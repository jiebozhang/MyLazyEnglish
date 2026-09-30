package com.lazyeng.family.core.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lazyeng.family.core.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Copies the former vocabulary.db into family.db once; the source is retained unless the target transaction commits. */
internal class LegacyVocabularyMigration(private val context: Context, private val target: FamilyDatabase) {
    private val mutex = Mutex()

    suspend fun ensureImported() = mutex.withLock {
        if (target.legacyMigrationDao().isComplete(MARKER)) {
            context.deleteDatabase(LEGACY_DATABASE)
            return@withLock
        }
        val sourceFile = context.getDatabasePath(LEGACY_DATABASE)
        val source = if (sourceFile.isFile) SQLiteDatabase.openDatabase(
            sourceFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
        ) else null
        try {
            target.withTransaction {
                if (source != null && source.hasTable("vocabulary_items")) {
                    check(target.openHelper.writableDatabase.count("vocabulary_items") == 0L &&
                        target.openHelper.writableDatabase.count("vocabulary_contexts") == 0L &&
                        target.openHelper.writableDatabase.count("review_attempts") == 0L) {
                        "Legacy vocabulary import must precede new vocabulary writes"
                    }
                    listOf("vocabulary_items", "vocabulary_contexts", "review_attempts").forEach { table ->
                        if (source.hasTable(table)) source.copyRowsTo(target.openHelper.writableDatabase, table)
                    }
                    target.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                        check(!cursor.moveToFirst()) { "Legacy vocabulary contains orphaned profile records" }
                    }
                }
                target.legacyMigrationDao().markComplete(LegacyMigrationMarkerEntity(MARKER))
            }
            if (source != null) {
                source.close()
                context.deleteDatabase(LEGACY_DATABASE)
            }
        } finally {
            source?.close()
        }
    }

    private fun SQLiteDatabase.hasTable(name: String): Boolean = rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name),
    ).use { it.moveToFirst() }

    private fun SupportSQLiteDatabase.count(table: String): Long = query("SELECT COUNT(*) FROM `$table`").use {
        check(it.moveToFirst())
        it.getLong(0)
    }

    private fun SQLiteDatabase.copyRowsTo(destination: SupportSQLiteDatabase, table: String) {
        rawQuery("SELECT * FROM `$table`", null).use { cursor ->
            while (cursor.moveToNext()) {
                val values = cursor.toContentValues()
                destination.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
            }
        }
    }

    private fun Cursor.toContentValues(): ContentValues = ContentValues(columnCount).also { values ->
        for (index in 0 until columnCount) {
            val column = getColumnName(index)
            when (getType(index)) {
                Cursor.FIELD_TYPE_NULL -> values.putNull(column)
                Cursor.FIELD_TYPE_INTEGER -> values.put(column, getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> values.put(column, getDouble(index))
                Cursor.FIELD_TYPE_STRING -> values.put(column, getString(index))
                Cursor.FIELD_TYPE_BLOB -> values.put(column, getBlob(index))
            }
        }
    }

    private companion object {
        const val LEGACY_DATABASE = "vocabulary.db"
        const val MARKER = "vocabulary-db-v2-imported"
    }
}

internal class LegacyMigratingVocabularyRepository(
    private val delegate: VocabularyRepository,
    private val migration: LegacyVocabularyMigration,
) : VocabularyRepository {
    override suspend fun listItems(profileId: ProfileId) = ready { delegate.listItems(profileId) }
    override suspend fun getItem(profileId: ProfileId, lemma: String) = ready { delegate.getItem(profileId, lemma) }
    override suspend fun saveSnapshot(profileId: ProfileId, item: VocabularyItem) = ready { delegate.saveSnapshot(profileId, item) }
    override suspend fun deleteItem(profileId: ProfileId, lemma: String) = ready { delegate.deleteItem(profileId, lemma) }
    override suspend fun listContexts(profileId: ProfileId, lemma: String) = ready { delegate.listContexts(profileId, lemma) }
    override suspend fun addContext(profileId: ProfileId, context: VocabularyContext) = ready { delegate.addContext(profileId, context) }
    override suspend fun saveWord(capture: VocabularyCapture) = ready { delegate.saveWord(capture) }
    override fun isWordSaved(profileId: ProfileId, lemma: String): Flow<Boolean> = flow {
        migration.ensureImported()
        emitAll(delegate.isWordSaved(profileId, lemma))
    }
    override fun getWordsByProfile(profileId: ProfileId): Flow<List<VocabularyItem>> = flow {
        migration.ensureImported()
        emitAll(delegate.getWordsByProfile(profileId))
    }
    override suspend fun listSavedLemmas(profileId: ProfileId) = ready { delegate.listSavedLemmas(profileId) }
    override suspend fun deleteWord(profileId: ProfileId, id: Long) = ready { delegate.deleteWord(profileId, id) }
    override suspend fun recordReview(profileId: ProfileId, attempt: ReviewAttempt, updatedItem: VocabularyItem) =
        ready { delegate.recordReview(profileId, attempt, updatedItem) }

    private suspend fun <T> ready(block: suspend () -> T): T {
        migration.ensureImported()
        return block()
    }
}
