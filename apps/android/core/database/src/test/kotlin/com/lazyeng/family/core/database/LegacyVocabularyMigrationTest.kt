package com.lazyeng.family.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.lazyeng.family.core.model.Family
import com.lazyeng.family.core.model.FamilyId
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.VocabularyStatus
import com.lazyeng.family.core.model.Profile
import com.lazyeng.family.core.model.ProfileRole
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LegacyVocabularyMigrationTest {
    @Test fun legacyVocabularyIsImportedAtomicallyAndSourceIsRemovedOnlyAfterCommit() = runTest {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("vocabulary.db")
        val profileId = ProfileId("legacy-child")
        createLegacyDatabase(context.getDatabasePath("vocabulary.db"), profileId.value)
        val target = Room.inMemoryDatabaseBuilder(context, FamilyDatabase::class.java).build()
        try {
            createProfile(target, profileId)
            val migration = LegacyVocabularyMigration(context, target)
            migration.ensureImported()
            migration.ensureImported()

            val vocabulary = target.vocabularyRepository()
            val item = vocabulary.getItem(profileId, "run")
            assertNotNull(item)
            assertEquals(VocabularyStatus.REVIEW, item?.status)
            assertEquals(123456L, item?.nextReviewAt?.toEpochMilli())
            assertEquals("She is running home.", vocabulary.listContexts(profileId, "run").single().contextSentence)
            assertEquals(1, target.reviewAttemptRepository().listAttempts(profileId, "run").size)
            assertFalse(context.getDatabasePath("vocabulary.db").exists())
            assertTrue(target.legacyMigrationDao().isComplete("vocabulary-db-v2-imported"))
        } finally {
            target.close()
            context.deleteDatabase("vocabulary.db")
        }
    }

    @Test fun failedLegacyImportRollsBackAllRowsAndRetainsSourceForRetry() = runTest {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("vocabulary.db")
        createLegacyDatabase(context.getDatabasePath("vocabulary.db"), "missing-profile")
        val target = Room.inMemoryDatabaseBuilder(context, FamilyDatabase::class.java).build()
        try {
            val migration = LegacyVocabularyMigration(context, target)
            assertTrue(runCatching { migration.ensureImported() }.isFailure)
            assertFalse(target.legacyMigrationDao().isComplete("vocabulary-db-v2-imported"))
            assertEquals(0, target.vocabularyRepository().listItems(ProfileId("missing-profile")).size)
            assertTrue(context.getDatabasePath("vocabulary.db").exists())
            createProfile(target, ProfileId("missing-profile"))
            migration.ensureImported()
            assertNotNull(target.vocabularyRepository().getItem(ProfileId("missing-profile"), "run"))
        } finally {
            target.close()
            context.deleteDatabase("vocabulary.db")
        }
    }

    private suspend fun createProfile(db: FamilyDatabase, id: ProfileId) {
        val family = Family(FamilyId("legacy-family"), emptyMap(), Instant.EPOCH)
        db.familyRepository().saveFamily(family)
        db.profileRepository().saveProfile(family.id, id, Profile(
            id, family.id, "Fixture", null, ProfileRole.CHILD, "child", "A1", emptyMap(), Instant.EPOCH, null,
        ))
    }

    private fun createLegacyDatabase(file: java.io.File, profileId: String) {
        val schemaText = checkNotNull(javaClass.classLoader?.getResourceAsStream(
            "com.lazyeng.family.core.database.VocabularyDatabase/2.json",
        )).bufferedReader().use { it.readText() }
        val database = JSONObject(schemaText).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            database.getJSONArray("setupQueries").let { setup ->
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            }
            db.execSQL("INSERT INTO vocabulary_items (profile_id, lemma, status, stage, next_review_at_ms) VALUES (?, 'run', 'REVIEW', 2, 123456)", arrayOf(profileId))
            db.execSQL("INSERT INTO vocabulary_contexts (profile_id, lemma, surface, context_sentence, video_id, line_id, timestamp_ms, created_at_ms) VALUES (?, 'run', 'running', 'She is running home.', 'video', 'line', 42, 10)", arrayOf(profileId))
            db.execSQL("INSERT INTO review_attempts (id, event_uuid, profile_id, lemma, rating, occurred_at_ms) VALUES ('attempt', 'event', ?, 'run', 'REMEMBERED', 20)", arrayOf(profileId))
            db.version = database.getInt("version")
        }
    }
}
