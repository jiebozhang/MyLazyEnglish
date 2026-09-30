package com.lazyeng.family

import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.database.FamilyDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledDatabaseIntegrityDeviceTest {
    @Test fun existingDatabaseHasStableAggregateCountsAndNoIntegrityErrors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = FamilyDatabase.open(context)
        try {
            val sql = database.openHelper.readableDatabase
            val counts = listOf(
                "families", "profiles", "videos", "watch_progress", "subtitle_lines",
                "lookup_events", "vocabulary_items", "review_attempts",
            ).associateWith { table ->
                sql.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    assertTrue("Count query returned no row for $table", cursor.moveToFirst())
                    cursor.getLong(0)
                }
            }
            sql.query("PRAGMA integrity_check").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
            }
            sql.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse("Database has foreign-key violations", cursor.moveToFirst())
            }
            println("INT3_DATABASE_COUNTS ${counts.entries.joinToString { "${it.key}=${it.value}" }}")
        } finally {
            database.close()
        }
    }
}
