package com.lazyeng.family.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.lazyeng.family.core.common.AppError
import com.lazyeng.family.core.common.AppErrorCode
import com.lazyeng.family.core.common.AppResult
import com.lazyeng.family.core.common.ErrorHandling
import com.lazyeng.family.core.common.SecureSecretStore
import com.lazyeng.family.core.common.UserMessageKey
import com.lazyeng.family.core.model.SecretReference
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

interface SecretRecordCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

interface SecretRecordFile {
    fun read(): ByteArray?
    fun writeAtomically(bytes: ByteArray)
    fun delete()
}

class AtomicSecretRecordFile(private val file: AtomicFile) : SecretRecordFile {
    override fun read(): ByteArray? = try {
        file.readFully()
    } catch (error: java.io.FileNotFoundException) {
        if (file.baseFile.exists()) throw error
        null
    }

    override fun writeAtomically(bytes: ByteArray) {
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    override fun delete() = file.delete()
}

/** AES-GCM encrypted app-private records. Any Keystore or file failure fails closed. */
class KeystoreSecureSecretStore(
    private val file: SecretRecordFile,
    private val cipher: SecretRecordCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SecureSecretStore {
    override suspend fun read(reference: SecretReference): AppResult<CharArray?> = withContext(io) {
        try {
            val encrypted = file.read() ?: return@withContext AppResult.Success(null)
            val plain = cipher.decrypt(encrypted)
            try {
                val records = JSONObject(plain.toString(Charsets.UTF_8))
                if (!records.has(referenceKey(reference))) return@withContext AppResult.Success(null)
                val decoded = Base64.getDecoder().decode(records.getString(referenceKey(reference)))
                try {
                    val chars = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(decoded))
                    CharArray(chars.remaining()).also(chars::get)
                } finally {
                    decoded.fill(0)
                }
            } finally {
                plain.fill(0)
            }.let { AppResult.Success(it) }
        } catch (_: Exception) {
            failure()
        }
    }

    override suspend fun write(reference: SecretReference, secret: CharArray): AppResult<Unit> = withContext(io) {
        var plain: ByteArray? = null
        var encrypted: ByteArray? = null
        try {
            val records = readRecords()
            val encodedChars = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(secret))
            val value = ByteArray(encodedChars.remaining()).also(encodedChars::get)
            try {
                records.put(referenceKey(reference), Base64.getEncoder().encodeToString(value))
            } finally {
                value.fill(0)
            }
            plain = records.toString().toByteArray(Charsets.UTF_8)
            encrypted = cipher.encrypt(plain)
            file.writeAtomically(encrypted)
            AppResult.Success(Unit)
        } catch (_: Exception) {
            failure()
        } finally {
            plain?.fill(0)
            encrypted?.fill(0)
        }
    }

    override suspend fun delete(reference: SecretReference): AppResult<Unit> = withContext(io) {
        var plain: ByteArray? = null
        var encrypted: ByteArray? = null
        try {
            val records = readRecords()
            records.remove(referenceKey(reference))
            plain = records.toString().toByteArray(Charsets.UTF_8)
            encrypted = cipher.encrypt(plain)
            file.writeAtomically(encrypted)
            AppResult.Success(Unit)
        } catch (_: Exception) {
            failure()
        } finally {
            plain?.fill(0)
            encrypted?.fill(0)
        }
    }

    private fun readRecords(): JSONObject {
        val encrypted = file.read() ?: return JSONObject()
        val plain = cipher.decrypt(encrypted)
        return try {
            JSONObject(plain.toString(Charsets.UTF_8))
        } finally {
            plain.fill(0)
        }
    }

    private fun referenceKey(reference: SecretReference): String =
        MessageDigest.getInstance("SHA-256").digest(reference.value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private val STORAGE_ERROR = AppError(
            AppErrorCode("AI_SECRET_STORAGE_FAILED"), ErrorHandling.PARENT_INTERVENTION,
            UserMessageKey("error.ai_secret_storage_unavailable"),
        )

        private fun failure() = AppResult.Failure(STORAGE_ERROR)

        // Preserve the original JVM factory signature for previously compiled callers.
        @JvmOverloads
        fun create(
            context: Context,
            file: SecretRecordFile = AtomicSecretRecordFile(AtomicFile(File(context.noBackupFilesDir, "ai-secrets.enc"))),
        ): SecureSecretStore = KeystoreSecureSecretStore(
            file, AndroidKeystoreSecretCipher(),
        )
    }
}

private class AndroidKeystoreSecretCipher : SecretRecordCipher {
    private val alias = "lazyeng.ai.credentials.v1"

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Credential protection key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    override fun encrypt(plain: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key(create = true))
        iv + doFinal(plain)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size >= IV_BYTES + TAG_BYTES) { "Encrypted credential record is invalid" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, ciphertext.copyOfRange(0, IV_BYTES)))
            doFinal(ciphertext, IV_BYTES, ciphertext.size - IV_BYTES)
        }
    }

    private companion object {
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}
