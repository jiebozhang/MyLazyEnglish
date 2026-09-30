package com.lazyeng.family

import android.security.NetworkSecurityPolicy
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.model.Family
import com.lazyeng.family.core.model.FamilyId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicCleartextIntegrityDeviceTest {
    @Test fun publicCleartextIsBlockedAndIsolatedRoomTransactionRollsBack() = runBlocking {
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("api.example.com"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, FamilyDatabase::class.java).build()
        val familyId = FamilyId("int3-rollback-fixture")
        try {
            assertTrue(runCatching {
                db.withTransaction {
                    db.familyRepository().saveFamily(Family(familyId, emptyMap(), Instant.EPOCH))
                    error("synthetic interruption before commit")
                }
            }.isFailure)
            assertNull(db.familyRepository().getFamily(familyId))
            db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
            }
            println("INT3_ROOM_ROLLBACK isolated_transaction=rolled_back integrity=ok cleartext_public=blocked")
        } finally {
            db.close()
        }
    }
}
