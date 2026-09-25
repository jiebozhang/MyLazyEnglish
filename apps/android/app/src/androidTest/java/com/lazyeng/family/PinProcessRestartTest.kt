package com.lazyeng.family

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.model.FamilyId
import com.lazyeng.family.core.security.*
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run prepare -> adb am force-stop -> verify as separate instrumentation processes. */
@RunWith(AndroidJUnit4::class)
class PinProcessRestartTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getNoBackupFilesDir() = File(cacheDir, "pin-process-fixture").apply { mkdirs() }
    }
    private val family = FamilyId("process-restart-fixture")
    // A controlled timestamp makes the two-process assertion independent of operator delay.
    private val clock = Clock { Instant.parse("2026-01-01T00:00:00Z") }
    private fun phase(value: String) = assumeTrue(
        InstrumentationRegistry.getArguments().getString("pinProcessPhase") == value)

    @Test fun prepare() = runBlocking {
        phase("prepare")
        val store = KeystorePinRecordStore.create(context)
        assertNull("Remove only this test's fixture before repeating", store.read())
        val service = PinService(store, clock)
        val random = SecureRandom()
        val pin = CharArray(4) { ('0'.code + random.nextInt(10)).toChar() }
        val wrong = pin.map { ('0'.code + (it.digitToInt() + 1) % 10).toChar() }.toCharArray()
        try {
            service.setup(family, pin)
            repeat(5) { assertTrue(service.verify(family, wrong) is PinCheck.Rejected) }
            assertEquals(5, service.status(family).failures)
            assertEquals(clock.now().toEpochMilli() + 30_000, service.status(family).lockUntil)
            val file = File(context.noBackupFilesDir, "parent-pin.enc")
            assertTrue(file.length() > 0)
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(String(pin)))
        } finally { pin.fill('\u0000'); wrong.fill('\u0000') }
    }
    @Test fun verify() = runBlocking {
        phase("verify")
        val store = KeystorePinRecordStore.create(context)
        val service = PinService(store, clock)
        val status = service.status(family)
        assertTrue(status.configured)
        assertEquals(5, status.failures)
        assertEquals(clock.now().toEpochMilli() + 30_000, status.lockUntil)
        val dummy = CharArray(4) { ('0'.code + it).toChar() }
        try { assertTrue(service.verify(family, dummy) is PinCheck.Locked) }
        finally { dummy.fill('\u0000') }
        assertEquals(5, store.read()!!.failures)
    }
}
