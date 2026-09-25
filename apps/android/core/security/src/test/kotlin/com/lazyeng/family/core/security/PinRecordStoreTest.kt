package com.lazyeng.family.core.security

import android.util.AtomicFile
import com.lazyeng.family.core.model.FamilyId
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PinRecordStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val sample = PinRecord(FamilyId("fixture-family"), 1, PinPolicy.algorithm, 210_000,
        ByteArray(16) { it.toByte() }, ByteArray(32) { (it + 1).toByte() }, 5, 30_000)

    @Test fun initializationFailureNeverWritesPlaintextOrOverwritesExistingRecord() = runTest {
        val file = File(folder.root, "pin.enc")
        val marker = byteArrayOf(42, 41)
        file.writeBytes(marker)
        val unavailable = object : RecordCipher {
            override fun encrypt(bytes: ByteArray): ByteArray = throw GeneralSecurityException("unavailable")
            override fun decrypt(bytes: ByteArray): ByteArray = throw GeneralSecurityException("unavailable")
        }
        val store = KeystorePinRecordStore(AtomicFile(file), unavailable)
        assertTrue(runCatching { store.write(sample) }.isFailure)
        assertArrayEquals(marker, file.readBytes())
        assertTrue(runCatching { store.read() }.isFailure)
    }

    @Test fun encryptedRecordRoundTripAndTamperRejection() = runTest {
        // Software cipher verifies serialization/atomic storage only; device tests use Android Keystore.
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = object : RecordCipher {
            override fun encrypt(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key); iv + doFinal(bytes)
            }
            override fun decrypt(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                doFinal(bytes.copyOfRange(12, bytes.size))
            }
        }
        val file = File(folder.root, "pin.enc")
        KeystorePinRecordStore(AtomicFile(file), cipher).write(sample)
        assertFalse(file.readText().contains("fixture-family"))
        val loaded = checkNotNull(KeystorePinRecordStore(AtomicFile(file), cipher).read())
        assertEquals(sample.familyId, loaded.familyId)
        assertEquals(sample.failures, loaded.failures)
        assertEquals(sample.lockUntil, loaded.lockUntil)
        assertArrayEquals(sample.hash, loaded.hash)
        val corrupted = file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        file.writeBytes(corrupted)
        assertTrue(runCatching { KeystorePinRecordStore(AtomicFile(file), cipher).read() }.isFailure)
    }
}
