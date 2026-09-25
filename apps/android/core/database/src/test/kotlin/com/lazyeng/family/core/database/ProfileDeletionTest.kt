package com.lazyeng.family.core.database

import androidx.room.Room
import com.lazyeng.family.core.common.ProfileDeletionCoordinator
import com.lazyeng.family.core.model.ProfileDeletionRequested
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileDeletionTest {
    @Test fun directoryIsScopedAndExcludesTombstones() = runTest {
        database { db ->
            db.familyRepository().saveFamily(familyB)
            val other = sampleProfile(familyB, "other-fixture")
            db.profileRepository().saveProfile(familyB.id, other.id, other)
            assertEquals(listOf(sampleProfile().id), db.profileDirectory().choices(familyA.id).map { it.id })
            db.profileRepository().deleteProfile(familyA.id, sampleProfile().id, createdAt.plusSeconds(1))
            assertTrue(db.profileDirectory().choices(familyA.id).isEmpty())
            assertEquals(listOf(other.id), db.profileDirectory().choices(familyB.id).map { it.id })
        }
    }

    @Test fun failureInLaterCleanerRollsBackBodyAndPriorCleanerWrites() = runTest {
        database { db ->
            val coordinator = coordinator(db)
            coordinator.register("other-transactional-work") {
                db.familyRepository().saveFamily(familyA.copy(settings = mapOf("fixture" to "changed")))
            }
            coordinator.register("failing-fixture") { error("synthetic failure") }
            assertTrue(runCatching { coordinator.request(request) }.isFailure)
            assertEquals(sampleProfile(), db.profileRepository().getProfile(familyA.id, sampleProfile().id))
            assertEquals(familyA, db.familyRepository().getFamily(familyA.id))
        }
    }

    @Test fun cancellationAfterBodyAndExpiredAuthorizationRollBack() = runTest {
        database { db ->
            val entered = CompletableDeferred<Unit>()
            val coordinator = coordinator(db)
            coordinator.register("suspended-fixture") { entered.complete(Unit); awaitCancellation() }
            val job = launch { coordinator.request(request) }
            entered.await(); job.cancelAndJoin()
            assertEquals(sampleProfile(), db.profileRepository().getProfile(familyA.id, sampleProfile().id))
            var checks = 0
            assertTrue(runCatching { coordinator(db).request(request) { check(++checks == 1) } }.isFailure)
            assertEquals(sampleProfile(), db.profileRepository().getProfile(familyA.id, sampleProfile().id))
        }
    }

    @Test fun multipleRegisteredCleanersCommitOnceAndRejectDuplicateNames() = runTest {
        database { db ->
            val coordinator = coordinator(db)
            val seen = mutableListOf<ProfileDeletionRequested>()
            coordinator.register("observer-fixture") { seen += it }
            assertTrue(runCatching { coordinator.register("profile-body") {} }.isFailure)
            coordinator.request(request)
            assertEquals(listOf(request), seen)
            assertNull(db.profileRepository().getProfile(familyA.id, sampleProfile().id))
            assertEquals(request.requestedAt, db.profileDao().getRecord(familyA.id.value, sampleProfile().id.value)?.deletedAt)
        }
    }

    private val request = ProfileDeletionRequested(familyA.id, sampleProfile().id, createdAt.plusSeconds(1))
    private fun coordinator(db: FamilyDatabase) = ProfileDeletionCoordinator(db.profileDeletionTransaction()).apply {
        register("profile-body") { db.profileRepository().deleteProfile(it.familyId, it.profileId, it.requestedAt) }
    }
    private suspend fun database(block: suspend (FamilyDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FamilyDatabase::class.java).build()
        try {
            db.familyRepository().saveFamily(familyA)
            db.profileRepository().saveProfile(familyA.id, sampleProfile().id, sampleProfile())
            block(db)
        } finally { db.close() }
    }
}
