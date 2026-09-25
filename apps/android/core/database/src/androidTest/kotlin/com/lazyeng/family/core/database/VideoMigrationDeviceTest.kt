package com.lazyeng.family.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the preserved exported v1 fixture, never the installed app's household database. */
@RunWith(AndroidJUnit4::class)
class VideoMigrationDeviceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FamilyDatabase::class.java)

    @Test fun v1ToV2PreservesHouseholdsAndValidatesExportedSchema() {
        val name = "migration-v1-v2-fixture"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO families VALUES ('fixture-family', '{}', '2026-01-01T00:00:00Z')")
            execSQL("""INSERT INTO profiles VALUES ('fixture-profile', 'fixture-family', 'Synthetic learner', NULL,
                'CHILD', 'child', 'Power Up 2', '{}', '2026-01-01T00:00:00Z', NULL)""")
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, VideoMigration.MIGRATION_1_2).use { db ->
            db.query("SELECT nickname, family_id, deleted_at FROM profiles WHERE id='fixture-profile'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Synthetic learner", it.getString(0))
                assertEquals("fixture-family", it.getString(1))
                assertTrue(it.isNull(2))
            }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            for (table in listOf("videos", "video_assets", "import_jobs", "watch_progress")) {
                db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
        }
    }
}
