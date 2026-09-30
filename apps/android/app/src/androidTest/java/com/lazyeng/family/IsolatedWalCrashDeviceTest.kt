package com.lazyeng.family

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.model.Family
import com.lazyeng.family.core.model.FamilyId
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Two externally orchestrated phases; only the dedicated fixture database is written. */
class IsolatedWalCrashDeviceTest {
    @Test fun holdUncommittedFixtureTransactionUntilProcessIsKilled(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("int3CrashPhase") == "hold")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pendingId = FamilyId("int3-pending-${UUID.randomUUID()}")
        context.openFileOutput(MARKER_FILE, 0).bufferedWriter().use { it.write(pendingId.value) }
        val db = Room.databaseBuilder(context, FamilyDatabase::class.java, DATABASE_NAME).build()
        db.familyRepository().saveFamily(Family(FamilyId(SEED_ID), emptyMap(), Instant.EPOCH))
        try {
            db.withTransaction {
                db.familyRepository().saveFamily(Family(pendingId, emptyMap(), Instant.EPOCH))
                println("INT3_WAL_PENDING isolated_transaction_open")
                Thread.sleep(600_000L)
                error("Crash fixture was not interrupted before the timeout")
            }
        } finally {
            db.close()
        }
    }

    @Test fun reopenFixtureAfterKillRetainsCommittedRowsAndRollsBackPendingRow() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("int3CrashPhase") == "recover")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pendingId = FamilyId(context.openFileInput(MARKER_FILE).bufferedReader().use { it.readText().trim() })
        val db = Room.databaseBuilder(context, FamilyDatabase::class.java, DATABASE_NAME).build()
        try {
            assertTrue(db.familyRepository().getFamily(FamilyId(SEED_ID)) != null)
            assertNull(db.familyRepository().getFamily(pendingId))
            db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
            }
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
            println("INT3_WAL_RECOVERY committed=retained pending=rolled_back integrity=ok foreign_keys=ok")
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DATABASE_NAME = "int3-isolated-wal.db"
        const val MARKER_FILE = "int3-isolated-wal-current.txt"
        const val SEED_ID = "int3-committed-fixture"
    }
}
