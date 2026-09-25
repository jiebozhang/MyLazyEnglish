package com.lazyeng.family.core.security

import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.model.FamilyId
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object PinPolicy {
    const val minimumIterations = 210_000
    // Temporary Xiaomi 10S calibration. Recheck with signed Release on 15 Ultra in INT-2.
    // Default platform provider measured >250ms even at the mandatory floor; see ADR 0008.
    const val newRecordIterations = 210_000
    const val algorithm = "PBKDF2WithHmacSHA256"
    fun delayMillis(failures: Int): Long = if (failures < 5) 0 else
        (30_000L shl (failures - 5).coerceAtMost(5)).coerceAtMost(900_000L)
}

class PinRecord(
    val familyId: FamilyId,
    val version: Int,
    val algorithm: String,
    val iterations: Int,
    val salt: ByteArray,
    val hash: ByteArray,
    val failures: Int = 0,
    val lockUntil: Long = 0,
) {
    fun withThrottle(failures: Int, lockUntil: Long) =
        PinRecord(familyId, version, algorithm, iterations, salt, hash, failures, lockUntil)
    override fun toString(): String = "PinRecord(REDACTED)"
}

interface PinRecordStore {
    suspend fun read(): PinRecord?
    suspend fun write(record: PinRecord)
}

data class PinStatus(val configured: Boolean, val failures: Int = 0, val lockUntil: Long = 0)
sealed interface PinCheck {
    data object Accepted : PinCheck
    data class Rejected(val lockUntil: Long) : PinCheck
    data class Locked(val lockUntil: Long) : PinCheck
}

fun interface PinDerivation {
    fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray
}

class PlatformPbkdf2 : PinDerivation {
    override fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val specification = PBEKeySpec(pin, salt, iterations, 256)
        return try {
            // Let Android select its platform provider; do not hardcode BC.
            SecretKeyFactory.getInstance(PinPolicy.algorithm).generateSecret(specification).encoded
        } finally {
            specification.clearPassword()
        }
    }
}

/** Application-owned instance; the mutex serializes setup and verification attempts. */
class PinService(
    private val store: PinRecordStore,
    private val clock: Clock,
    private val derivation: PinDerivation = PlatformPbkdf2(),
    private val computation: CoroutineDispatcher = Dispatchers.Default,
    private val random: SecureRandom = SecureRandom(),
) {
    private val mutex = Mutex()

    suspend fun status(familyId: FamilyId): PinStatus = mutex.withLock {
        val record = store.read() ?: return@withLock PinStatus(false)
        validate(record, familyId)
        PinStatus(true, record.failures, record.lockUntil)
    }

    suspend fun setup(familyId: FamilyId, pin: CharArray) = mutex.withLock {
        requirePin(pin)
        check(store.read() == null) { "PIN already configured" }
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = withContext(computation) { derivation.derive(pin, salt, PinPolicy.newRecordIterations) }
        store.write(PinRecord(familyId, 1, PinPolicy.algorithm, PinPolicy.newRecordIterations, salt, hash))
    }

    suspend fun verify(familyId: FamilyId, pin: CharArray): PinCheck = mutex.withLock {
        requirePin(pin)
        val record = checkNotNull(store.read()) { "PIN is not configured" }
        validate(record, familyId)
        val now = clock.now().toEpochMilli()
        if (record.lockUntil > now) return@withLock PinCheck.Locked(record.lockUntil)
        // Reserve a failed attempt durably BEFORE expensive work. Process death cannot erase it.
        val count = (record.failures.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val delay = PinPolicy.delayMillis(count)
        val pending = record.withThrottle(count, if (delay == 0L) 0 else now + delay)
        store.write(pending)
        val candidate = withContext(computation) { derivation.derive(pin, record.salt, record.iterations) }
        val matches = try { MessageDigest.isEqual(record.hash, candidate) } finally { candidate.fill(0) }
        if (matches) {
            store.write(record.withThrottle(0, 0))
            PinCheck.Accepted
        } else {
            val until = if (delay == 0L) 0 else clock.now().toEpochMilli() + delay
            store.write(pending.withThrottle(count, until))
            PinCheck.Rejected(until)
        }
    }

    private fun requirePin(pin: CharArray) {
        require(pin.size == 4 && pin.all { it in '0'..'9' }) { "PIN requires four digits" }
    }
    private fun validate(record: PinRecord, familyId: FamilyId) {
        check(record.familyId == familyId && record.version == 1 && record.algorithm == PinPolicy.algorithm &&
            record.iterations >= PinPolicy.minimumIterations && record.salt.size >= 16 && record.hash.size == 32 &&
            record.failures >= 0 && record.lockUntil >= 0) { "PIN record unavailable" }
    }
}
