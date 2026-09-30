package com.lazyeng.family.feature.dictionary

import android.database.sqlite.SQLiteDatabase
import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.testing.TestDispatchers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DictionaryAssetProvisionerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val testDispatchers = TestDispatchers()

    @Test
    fun provisionsAndReusesVerifiedDatabase() = runTest(testDispatchers.scheduler) {
        val databaseBytes = createDatabase()
        val provisioner = provisioner(databaseBytes, temporaryFolder.root)

        val first = provisioner.ensureReady()
        val readyState = provisioner.state.value
        val second = provisioner.ensureReady()

        assertEquals(first, second)
        assertTrue(first.isFile)
        assertEquals(DictionaryProvisioningState.Ready(first), provisioner.state.value)
        assertSame(readyState, provisioner.state.value)
        SQLiteDatabase.openDatabase(first.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT word FROM dictionary WHERE word='run'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        }
    }

    @Test
    fun repairsCorruptExistingCopyAndRemovesInterruptedTemporaryFile() = runTest(testDispatchers.scheduler) {
        val bytes = createDatabase()
        val directory = temporaryFolder.newFolder()
        val target = File(directory, DictionaryAssetProvisioner.DATABASE_NAME)
        target.writeText("partial-corrupt-content")
        File(directory, "${DictionaryAssetProvisioner.DATABASE_NAME}.part").writeText("interrupted")

        val result = provisioner(bytes, directory).ensureReady()

        assertTrue(result.length() > 0)
        assertFalse(File(directory, "${DictionaryAssetProvisioner.DATABASE_NAME}.part").exists())
        assertEquals(databaseHash(bytes), sha256(result.readBytes()))
    }

    @Test
    fun concurrentEnsureCallsAreSerializedAndAllReturnInstalledFile() = runTest(testDispatchers.scheduler) {
        val bytes = createDatabase()
        val provisioner = provisioner(bytes, temporaryFolder.root)

        val calls = List(4) { async { provisioner.ensureReady() } }

        assertTrue(calls.awaitAll().all { it == provisioner.databaseFile })
    }

    @Test
    fun checksumFailureNeverMarksReadyOrLeavesPartialDatabase() = runTest(testDispatchers.scheduler) {
        val bytes = createDatabase()
        val directory = temporaryFolder.root
        val source = ByteArrayDictionaryAssets(bytes, "0".repeat(64))
        val provisioner = DictionaryAssetProvisioner(source, directory, dispatchers())

        runCatching { provisioner.ensureReady() }.onSuccess { error("Expected integrity rejection") }

        assertTrue(provisioner.state.value is DictionaryProvisioningState.Failed)
        assertFalse(provisioner.databaseFile.exists())
        assertFalse(File(directory, "${DictionaryAssetProvisioner.DATABASE_NAME}.part").exists())
    }

    private fun provisioner(bytes: ByteArray, directory: File) =
        DictionaryAssetProvisioner(ByteArrayDictionaryAssets(bytes, databaseHash(bytes)), directory, dispatchers())

    private fun dispatchers() = object : DispatcherProvider {
        override val io = testDispatchers.dispatcher
        override val computation = testDispatchers.dispatcher
        override val main = testDispatchers.dispatcher
    }

    private fun createDatabase(): ByteArray {
        val file = temporaryFolder.newFile("fixture.sqlite")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.version = 1
            database.execSQL("CREATE TABLE dictionary (word TEXT NOT NULL COLLATE NOCASE PRIMARY KEY, phonetic TEXT NOT NULL, definition TEXT NOT NULL, translation TEXT NOT NULL, pos TEXT NOT NULL, bnc INTEGER, frq INTEGER) WITHOUT ROWID")
            database.execSQL("INSERT INTO dictionary VALUES ('run', '/rʌn/', '', '跑', 'v.', 1, 1)")
        }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(file.readBytes()) }
        file.delete()
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun databaseHash(compressed: ByteArray): String = sha256(
        GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() },
    )

    private class ByteArrayDictionaryAssets(private val compressed: ByteArray, private val hash: String) : DictionaryAssetSource {
        override fun openCompressedDatabase() = ByteArrayInputStream(compressed)
        override fun openExpectedSha256() = ByteArrayInputStream(hash.toByteArray(Charsets.US_ASCII))
    }
}
