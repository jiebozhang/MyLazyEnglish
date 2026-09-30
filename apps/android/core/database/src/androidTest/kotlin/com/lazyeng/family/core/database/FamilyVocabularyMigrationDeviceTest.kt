package com.lazyeng.family.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FamilyVocabularyMigrationDeviceTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FamilyDatabase::class.java)

    @Test
    fun v4ToV5AddsProfileScopedVocabularyTablesWithoutLosingFamilyData() {
        val name = "family-v4-v5-profile-vocabulary"
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO families (id, settings, created_at) VALUES ('family-fixture', '{}', '2026-09-27T00:00:00Z')")
            execSQL("INSERT INTO profiles (id, family_id, nickname, avatar_id, role, age_mode, english_level, ui_preferences, created_at, deleted_at) VALUES ('child-fixture', 'family-fixture', 'Fixture', NULL, 'CHILD', 'child', 'A1', '{}', '2026-09-27T00:00:00Z', NULL)")
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true, FamilyVocabularyMigration.MIGRATION_4_5).use { db ->
            db.execSQL("INSERT INTO vocabulary_items (id, profile_id, lemma, status, stage, next_review_at_ms) VALUES (7, 'child-fixture', 'run', 'REVIEW', 1, 123456)")
            db.execSQL("INSERT INTO vocabulary_contexts (id, profile_id, lemma, surface, phonetic, definition, sense_id, context_sentence, video_id, line_id, timestamp_ms, created_at_ms) VALUES (9, 'child-fixture', 'run', 'running', '/rʌn/', '跑', NULL, 'She is running.', 'video-fixture', 'line-fixture', 1234, 1000)")
            db.execSQL("INSERT INTO review_attempts (id, event_uuid, profile_id, lemma, rating, occurred_at_ms) VALUES ('attempt-fixture', 'event-fixture', 'child-fixture', 'run', 'REMEMBERED', 1100)")
            db.query("SELECT id FROM profiles WHERE id='child-fixture'").use { assertTrue(it.moveToFirst()) }
            db.query("SELECT profile_id, lemma, review_count FROM vocabulary_items WHERE id=7").use {
                assertTrue(it.moveToFirst())
                assertEquals("child-fixture", it.getString(0))
                assertEquals("run", it.getString(1))
                assertEquals(0, it.getInt(2))
            }
            db.query("SELECT context_sentence FROM vocabulary_contexts WHERE id=9").use {
                assertTrue(it.moveToFirst())
                assertEquals("She is running.", it.getString(0))
            }
            db.query("SELECT event_uuid FROM review_attempts WHERE id='attempt-fixture'").use {
                assertTrue(it.moveToFirst())
                assertEquals("event-fixture", it.getString(0))
            }
            db.query("PRAGMA foreign_key_check").use { assertTrue(!it.moveToFirst()) }
        }
    }
}
