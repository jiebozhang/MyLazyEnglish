package com.lazyeng.family.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.model.EventUuid
import com.lazyeng.family.core.model.LookupEvent
import com.lazyeng.family.core.model.LookupEventId
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.SubtitleLineId
import com.lazyeng.family.core.model.VideoId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LookupEventMigrationDeviceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FamilyDatabase::class.java)

    @Test fun populatedV3DatabaseUpgradesAndEnforcesAppendOnlyDedupOnDevice() = runBlocking {
        val name = "migration-v3-v4-lookups"
        val before = helper.createDatabase(name, 3).use { db ->
            SubtitleMigrationFixture.seed { db.execSQL(it) }
            db.execSQL("INSERT INTO subtitle_tracks VALUES ('track-a', 'video-a', 'en', 'SRT', NULL)")
            db.execSQL("INSERT INTO subtitle_versions VALUES ('version-a', 'track-a', 'fixture-hash', 'fixture-parser', 'PUBLISHED', 'UTF-8', '2026-01-01T00:00:00Z')")
            db.execSQL("INSERT INTO subtitle_lines VALUES ('line-a', 'version-a', 1, 100, 500, 'A fixture line.')")
            LookupEventMigrationFixture.snapshot { db.query(it) }
        }
        helper.runMigrationsAndValidate(name, 4, true, LookupEventMigration.MIGRATION_3_4).use { db ->
            assertEquals(before, LookupEventMigrationFixture.snapshot { db.query(it) })
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, FamilyDatabase::class.java, name)
            .addMigrations(VideoMigration.MIGRATION_1_2, SubtitleMigration.MIGRATION_2_3,
                LookupEventMigration.MIGRATION_3_4, FamilyVocabularyMigration.MIGRATION_4_5, AiContentMigration.MIGRATION_5_6)
            .build()
        try {
            val repository = database.lookupEventRepository()
            val profile = ProfileId("profile-a")
            repeat(3) { index ->
                repository.append(profile, LookupEvent(
                    LookupEventId("lookup-$index"), EventUuid("uuid-$index"), profile,
                    "running", "run", SubtitleLineId("line-a"), VideoId("video-a"), Instant.parse("2026-01-02T00:00:00Z"),
                ))
            }
            assertEquals(1, repository.listEvents(profile, Instant.EPOCH, Instant.parse("2026-01-03T00:00:00Z")).size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
