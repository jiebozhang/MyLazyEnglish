package com.lazyeng.family.core.security

import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.model.FamilyId
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PinServiceTest {
    private val family = FamilyId("test-family")
    private val synthetic = CharArray(4) { ('0'.code + it).toChar() }
    private val wrong = CharArray(4) { ('9'.code - it).toChar() }
    private val derive = PinDerivation { pin, _, _ -> ByteArray(32) { pin[it % pin.size].code.toByte() } }
    private class MemoryRecords : PinRecordStore {
        var record: PinRecord? = null
        override suspend fun read() = record
        override suspend fun write(record: PinRecord) { this.record = record }
    }

    @Test fun publishedPbkdf2Sha256Vector() {
        // Public algorithm vector (password/salt), not an application PIN or credential.
        val value = PlatformPbkdf2().derive("password".toCharArray(), "salt".toByteArray(), 1)
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            value.joinToString("") { "%02x".format(it) })
    }

    @Test fun parametersAreStoredAndExistingIterationCountIsUsed() = runTest {
        val records = MemoryRecords()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val service = PinService(records, Clock { Instant.EPOCH }, derive, dispatcher)
        service.setup(family, synthetic)
        val record = checkNotNull(records.record)
        assertEquals(PinPolicy.newRecordIterations, record.iterations)
        assertEquals(16, record.salt.size)
        assertEquals(1, record.version)
        assertEquals(PinPolicy.algorithm, record.algorithm)
        records.record = PinRecord(family, 1, record.algorithm, 220_000, record.salt, record.hash)
        var usedIterations = 0
        val observer = PinDerivation { pin, salt, iterations -> usedIterations = iterations; derive.derive(pin, salt, iterations) }
        assertEquals(PinCheck.Accepted, PinService(records, Clock { Instant.EPOCH }, observer, dispatcher).verify(family, synthetic))
        assertEquals(220_000, usedIterations)
    }

    @Test fun failureThresholdDoublingCapRestartAndSuccessReset() = runTest {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val records = MemoryRecords()
        val clock = Clock { now }
        val dispatcher = StandardTestDispatcher(testScheduler)
        fun service() = PinService(records, clock, derive, dispatcher)
        service().setup(family, synthetic)
        repeat(4) { assertEquals(PinCheck.Rejected(0), service().verify(family, wrong)) }
        for (delay in listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L)) {
            val result = service().verify(family, wrong) as PinCheck.Rejected
            assertEquals(delay, result.lockUntil - now.toEpochMilli())
            val before = service().status(family).failures
            assertEquals(PinCheck.Locked(result.lockUntil), service().verify(family, synthetic))
            assertEquals(before, service().status(family).failures)
            now = Instant.ofEpochMilli(result.lockUntil)
        }
        assertEquals(PinCheck.Accepted, service().verify(family, synthetic))
        assertEquals(PinStatus(true), service().status(family))
    }

    @Test fun interruptedDerivationLeavesAttemptDurableAndLockedCallsNeverHash() = runTest {
        val records = MemoryRecords()
        val clock = Clock { Instant.EPOCH }
        val dispatcher = StandardTestDispatcher(testScheduler)
        PinService(records, clock, derive, dispatcher).setup(family, synthetic)
        val crashing = PinService(records, clock, PinDerivation { _, _, _ -> error("test interruption") }, dispatcher)
        repeat(5) { assertTrue(runCatching { crashing.verify(family, wrong) }.isFailure) }
        assertEquals(5, checkNotNull(records.record).failures)
        assertEquals(PinCheck.Locked(30_000), crashing.verify(family, wrong))
    }

    @Test fun duplicateSetupWrongFamilyAndDamagedRecordFailClosed() = runTest {
        val records = MemoryRecords()
        val service = PinService(records, Clock { Instant.EPOCH }, derive, StandardTestDispatcher(testScheduler))
        service.setup(family, synthetic)
        assertTrue(runCatching { service.setup(family, wrong) }.isFailure)
        assertTrue(runCatching { service.verify(FamilyId("other"), synthetic) }.isFailure)
        val stored = checkNotNull(records.record)
        records.record = PinRecord(family, 1, stored.algorithm, 1, stored.salt, stored.hash)
        assertTrue(runCatching { service.status(family) }.isFailure)
    }

    @Test fun actualHashesUseIndependentRandomSalts() = runTest {
        val first = MemoryRecords()
        val second = MemoryRecords()
        PinService(first, Clock { Instant.EPOCH }).setup(family, synthetic)
        PinService(second, Clock { Instant.EPOCH }).setup(family, synthetic)
        assertFalse(first.record!!.salt.contentEquals(second.record!!.salt))
        assertFalse(first.record!!.hash.contentEquals(second.record!!.hash))
        assertEquals(PinCheck.Accepted, PinService(first, Clock { Instant.EPOCH }).verify(family, synthetic))
    }

    @Test fun failedAttemptReservationNeverRunsDerivation() = runTest {
        val records = MemoryRecords()
        val dispatcher = StandardTestDispatcher(testScheduler)
        PinService(records, Clock { Instant.EPOCH }, derive, dispatcher).setup(family, synthetic)
        val unavailable = object : PinRecordStore {
            override suspend fun read() = records.record
            override suspend fun write(record: PinRecord) { error("test disk failure") }
        }
        var calls = 0
        val service = PinService(unavailable, Clock { Instant.EPOCH },
            PinDerivation { pin, salt, count -> calls++; derive.derive(pin, salt, count) }, dispatcher)
        assertTrue(runCatching { service.verify(family, synthetic) }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun failedSuccessResetCannotGrantAccess() = runTest {
        val records = MemoryRecords()
        val dispatcher = StandardTestDispatcher(testScheduler)
        PinService(records, Clock { Instant.EPOCH }, derive, dispatcher).setup(family, synthetic)
        val resetFailure = object : PinRecordStore {
            override suspend fun read() = records.record
            override suspend fun write(record: PinRecord) {
                check(record.failures != 0) { "test reset failure" }
                records.write(record)
            }
        }
        val service = PinService(resetFailure, Clock { Instant.EPOCH }, derive, dispatcher)
        assertTrue(runCatching { service.verify(family, synthetic) }.isFailure)
        assertEquals(1, records.record!!.failures)
    }
}
