package com.lazyeng.family.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.lazyeng.family.core.model.FamilyId
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

interface RecordCipher {
    fun encrypt(bytes: ByteArray): ByteArray
    fun decrypt(bytes: ByteArray): ByteArray
}

/** Encrypts only the one-way verifier/throttle record. A raw PIN never reaches this layer. */
class KeystorePinRecordStore(private val file: AtomicFile, private val cipher: RecordCipher) : PinRecordStore {
    override suspend fun read(): PinRecord? = withContext(Dispatchers.IO) {
        val encrypted = try { file.readFully() } catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return@withContext null
        }
        val plain = cipher.decrypt(encrypted)
        try {
            val json = JSONObject(plain.toString(Charsets.UTF_8))
            PinRecord(
                FamilyId(json.getString("familyId")), json.getInt("version"), json.getString("algorithm"),
                json.getInt("iterations"), Base64.getDecoder().decode(json.getString("salt")),
                Base64.getDecoder().decode(json.getString("hash")), json.getInt("failures"), json.getLong("lockUntil"),
            )
        } finally { plain.fill(0) }
    }

    override suspend fun write(record: PinRecord) = withContext(Dispatchers.IO) {
        val plain = JSONObject().put("familyId", record.familyId.value).put("version", record.version)
            .put("algorithm", record.algorithm).put("iterations", record.iterations)
            .put("salt", Base64.getEncoder().encodeToString(record.salt))
            .put("hash", Base64.getEncoder().encodeToString(record.hash))
            .put("failures", record.failures).put("lockUntil", record.lockUntil)
            .toString().toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.encrypt(plain) } finally { plain.fill(0) }
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    companion object {
        fun create(context: Context): PinRecordStore {
            val file = AtomicFile(File(context.noBackupFilesDir, "parent-pin.enc"))
            return KeystorePinRecordStore(file, AndroidRecordCipher())
        }
    }
}

private class AndroidRecordCipher : RecordCipher {
    private val alias = "lazyeng.parent.pin.verifier.v1"
    private fun key(create: Boolean): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "PIN protection key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
    override fun encrypt(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key(create = true))
        iv + doFinal(bytes)
    }
    override fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size >= 28) { "PIN record damaged" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size))
        }
    }
}
