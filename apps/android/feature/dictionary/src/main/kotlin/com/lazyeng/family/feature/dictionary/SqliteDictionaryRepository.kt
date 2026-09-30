package com.lazyeng.family.feature.dictionary

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.model.DictionaryEntry
import com.lazyeng.family.core.model.DictionaryRepository
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.withContext

/** Reads the separately-versioned dictionary package without exposing SQLite cursors to callers. */
class SqliteDictionaryRepository(
    private val databaseFile: File,
    private val dispatchers: DispatcherProvider,
) : DictionaryRepository, Closeable {
    @Volatile
    private var database: SQLiteDatabase? = null

    override suspend fun findEntry(lemma: String): DictionaryEntry? = withContext(dispatchers.io) {
        val normalized = lemma.trim()
        if (normalized.isEmpty()) return@withContext null
        val db = openDatabase()
        db.rawQuery(
            "SELECT word, phonetic, translation, pos FROM dictionary WHERE word = ? COLLATE NOCASE LIMIT 1",
            arrayOf(normalized),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            DictionaryEntry(
                lemma = cursor.getString(0),
                partOfSpeech = cursor.getString(3).takeUnless(String::isBlank),
                phonetic = cursor.getString(1).takeUnless(String::isBlank),
                senses = cursor.getString(2).lineSequence().map(String::trim).filter(String::isNotEmpty).toList(),
                cefr = null,
                frequencySource = null,
            )
        }
    }

    @Synchronized
    private fun openDatabase(): SQLiteDatabase {
        database?.takeIf(SQLiteDatabase::isOpen)?.let { return it }
        check(databaseFile.isFile) { "Dictionary database is not installed" }
        val opened = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        if (opened.version != EXPECTED_SCHEMA_VERSION) {
            opened.close()
            throw SQLiteException("Unsupported dictionary schema version")
        }
        database = opened
        return opened
    }

    @Synchronized
    override fun close() {
        database?.close()
        database = null
    }

    private companion object {
        const val EXPECTED_SCHEMA_VERSION = 1
    }
}
