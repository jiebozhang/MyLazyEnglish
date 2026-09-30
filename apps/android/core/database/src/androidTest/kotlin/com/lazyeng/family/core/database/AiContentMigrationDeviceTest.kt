package com.lazyeng.family.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiContentMigrationDeviceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FamilyDatabase::class.java)

    @Test fun v5ToV6PreservesExistingFamilyVideoSubtitleAndVocabularyOnDevice() {
        val name = "family-v5-v6-ai-content"
        helper.createDatabase(name, 5).use { db ->
            SubtitleMigrationFixture.seed { db.execSQL(it) }
            db.execSQL("INSERT INTO subtitle_tracks VALUES ('track-a', 'video-a', 'en', 'SRT', NULL)")
            db.execSQL("INSERT INTO subtitle_versions VALUES ('version-a', 'track-a', 'hash-a', 'parser-v1', 'PUBLISHED', 'UTF-8', '2026-09-01T00:00:00Z')")
            db.execSQL("INSERT INTO subtitle_lines VALUES ('line-a', 'version-a', 1, 100, 900, 'A fixture line.')")
            db.execSQL("INSERT INTO vocabulary_items (id, profile_id, lemma, status, stage, next_review_at_ms) VALUES (7, 'profile-a', 'run', 'LEARNING', 0, NULL)")
            db.execSQL("INSERT INTO vocabulary_contexts (id, profile_id, lemma, surface, context_sentence, video_id, line_id, timestamp_ms, created_at_ms) VALUES (9, 'profile-a', 'run', 'running', 'A fixture line.', 'video-a', 'line-a', 100, 200)")
            db.execSQL("INSERT INTO review_attempts VALUES ('review-a', 'event-a', 'profile-a', 'run', 'REMEMBERED', 300)")
        }
        helper.runMigrationsAndValidate(name, 6, true, AiContentMigration.MIGRATION_5_6).use { db ->
            db.query("SELECT COUNT(*) FROM profiles").use { assertTrue(it.moveToFirst()); assertTrue(it.getInt(0) >= 2) }
            db.query("SELECT COUNT(*) FROM videos").use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM subtitle_lines WHERE id='line-a'").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM vocabulary_items WHERE lemma='run'").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM ai_generated_content").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }
}
