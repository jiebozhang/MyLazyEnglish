package com.lazyeng.family.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitleMigrationDeviceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FamilyDatabase::class.java)

    @Test fun populatedV2UpgradesToV3WithoutChangingAnyExistingValue() {
        val name = "migration-v2-v3-synthetic"
        val before = helper.createDatabase(name, 2).use { db ->
            SubtitleMigrationFixture.seed { db.execSQL(it) }
            SubtitleMigrationFixture.snapshot { db.query(it) }
        }
        helper.runMigrationsAndValidate(name, 3, true, SubtitleMigration.MIGRATION_2_3).use { db ->
            assertEquals(before, SubtitleMigrationFixture.snapshot { db.query(it) })
            for (table in listOf("subtitle_tracks", "subtitle_versions", "subtitle_lines")) {
                db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
            db.execSQL("INSERT INTO subtitle_tracks VALUES ('track', 'video-a', 'en', 'SRT', NULL)")
            db.execSQL("INSERT INTO subtitle_versions VALUES ('version', 'track', 'synthetic-hash', 'srt-vtt-1', 'DRAFT', 'UTF-8', NULL)")
            db.execSQL("INSERT INTO subtitle_lines VALUES ('line', 'version', 1, 1250, 3500, 'Synthetic cue.')")
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }
}
