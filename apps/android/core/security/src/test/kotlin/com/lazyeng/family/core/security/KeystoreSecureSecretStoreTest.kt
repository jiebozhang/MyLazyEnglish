package com.lazyeng.family.core.security

import com.lazyeng.family.core.common.AppResult
import com.lazyeng.family.core.model.SecretReference
import com.lazyeng.family.core.testing.TestDispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreSecureSecretStoreTest {
    @Test
    fun factoryRetainsOriginalContextOnlyJvmSignature() {
        val method = KeystoreSecureSecretStore.Companion.javaClass
            .getMethod("create", android.content.Context::class.java)
        assertEquals(com.lazyeng.family.core.common.SecureSecretStore::class.java, method.returnType)
        KeystoreSecureSecretStore.Companion.javaClass.getMethod(
            "create", android.content.Context::class.java, SecretRecordFile::class.java,
        )
    }

    @Test
    fun encryptedRecordRoundTripsAndNeverContainsPlainSecret() = runTest {
        val file = InMemoryRecordFile()
        val store = KeystoreSecureSecretStore(file, TestCipher(), TestDispatchers(testScheduler).dispatcher)
        val fakeSecret = "TEST_ONLY_FAKE_CREDENTIAL"
        assertTrue(store.write(reference, fakeSecret.toCharArray()) is AppResult.Success)
        assertFalse(file.value!!.toString(Charsets.UTF_8).contains(fakeSecret))
        val read = (store.read(reference) as AppResult.Success).value!!
        assertArrayEquals(fakeSecret.toCharArray(), read)
        read.fill('\u0000')
    }

    @Test
    fun cipherFailureRejectsWriteWithoutAnyPlaintextFile() = runTest {
        val file = InMemoryRecordFile()
        val store = KeystoreSecureSecretStore(file, FailingCipher, TestDispatchers(testScheduler).dispatcher)
        val result = store.write(reference, "TEST_ONLY_FAKE_CREDENTIAL".toCharArray())
        assertEquals("AI_SECRET_STORAGE_FAILED", (result as AppResult.Failure).error.code.value)
        assertEquals(0, file.writeCount)
        assertTrue(file.value == null)
    }

    private val reference = SecretReference("test-reference")

    private class TestCipher : SecretRecordCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray) = encrypt(ciphertext)
    }

    private class InMemoryRecordFile : SecretRecordFile {
        var value: ByteArray? = null
        var writeCount = 0
        override fun read() = value?.copyOf()
        override fun writeAtomically(bytes: ByteArray) {
            writeCount++
            value = bytes.copyOf()
        }
        override fun delete() { value = null }
    }

    private object FailingCipher : SecretRecordCipher {
        override fun encrypt(plain: ByteArray): ByteArray = error("keystore unavailable")
        override fun decrypt(ciphertext: ByteArray): ByteArray = error("keystore unavailable")
    }
}
