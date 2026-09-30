package com.lazyeng.family.core.database

import androidx.room.Room
import com.lazyeng.family.core.model.Family
import com.lazyeng.family.core.model.FamilyId
import com.lazyeng.family.core.model.Profile
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.ProfileRole
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

internal val createdAt: Instant = Instant.parse("2026-01-01T00:00:00.123456789Z")
internal val familyA = Family(FamilyId("fixture-family-a"), mapOf("language" to "example"), createdAt)
internal val familyB = familyA.copy(id = FamilyId("fixture-family-b"))
internal fun sampleProfile(family: Family = familyA, id: String = "fixture-profile-a") = Profile(
    ProfileId(id), family.id, "Fixture Learner", null, ProfileRole.CHILD, "child",
    "Power Up 2", mapOf("example" to "quotes: \"line\"\nvalue"), createdAt, null,
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FamilyRepositoryTest {
    private lateinit var database: FamilyDatabase
    private val profiles get() = database.profileRepository()
    private val families get() = database.familyRepository()

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() = database.close()

    @Test fun familyAndAllProfileFieldsRoundTripWithoutLosingTimestampPrecision() = runTest {
        families.saveFamily(familyA)
        val profile = sampleProfile().copy(avatarId = "fixture-avatar", role = ProfileRole.ADULT)
        profiles.saveProfile(familyA.id, profile.id, profile)
        assertEquals(familyA, families.getFamily(familyA.id))
        assertEquals(profile, profiles.getProfile(familyA.id, profile.id))
        assertNull(families.getFamily(FamilyId("absent-family")))
    }

    @Test fun crossFamilyAndUnknownProfileQueriesReturnNull() = runTest {
        seed()
        val first = sampleProfile()
        val second = sampleProfile(familyB, "fixture-profile-b")
        assertNull(profiles.getProfile(familyA.id, second.id))
        assertNull(profiles.getProfile(familyB.id, first.id))
        assertNull(profiles.getProfile(familyA.id, ProfileId("absent-profile")))
        assertEquals(first, profiles.getProfile(familyA.id, first.id))
        assertEquals(second, profiles.getProfile(familyB.id, second.id))
    }

    @Test fun editingOneFamilyAndProfileLeavesOtherFamilyAndSiblingUntouched() = runTest {
        seed()
        val sibling = sampleProfile(id = "fixture-sibling")
        profiles.saveProfile(familyA.id, sibling.id, sibling)
        val edited = sampleProfile().copy(nickname = "Updated Fixture", englishLevel = "example-level",
            role = ProfileRole.PARENT, ageMode = "adult", avatarId = "fixture-icon", uiPreferences = emptyMap())
        profiles.saveProfile(familyA.id, edited.id, edited)
        families.saveFamily(familyA.copy(settings = mapOf("example" to "updated")))
        assertEquals(edited, profiles.getProfile(familyA.id, edited.id))
        assertEquals(sibling, profiles.getProfile(familyA.id, sibling.id))
        assertEquals(sampleProfile(familyB, "fixture-profile-b"), profiles.getProfile(familyB.id, ProfileId("fixture-profile-b")))
        assertEquals(familyB, families.getFamily(familyB.id))
    }

    @Test fun crossFamilyDeleteAndMissingDeleteAreNoOps() = runTest {
        seed()
        val target = sampleProfile()
        profiles.deleteProfile(familyB.id, target.id, createdAt.plusSeconds(20))
        profiles.deleteProfile(familyA.id, ProfileId("missing"), createdAt.plusSeconds(20))
        assertEquals(target, profiles.getProfile(familyA.id, target.id))
    }

    @Test fun deletionRetainsFirstTombstoneAndLeavesOtherProfilesUntouched() = runTest {
        seed()
        val target = sampleProfile()
        val sibling = sampleProfile(id = "fixture-sibling")
        profiles.saveProfile(familyA.id, sibling.id, sibling)
        val deletedAt = createdAt.plusSeconds(60)
        profiles.deleteProfile(familyA.id, target.id, deletedAt)
        profiles.deleteProfile(familyA.id, target.id, deletedAt.plusSeconds(60))
        assertNull(profiles.getProfile(familyA.id, target.id))
        assertEquals(target.copy(deletedAt = deletedAt), database.profileDao().getRecord(familyA.id.value, target.id.value)?.toDomain())
        assertEquals(sibling, profiles.getProfile(familyA.id, sibling.id))
        assertNotNull(profiles.getProfile(familyB.id, ProfileId("fixture-profile-b")))
    }

    @Test fun saveCannotReassignIdToAnotherFamilyOrResurrectTombstone() = runTest {
        seed()
        val target = sampleProfile()
        assertTrue(runCatching { profiles.saveProfile(familyB.id, target.id, target) }.isFailure)
        assertTrue(runCatching { profiles.saveProfile(familyB.id, target.id, target.copy(familyId = familyB.id)) }.isFailure)
        assertTrue(runCatching { profiles.saveProfile(familyA.id, ProfileId("wrong-id"), target) }.isFailure)
        assertEquals(target, profiles.getProfile(familyA.id, target.id))
        profiles.deleteProfile(familyA.id, target.id, createdAt.plusSeconds(1))
        assertTrue(runCatching { profiles.saveProfile(familyA.id, target.id, target) }.isFailure)
        assertNull(profiles.getProfile(familyA.id, target.id))
    }

    @Test fun orphanProfileIsRejectedByForeignKey() = runTest {
        val target = sampleProfile()
        assertTrue(runCatching { profiles.saveProfile(familyA.id, target.id, target) }.isFailure)
        assertNull(profiles.getProfile(familyA.id, target.id))
    }

    @Test fun creationTimeAndDeletionOrderAreProtected() = runTest {
        seed()
        val target = sampleProfile()
        assertTrue(runCatching { families.saveFamily(familyA.copy(createdAt = createdAt.plusSeconds(1))) }.isFailure)
        assertTrue(runCatching { profiles.saveProfile(familyA.id, target.id, target.copy(createdAt = createdAt.plusSeconds(1))) }.isFailure)
        assertTrue(runCatching { profiles.deleteProfile(familyA.id, target.id, createdAt.minusSeconds(1)) }.isFailure)
        assertTrue(runCatching { profiles.saveProfile(familyA.id, target.id, target.copy(deletedAt = createdAt)) }.isFailure)
        assertEquals(target, profiles.getProfile(familyA.id, target.id))
    }

    @Test fun racingEditAndDeleteNeverRestoresDeletedProfile() = runTest {
        seed()
        val target = sampleProfile()
        val edit = async { runCatching {
            profiles.saveProfile(familyA.id, target.id, target.copy(nickname = "Concurrent Fixture"))
        } }
        val deletion = async { profiles.deleteProfile(familyA.id, target.id, createdAt.plusSeconds(1)) }
        edit.await()
        deletion.await()
        assertNull(profiles.getProfile(familyA.id, target.id))
        assertEquals(createdAt.plusSeconds(1), database.profileDao().getRecord(familyA.id.value, target.id.value)?.deletedAt)
    }

    @Test fun v1SchemaHasExactlyPrdColumnsAndNoCurrentProfileOrPinTable() {
        val sql = database.openHelper.writableDatabase
        fun columns(table: String): Set<String> = sql.query("PRAGMA table_info($table)").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        assertEquals(setOf("id", "settings", "created_at"), columns("families"))
        assertEquals(setOf("id", "family_id", "nickname", "avatar_id", "role", "age_mode", "english_level",
            "ui_preferences", "created_at", "deleted_at"), columns("profiles"))
        val schemaText = checkNotNull(javaClass.classLoader?.getResourceAsStream(
            "com.lazyeng.family.core.database.FamilyDatabase/1.json",
        )).bufferedReader().use { it.readText() }
        val schema = JSONObject(schemaText).getJSONObject("database")
        assertEquals(1, schema.getInt("version"))
        val entities = schema.getJSONArray("entities")
        assertEquals(setOf("families", "profiles"), (0 until entities.length()).map {
            entities.getJSONObject(it).getString("tableName")
        }.toSet())
        sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    private suspend fun seed() {
        families.saveFamily(familyA)
        families.saveFamily(familyB)
        for (profile in listOf(sampleProfile(), sampleProfile(familyB, "fixture-profile-b"))) {
            profiles.saveProfile(profile.familyId, profile.id, profile)
        }
    }
}
