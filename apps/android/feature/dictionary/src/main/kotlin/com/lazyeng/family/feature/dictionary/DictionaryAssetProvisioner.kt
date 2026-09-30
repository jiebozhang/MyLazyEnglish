package com.lazyeng.family.feature.dictionary

import android.content.res.AssetManager
import android.database.sqlite.SQLiteDatabase
import com.lazyeng.family.core.common.DispatcherProvider
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface DictionaryProvisioningState {
    data object NotStarted : DictionaryProvisioningState
    data object Preparing : DictionaryProvisioningState
    data class Ready(val database: File) : DictionaryProvisioningState
    data class Failed(val message: String) : DictionaryProvisioningState
}

/** Installs the versioned read-only dictionary atomically and repairs invalid/incomplete copies. */
class DictionaryAssetProvisioner(
    private val assets: DictionaryAssetSource,
    private val directory: File,
    private val dispatchers: DispatcherProvider,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<DictionaryProvisioningState>(DictionaryProvisioningState.NotStarted)
    @Volatile private var verifiedFingerprint: Pair<Long, Long>? = null
    val state: StateFlow<DictionaryProvisioningState> = mutableState.asStateFlow()
    val databaseFile: File get() = File(directory, DATABASE_NAME)

    suspend fun ensureReady(): File = mutex.withLock {
        withContext(dispatchers.io) {
            try {
                val expectedHash = assets.openExpectedSha256().bufferedReader(Charsets.US_ASCII).use { it.readText().trim() }
                require(expectedHash.matches(Regex("[0-9a-fA-F]{64}"))) { "Dictionary checksum manifest is invalid" }
                val target = databaseFile
                val fingerprint = target.takeIf(File::isFile)?.let { it.length() to it.lastModified() }
                if (mutableState.value is DictionaryProvisioningState.Ready && fingerprint != null && fingerprint == verifiedFingerprint) {
                    return@withContext target
                }
                mutableState.value = DictionaryProvisioningState.Preparing
                if (isValid(target, expectedHash)) {
                    verifiedFingerprint = target.length() to target.lastModified()
                    mutableState.value = DictionaryProvisioningState.Ready(target)
                    return@withContext target
                }

                directory.mkdirs()
                val temporary = File(directory, "$DATABASE_NAME.part")
                temporary.delete()
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    DigestInputStream(GZIPInputStream(assets.openCompressedDatabase().buffered()), digest).use { input ->
                        temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
                    require(actualHash.equals(expectedHash, ignoreCase = true)) { "Dictionary SHA-256 validation failed" }
                    require(isValidDatabase(temporary)) { "Dictionary SQLite integrity validation failed" }
                    try {
                        java.nio.file.Files.move(
                            temporary.toPath(), target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        )
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                    target.setReadable(true, true)
                    target.setWritable(false, false)
                    check(isValid(target, expectedHash)) { "Installed dictionary failed final verification" }
                    verifiedFingerprint = target.length() to target.lastModified()
                    mutableState.value = DictionaryProvisioningState.Ready(target)
                    target
                } finally {
                    temporary.delete()
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = DictionaryProvisioningState.NotStarted
                throw cancelled
            } catch (failure: Exception) {
                verifiedFingerprint = null
                mutableState.value = DictionaryProvisioningState.Failed("离线词典安装失败，请重试。")
                throw DictionaryProvisioningException("离线词典安装失败，请重试。", failure)
            }
        }
    }

    private fun isValid(file: File, expectedHash: String): Boolean = file.isFile &&
        sha256(file).equals(expectedHash, ignoreCase = true) && isValidDatabase(file)

    private fun isValidDatabase(file: File): Boolean = runCatching {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.version == SCHEMA_VERSION && database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == "ok"
            }
        }
    }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    companion object {
        const val DATABASE_NAME = "ecdictionary.sqlite"
        private const val SCHEMA_VERSION = 1
    }
}

interface DictionaryAssetSource {
    fun openCompressedDatabase(): java.io.InputStream
    fun openExpectedSha256(): java.io.InputStream
}

class AssetManagerDictionarySource(
    private val manager: AssetManager,
    private val basePath: String = "dictionary",
) : DictionaryAssetSource {
    override fun openCompressedDatabase() = manager.open("$basePath/${DictionaryAssetProvisioner.DATABASE_NAME}.gzip")
    override fun openExpectedSha256() = manager.open("$basePath/${DictionaryAssetProvisioner.DATABASE_NAME}.sha256")
}

class DictionaryProvisioningException(message: String, cause: Throwable) : Exception(message, cause)
