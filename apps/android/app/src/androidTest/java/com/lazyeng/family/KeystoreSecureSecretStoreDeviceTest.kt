package com.lazyeng.family

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.common.AppResult
import com.lazyeng.family.core.model.SecretReference
import com.lazyeng.family.core.security.AtomicSecretRecordFile
import com.lazyeng.family.core.security.KeystoreSecureSecretStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class KeystoreSecureSecretStoreDeviceTest {
    // Instrumentation runs under the target UID, so isolate fixture records by filename.
    @Test
    fun aWriteFakeSecretToKeystoreEncryptedStorage() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.noBackupFilesDir, FIXTURE_FILE)
            file.delete()
            val store = KeystoreSecureSecretStore.create(context, AtomicSecretRecordFile(AtomicFile(file)))
            val secret = fakeSecret.toCharArray()
            assertTrue(store.write(reference, secret) is AppResult.Success)
            secret.fill('\u0000')
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(fakeSecret))
        }
    }

    @Test
    fun bReadAfterApplicationForceStopAndRemoveFakeSecret() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.noBackupFilesDir, FIXTURE_FILE)
            val store = KeystoreSecureSecretStore.create(context, AtomicSecretRecordFile(AtomicFile(file)))
            val value = ((store.read(reference) as AppResult.Success).value ?: error("test value unavailable"))
            assertArrayEquals(fakeSecret.toCharArray(), value)
            value.fill('\u0000')
            assertTrue(store.delete(reference) is AppResult.Success)
            file.delete()
        }
    }

    private val reference = SecretReference("device-test-reference")
    private val fakeSecret = "TEST_ONLY_FAKE_CREDENTIAL_42"
    private companion object { const val FIXTURE_FILE = "int3-keystore-fixture.enc" }
}
