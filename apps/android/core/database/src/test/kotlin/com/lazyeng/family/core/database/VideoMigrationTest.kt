package com.lazyeng.family.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.lazyeng.family.core.model.*
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.json.JSONArray
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
class VideoMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realV1FileUpgradesThroughRoomAndPreservesEveryExistingField() = runTest {
        val file = File(temporary.root, "upgrade.db")
        createV1(file)
        val db = open(file)
        try {
            // The original v1 fixture now upgrades through both migrations to the current schema.
            assertEquals(3, db.openHelper.writableDatabase.version)
            assertEquals(familyA, db.familyRepository().getFamily(familyA.id))
            assertEquals(familyB, db.familyRepository().getFamily(familyB.id))
            assertEquals(sampleProfile(), db.profileRepository().getProfile(familyA.id, sampleProfile().id))
            assertEquals(sampleProfile(familyB, "fixture-b"), db.profileRepository().getProfile(familyB.id, ProfileId("fixture-b")))
            val deleted = sampleProfile(id = "fixture-deleted").copy(deletedAt = createdAt.plusSeconds(42))
            assertEquals(deleted, db.profileDao().getRecord(familyA.id.value, deleted.id.value)?.toDomain())
            assertNull(db.profileRepository().getProfile(familyA.id, deleted.id))
            assertNull(db.profileRepository().getProfile(familyB.id, sampleProfile().id))
            val tables = db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue(tables.containsAll(listOf("families", "profiles", "videos", "video_assets", "import_jobs", "watch_progress")))
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            val v = sampleVideo()
            db.videoRepository().saveVideo(sampleProfile().id, v)
            db.watchProgressRepository().saveProgress(sampleProfile().id, WatchProgress(sampleProfile().id, v.id, 123, 0.1f, createdAt))
            assertEquals(v, db.videoRepository().getVideo(sampleProfile().id, v.id))
        } finally { db.close() }
        val reopened = open(file)
        try { assertEquals(123L, reopened.watchProgressRepository().getProgress(sampleProfile().id, sampleVideo().id)?.positionMs) }
        finally { reopened.close() }
    }

    @Test fun missingMigrationRefusesUpgradeWithoutDeletingV1Data() = runTest {
        val file = File(temporary.root, "missing-migration.db")
        createV1(file)
        val missing = Room.databaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java, file.absolutePath).build()
        try { assertTrue(runCatching { missing.openHelper.writableDatabase }.isFailure) } finally { missing.close() }
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(1, it.version) }
        val recovered = open(file)
        try { assertEquals(sampleProfile(), recovered.profileRepository().getProfile(familyA.id, sampleProfile().id)) }
        finally { recovered.close() }
    }

    private fun open(file: File) = Room.databaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java,
        file.absolutePath).addMigrations(VideoMigration.MIGRATION_1_2, SubtitleMigration.MIGRATION_2_3).build()

    private fun createV1(file: File) {
        val source = checkNotNull(javaClass.classLoader?.getResourceAsStream("com.lazyeng.family.core.database.FamilyDatabase/1.json"))
            .bufferedReader().use { it.readText() }
        val schema = JSONObject(source).getJSONObject("database")
        assertEquals(1, schema.getInt("version"))
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
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
            val converter = FamilyConverters()
            for (family in listOf(familyA, familyB)) db.execSQL("INSERT INTO families VALUES (?, ?, ?)",
                arrayOf(family.id.value, converter.encodeMap(family.settings), family.createdAt.toString()))
            for (profile in listOf(sampleProfile(), sampleProfile(familyB, "fixture-b"),
                sampleProfile(id = "fixture-deleted").copy(deletedAt = createdAt.plusSeconds(42)))) {
                db.execSQL("INSERT INTO profiles VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf(profile.id.value,
                    profile.familyId.value, profile.nickname, profile.avatarId, profile.role.name, profile.ageMode,
                    profile.englishLevel, converter.encodeMap(profile.uiPreferences), profile.createdAt.toString(), profile.deletedAt?.toString()))
            }
            db.version = 1
        }
    }
}
