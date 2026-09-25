package com.lazyeng.family.core.security

import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.model.FamilyId
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ParentSessionTest {
    @Test fun scopeCannotBeForgedReusedAfterRevocationOrTransferredBetweenSessions() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        fixture.pins.setup(fixture.family, fixture.sample)
        val parent = fixture.session()
        assertTrue(runCatching { parent.requireScope() }.isFailure)
        parent.unlock(fixture.sample)
        val scope = parent.requireScope()
        assertTrue(runCatching { parent.checkScope(ParentAuthorizedScope(fixture.family, 0)) }.isFailure)
        val other = fixture.session()
        other.unlock(fixture.sample)
        assertTrue(runCatching { other.checkScope(scope) }.isFailure)
        parent.revoke()
        parent.unlock(fixture.sample)
        assertTrue(runCatching { parent.checkScope(scope) }.isFailure)
    }

    @Test fun inactivityExpiresAndTouchCannotReviveExpiredSession() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.pins.setup(f.family, f.sample)
        val parent = f.session()
        parent.unlock(f.sample)
        f.now = f.now.plusSeconds(299)
        assertTrue(parent.authorized())
        parent.touch()
        f.now = f.now.plusSeconds(299)
        assertTrue(parent.authorized())
        f.now = f.now.plusSeconds(1)
        parent.touch()
        assertFalse(parent.authorized())
    }

    @Test fun revocationWhilePinVerificationIsPendingNeverGrantsScope() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.pins.setup(f.family, f.sample)
        val parent = f.session()
        f.gate = CompletableDeferred()
        val result = async { runCatching { parent.unlock(f.sample) } }
        runCurrent()
        parent.revoke()
        f.gate!!.complete(Unit)
        assertTrue(result.await().isFailure)
        assertFalse(parent.authorized())
    }

    private class Fixture(dispatcher: kotlinx.coroutines.CoroutineDispatcher) : PinRecordStore {
        val family = FamilyId("fixture-family")
        val sample = CharArray(4) { ('0'.code + it).toChar() }
        var now = Instant.EPOCH
        var record: PinRecord? = null
        var gate: CompletableDeferred<Unit>? = null
        val clock = Clock { now }
        val pins = PinService(this, clock, PinDerivation { pin, _, _ -> ByteArray(32) { pin[it % pin.size].code.toByte() } }, dispatcher)
        fun session() = ParentSession(family, pins, clock)
        override suspend fun read() = record
        override suspend fun write(record: PinRecord) { gate?.await(); this.record = record }
    }
}
