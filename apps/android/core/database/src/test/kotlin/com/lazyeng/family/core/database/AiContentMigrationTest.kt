package com.lazyeng.family.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.lazyeng.family.core.model.*
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class AiContentMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun populatedV5DatabaseUpgradesToV6WithoutDroppingProfileLearningOrSubtitleData() = runTest {
        val file = File(temporary.root, "v5-v6.db")
        val oldRows = createV5(file)
        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java, file.absolutePath)
            .addMigrations(AiContentMigration.MIGRATION_5_6).build()
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(6, sqlite.version)
            assertEquals(oldRows, legacySnapshot { sqlite.query(it) })
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            assertEquals(0, sqlite.query("SELECT id FROM ai_generated_content").count)
            val repository = db.aiContentRepository()
            val family = FamilyId("family-a")
            val row = AiGeneratedContent(GeneratedContentId("content-fixture"), AiFeature.CONTEXT_EXPLANATION,
                CacheKey("cache-fixture"), "input-hash", "{\"meaning_zh\":\"跑\"}", "schema-v1", "1",
                GeneratedContentStatus.Ready, "safe")
            repository.saveContent(family, row)
            assertEquals(row, repository.findContent(family, row.cacheKey))
            assertEquals(null, repository.findContent(FamilyId("family-b"), row.cacheKey))
            db.aiJobRepository().saveJob(family, AiJob(AiJobId("job-fixture"), AiFeature.CONTEXT_EXPLANATION,
                AiJobStatus.Unknown("SUCCEEDED"), 100f, null, "SMALL"))
            assertEquals("SUCCEEDED", (db.aiJobRepository().getJob(family, AiJobId("job-fixture"))?.status as AiJobStatus.Unknown).raw)
            db.aiBudgetUsageRepository().configure(family, "DAILY", "2026-09-28", 10, null)
            db.aiBudgetUsageRepository().record(family, "DAILY", "2026-09-28", "SMALL", false)
            assertEquals(1, db.aiBudgetUsageRepository().read(family, "DAILY", "2026-09-28")?.requestCount)
        } finally { db.close() }
    }

    private fun createV5(file: File): Map<String, List<List<String?>>> {
        val root = JSONObject(checkNotNull(javaClass.classLoader?.getResourceAsStream(
            "com.lazyeng.family.core.database.FamilyDatabase/5.json",
        )).bufferedReader().use { it.readText() }).getJSONObject("database")
        return SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = root.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = root.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            SubtitleMigrationFixture.seed { db.execSQL(it) }
            db.execSQL("INSERT INTO subtitle_tracks VALUES ('track-a', 'video-a', 'en', 'SRT', NULL)")
            db.execSQL("INSERT INTO subtitle_versions VALUES ('version-a', 'track-a', 'hash-a', 'parser-v1', 'PUBLISHED', 'UTF-8', '2026-09-01T00:00:00Z')")
            db.execSQL("INSERT INTO subtitle_lines VALUES ('line-a', 'version-a', 1, 100, 900, 'She is running.')")
            db.execSQL("UPDATE subtitle_tracks SET current_version_id='version-a' WHERE id='track-a'")
            db.execSQL("INSERT INTO lookup_events VALUES ('lookup-a', 'event-a', 'profile-a', 'running', 'run', 'line-a', 'video-a', '2026-09-01T00:00:01Z')")
            db.execSQL("INSERT INTO vocabulary_items (id, profile_id, lemma, status, stage, next_review_at_ms) VALUES (7, 'profile-a', 'run', 'LEARNING', 0, NULL)")
            db.execSQL("INSERT INTO vocabulary_contexts (id, profile_id, lemma, surface, context_sentence, video_id, line_id, timestamp_ms, created_at_ms) VALUES (9, 'profile-a', 'run', 'running', 'She is running.', 'video-a', 'line-a', 100, 200)")
            db.execSQL("INSERT INTO review_attempts VALUES ('review-a', 'review-event-a', 'profile-a', 'run', 'REMEMBERED', 300)")
            db.version = 5
            legacySnapshot { sql -> db.rawQuery(sql, null) }
        }
    }

    private fun legacySnapshot(query: (String) -> android.database.Cursor): Map<String, List<List<String?>>> =
        listOf("families", "profiles", "videos", "video_assets", "import_jobs", "watch_progress", "subtitle_tracks",
            "subtitle_versions", "subtitle_lines", "lookup_events", "vocabulary_items", "vocabulary_contexts", "review_attempts", "legacy_migration_markers")
            .associateWith { table -> query("SELECT * FROM $table ORDER BY 1").use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
            } }
}
