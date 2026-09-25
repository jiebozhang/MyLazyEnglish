package com.lazyeng.family.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.lazyeng.family.core.model.*
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
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
class SubtitleMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun v2FilePreservesAllSixTablesAndReopensWithWorkingSubtitleStorage() = runTest {
        val file = File(temporary.root, "v2-v3.db")
        val before = createV2(file)
        val missing = Room.databaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java, file.absolutePath).build()
        try { assertTrue(runCatching { missing.openHelper.writableDatabase }.isFailure) } finally { missing.close() }
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(2, it.version)
            assertEquals(before, SubtitleMigrationFixture.snapshot { sql -> it.rawQuery(sql, null) })
        }
        val profile = ProfileId("profile-a")
        val track = SubtitleTrack(SubtitleTrackId("track"), VideoId("video-a"), "en", "SRT", null)
        val version = DraftSubtitleVersion(SubtitleVersionId("version"), track.id, "synthetic-hash", "srt-vtt-1", "UTF-8")
        val line = SubtitleLine(SubtitleLineId("line"), version.id, 1, 1250, 3500, "Synthetic cue.")
        val db = open(file)
        try {
            assertEquals(3, db.openHelper.writableDatabase.version)
            assertEquals(before, SubtitleMigrationFixture.snapshot { db.openHelper.writableDatabase.query(it) })
            db.subtitleRepository().saveTrack(profile, track)
            db.subtitleRepository().saveDraft(profile, version, listOf(line))
            assertNull(db.subtitleRepository().getTrack(ProfileId("profile-b"), track.id))
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close() }
        val reopened = open(file)
        try {
            assertEquals(version, reopened.subtitleRepository().getDraft(profile, version.id))
            assertEquals(listOf(line), reopened.subtitleRepository().listLines(profile, version.id, 0, 10))
            assertEquals(before, SubtitleMigrationFixture.snapshot { reopened.openHelper.writableDatabase.query(it) })
        } finally { reopened.close() }
    }
    private fun open(file: File) = Room.databaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java, file.absolutePath)
        .addMigrations(SubtitleMigration.MIGRATION_2_3).build()
    private fun createV2(file: File): Map<String, List<List<String?>>> {
        val schema = JSONObject(checkNotNull(javaClass.classLoader?.getResourceAsStream("com.lazyeng.family.core.database.FamilyDatabase/2.json"))
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        return SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            SubtitleMigrationFixture.seed { db.execSQL(it) }
            db.version = 2
            SubtitleMigrationFixture.snapshot { db.rawQuery(it, null) }
        }
    }
}
