package com.lazyeng.family.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.lazyeng.family.core.model.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LookupEventMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun populatedV3UpgradesToV4PreservingFamilyVideoAndSubtitleRows() = runTest {
        val file = File(temporary.root, "v3-v4.db")
        val before = createV3(file)
        val db = open(file)
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(6, sqlite.version)
            assertEquals(before, LookupEventMigrationFixture.snapshot { sqlite.query(it) })
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }

            val repository = db.lookupEventRepository()
            repository.append(profileA, event("event-a", "uuid-a", profileA, "run"))
            repository.append(profileA, event("event-b", "uuid-b", profileA, "RUN", word = "running"))
            repository.append(profileB, event("event-c", "uuid-c", profileB, "run"))
            assertEquals(1, repository.listEvents(profileA, from, to).size)
            assertEquals(1, repository.listEvents(profileB, from, to).size)
            assertTrue(runCatching {
                repository.append(profileB, event("event-d", "uuid-d", profileA, "jump"))
            }.isFailure)
            assertEquals(1, repository.listEvents(profileA, from, to).size)
        } finally {
            db.close()
        }
        val reopened = open(file)
        try {
            assertEquals(6, reopened.openHelper.writableDatabase.version)
            assertEquals(before, LookupEventMigrationFixture.snapshot { reopened.openHelper.writableDatabase.query(it) })
            assertEquals(1, reopened.lookupEventRepository().listEvents(profileA, from, to).size)
        } finally {
            reopened.close()
        }
    }

    private fun open(file: File) = Room.databaseBuilder(
        RuntimeEnvironment.getApplication(), FamilyDatabase::class.java, file.absolutePath,
    ).addMigrations(VideoMigration.MIGRATION_1_2, SubtitleMigration.MIGRATION_2_3,
        LookupEventMigration.MIGRATION_3_4, FamilyVocabularyMigration.MIGRATION_4_5,
        AiContentMigration.MIGRATION_5_6).build()

    private fun createV3(file: File): Map<String, List<List<String?>>> {
        val schema = JSONObject(checkNotNull(javaClass.classLoader?.getResourceAsStream(
            "com.lazyeng.family.core.database.FamilyDatabase/3.json",
        )).bufferedReader().use { it.readText() }).getJSONObject("database")
        return SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            SubtitleMigrationFixture.seed { db.execSQL(it) }
            db.execSQL("INSERT INTO subtitle_tracks VALUES ('track-a', 'video-a', 'en', 'SRT', NULL)")
            db.execSQL("INSERT INTO subtitle_versions VALUES ('version-a', 'track-a', 'fixture-hash', 'fixture-parser', 'PUBLISHED', 'UTF-8', '2026-01-01T00:00:00Z')")
            db.execSQL("INSERT INTO subtitle_lines VALUES ('line-a', 'version-a', 1, 100, 500, 'A fixture line.')")
            db.execSQL("INSERT INTO subtitle_tracks VALUES ('track-b', 'video-b', 'en', 'SRT', NULL)")
            db.execSQL("INSERT INTO subtitle_versions VALUES ('version-b', 'track-b', 'fixture-hash-b', 'fixture-parser', 'PUBLISHED', 'UTF-8', '2026-01-01T00:00:00Z')")
            db.execSQL("INSERT INTO subtitle_lines VALUES ('line-b', 'version-b', 1, 600, 900, 'Another fixture line.')")
            db.version = 3
            LookupEventMigrationFixture.snapshot { db.rawQuery(it, null) }
        }
    }

    private fun event(id: String, uuid: String, profileId: ProfileId, lemma: String, word: String = lemma) = LookupEvent(
        LookupEventId(id), EventUuid(uuid), profileId, word, lemma, SubtitleLineId("line-a"), VideoId("video-a"), from.plusSeconds(1),
    )

    private companion object {
        val profileA = ProfileId("profile-a")
        val profileB = ProfileId("profile-b")
        val from = Instant.parse("2026-01-01T00:00:00Z")
        val to = Instant.parse("2026-01-03T00:00:00Z")
    }
}
