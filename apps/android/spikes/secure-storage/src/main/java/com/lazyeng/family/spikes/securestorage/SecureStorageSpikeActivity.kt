package com.lazyeng.family.spikes.securestorage

import android.app.Activity
import android.annotation.TargetApi
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import kotlin.math.roundToInt

private const val LOG_TAG = "SecureStorageSpike"
private const val KEY_ALIAS = "lazyeng_spike_cipher_v1"
private const val PREFS_NAME = "secure_storage_spike"
private const val TARGET_HASH_MILLIS = 250.0
private const val MIN_ITERATIONS = 210_000
private const val LOCK_PREF_COUNT = "probe.failure_count"
private const val LOCK_PREF_UNTIL = "probe.lock_until"
private const val SALT_PREF = "probe.verifier_salt"
private const val HASH_PREF = "probe.verifier_hash"
private const val ITERATIONS_PREF = "probe.verifier_iterations"

class SecureStorageSpikeActivity : Activity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            textSize = 14f
            setPadding(24, 24, 24, 24)
            gravity = Gravity.START
        }
        setContentView(ScrollView(this).apply { addView(output) })
        output.text = getString(R.string.spike_running)

        val mode = intent.getStringExtra("mode") ?: "baseline"
        Thread {
            val report = runCatching { SpikeRunner(this).run(mode, intent.extras?.getString("cipher"), intent.extras?.getString("digest")) }
                .fold(
                    onSuccess = { it },
                    onFailure = { "FAIL: spike runner (${it.javaClass.simpleName})" },
                )
            File(filesDir, "spike-report.txt").writeText(report)
            Log.i(LOG_TAG, report)
            runOnUiThread { output.text = report }
        }.start()
    }
}

@Suppress("ApplySharedPref")
private class SpikeRunner(private val activity: Activity) {
    private val random = SecureRandom()
    private val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
    private val results = mutableListOf<String>()

    fun run(mode: String, cipherExtra: String?, digestExtra: String?): String {
        when (mode) {
            "baseline" -> runBaseline()
            "verify-process" -> runProcessRestartCheck()
            "verify-uninstall" -> runUninstallCheck(cipherExtra, digestExtra)
            else -> results += "FAIL unknown mode"
        }
        return results.joinToString("\n")
    }

    private fun runBaseline() {
        prefs.edit().clear().commit()
        deleteKey(KEY_ALIAS)

        checkResult("Keystore AES-GCM encrypt/decrypt and at-rest ciphertext") {
            val secret = ByteArray(32).also(random::nextBytes)
            val cipherText = KeystoreCipher(KEY_ALIAS).encrypt(secret)
            val digest = sha256(secret)
            File(activity.filesDir, "probe.b64").writeText(Base64.encodeToString(cipherText, Base64.NO_WRAP))
            File(activity.filesDir, "probe.sha256").writeText(hex(digest))
            val diskContainsPlaintext = activity.filesDir.walkTopDown()
                .filter(File::isFile)
                .any { containsBytes(it.readBytes(), secret) }
            val decrypted = KeystoreCipher(KEY_ALIAS).decrypt(cipherText)
            val matches = MessageDigest.isEqual(digest, sha256(decrypted))
            secret.fill(0)
            decrypted.fill(0)
            check(!diskContainsPlaintext && matches)
        }

        checkResult("Keystore hardware-backed key classification") {
            val key = getOrCreateKey(KEY_ALIAS)
            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            val securityLevel = runCatching {
                KeyInfo::class.java.getMethod("getSecurityLevel").invoke(info)?.toString()
            }.getOrNull() ?: "unavailable"
            @Suppress("DEPRECATION")
            val legacyHardwareBacked = info.isInsideSecureHardware
            val hardwareBacked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                    info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
            } else legacyHardwareBacked
            val securityLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                    else -> "unknown"
                }
            } else if (legacyHardwareBacked) "hardware" else "software"
            results += "Keystore key hardware-backed=$hardwareBacked; securityLevel=$securityLevel ($securityLabel)"
            check(hardwareBacked)
        }

        checkResult("StrongBox capability probe") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                deleteKey("${KEY_ALIAS}_strongbox")
                try {
                    getOrCreateStrongBoxKey("${KEY_ALIAS}_strongbox")
                    results += "StrongBox supported=true"
                    deleteKey("${KEY_ALIAS}_strongbox")
                } catch (error: Exception) {
                    val reason = if (error.javaClass.simpleName == "StrongBoxUnavailableException") {
                        "StrongBoxUnavailableException"
                    } else error.javaClass.simpleName
                    results += "StrongBox supported=false; reason=$reason"
                }
            } else {
                results += "StrongBox supported=false; reason=requires API 28"
            }
        }

        checkResult("Keystore initialization failure refuses plaintext fallback") {
            val store = MemoryBlobStore()
            val vault = ApiKeyVault(store) { throw GeneralSecurityException("simulated initialization failure") }
            val rejected = runCatching { vault.store(ByteArray(32).also(random::nextBytes)) }.isFailure
            check(rejected && store.writeCount == 0 && store.value == null)
        }

        runKdfCalibration()
        runThrottleChecks()
        results += "Stored verifier fields=${listOf(SALT_PREF, HASH_PREF, ITERATIONS_PREF).joinToString(",")}; plaintext PIN is never persisted"
    }

    private fun runProcessRestartCheck() {
        checkResult("Keystore key and encrypted value survive process restart") {
            val cipherBytes = Base64.decode(File(activity.filesDir, "probe.b64").readText(), Base64.NO_WRAP)
            val expectedDigest = unhex(File(activity.filesDir, "probe.sha256").readText())
            val decrypted = KeystoreCipher(KEY_ALIAS).decrypt(cipherBytes)
            val valid = MessageDigest.isEqual(expectedDigest, sha256(decrypted))
            decrypted.fill(0)
            check(valid)
        }
        checkResult("PIN verifier and lockout survive process restart") {
            val count = prefs.getInt(LOCK_PREF_COUNT, 0)
            val lockUntil = prefs.getLong(LOCK_PREF_UNTIL, 0L)
            val remaining = lockUntil - System.currentTimeMillis()
            results += "Persisted failures=$count; lockRemainingMs=${remaining.coerceAtLeast(0)}"
            check(count >= 5 && remaining > 0 && prefs.contains(SALT_PREF) && prefs.contains(HASH_PREF))
        }
    }

    private fun runUninstallCheck(cipherExtra: String?, digestExtra: String?) {
        checkResult("Old Keystore key is not recoverable after uninstall/reinstall") {
            require(!cipherExtra.isNullOrBlank() && !digestExtra.isNullOrBlank())
            val keyStore = loadKeyStore()
            val oldKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (oldKey == null) {
                results += "Old alias absent after reinstall=true"
                return@checkResult
            }
            val payload = Base64.decode(cipherExtra, Base64.NO_WRAP)
            val decrypted = decryptWith(oldKey, payload)
            val recovered = MessageDigest.isEqual(unhex(digestExtra), sha256(decrypted))
            decrypted.fill(0)
            results += "Old alias present after reinstall=true; old payload recovered=$recovered"
            check(!recovered)
        }
    }

    private fun runKdfCalibration() {
        checkResult("PBKDF2-HMAC-SHA256 calibration and persisted verifier format") {
            val syntheticPin = randomText(24)
            val pinChars = syntheticPin.toCharArray()
            val salt = ByteArray(16).also(random::nextBytes)
            derive(pinChars, salt, MIN_ITERATIONS)
            val baseline = measure(pinChars, salt, MIN_ITERATIONS, samples = 7)
            val targetIterations = maxOf(
                MIN_ITERATIONS,
                ((MIN_ITERATIONS * TARGET_HASH_MILLIS / baseline.median).roundToInt() / 10_000) * 10_000,
            )
            val calibrated = measure(pinChars, salt, targetIterations, samples = 7)
            val hash = derive(pinChars, salt, targetIterations)
            val providerName = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").provider.name
            prefs.edit()
                .putString(SALT_PREF, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(HASH_PREF, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putInt(ITERATIONS_PREF, targetIterations)
                .commit()
            val persisted = prefs.all.filterKeys { it.startsWith("probe.verifier_") }
            val serialized = persisted.toString()
            val plainStored = serialized.contains(syntheticPin)
            results += "PBKDF2 baselineIterations=$MIN_ITERATIONS; samplesMs=${baseline.values}; medianMs=${fmt(baseline.median)}"
            results += "PBKDF2 calibratedIterations=$targetIterations; samplesMs=${calibrated.values}; medianMs=${fmt(calibrated.median)}; spreadMs=${fmt(calibrated.max - calibrated.min)}"
            results += "PBKDF2 provider=$providerName; targetMs=$TARGET_HASH_MILLIS"
            results += "Verifier persisted only salt/hash/iterations=${persisted.keys.containsAll(listOf(SALT_PREF, HASH_PREF, ITERATIONS_PREF)) && persisted.size == 3}; plaintextFound=$plainStored"
            pinChars.fill('\u0000')
            salt.fill(0)
            hash.fill(0)
            check(targetIterations >= MIN_ITERATIONS && !plainStored && persisted.size == 3)
        }
    }

    private fun runThrottleChecks() {
        checkResult("PIN failure lockout persists and doubles to the cap") {
            prefs.edit().remove(LOCK_PREF_COUNT).remove(LOCK_PREF_UNTIL).commit()
            val clock = MutableClock(System.currentTimeMillis())
            var limiter = PinFailureLimiter(prefs, clock)
            repeat(4) { limiter.recordFailure() }
            val fifth = limiter.recordFailure()
            check(fifth.delayMillis == 30_000L)

            limiter = PinFailureLimiter(prefs, MutableClock(clock.nowMillis))
            check(limiter.isLocked())
            val delays = mutableListOf(fifth.delayMillis)
            repeat(6) {
                clock.nowMillis = prefs.getLong(LOCK_PREF_UNTIL, 0L)
                limiter = PinFailureLimiter(prefs, clock)
                delays += limiter.recordFailure().delayMillis
            }
            val expected = listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L)
            check(delays == expected)
            val restarted = PinFailureLimiter(prefs, MutableClock(clock.nowMillis))
            check(restarted.isLocked() && prefs.getInt(LOCK_PREF_COUNT, 0) == 11)
            results += "Lockout delaysMs=$delays; persisted count=${prefs.getInt(LOCK_PREF_COUNT, 0)}; lockUntilPersisted=${prefs.getLong(LOCK_PREF_UNTIL, 0L) > 0}"
            restarted.resetAfterSuccess()
            check(prefs.getInt(LOCK_PREF_COUNT, -1) == 0 && prefs.getLong(LOCK_PREF_UNTIL, -1L) == 0L)
            results += "Successful verification resets limiter=true"
            repeat(5) { PinFailureLimiter(prefs, clock).recordFailure() }
            check(prefs.getInt(LOCK_PREF_COUNT, 0) == 5 && PinFailureLimiter(prefs, clock).isLocked())
            results += "Restart fixture persisted active lockout=true"
        }
    }

    private fun checkResult(name: String, block: () -> Unit) {
        val before = results.size
        runCatching(block).onSuccess {
            if (results.size == before) results += "PASS $name" else results.add(before, "PASS $name")
        }.onFailure {
            results += "FAIL $name (${it.javaClass.simpleName})"
        }
    }

    private fun measure(pin: CharArray, salt: ByteArray, iterations: Int, samples: Int): Timing {
        val values = (1..samples).map {
            val start = System.nanoTime()
            derive(pin, salt, iterations).fill(0)
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        return Timing(values, values[values.size / 2], values.first(), values.last())
    }

    private fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = loadKeyStore()
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        generator.init(builder.build())
        return generator.generateKey()
    }

    @TargetApi(Build.VERSION_CODES.P)
    private fun getOrCreateStrongBoxKey(alias: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun deleteKey(alias: String) {
        loadKeyStore().deleteEntry(alias)
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun decryptWith(key: SecretKey, encrypted: ByteArray): ByteArray {
        val iv = encrypted.copyOfRange(0, 12)
        val body = encrypted.copyOfRange(12, encrypted.size)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            doFinal(body)
        }
    }

    private fun randomText(length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        return (0..haystack.size - needle.size).any { start ->
            needle.indices.all { offset -> haystack[start + offset] == needle[offset] }
        }
    }

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    private fun hex(value: ByteArray): String = value.joinToString("") { "%02x".format(Locale.US, it) }
    private fun unhex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun fmt(value: Double): String = "%.2f".format(Locale.US, value)
}

private class KeystoreCipher(private val alias: String) {
    fun encrypt(plain: ByteArray): ByteArray {
        val key = cipherKey()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key)
            iv + doFinal(plain)
        }
    }

    fun decrypt(encrypted: ByteArray): ByteArray {
        val key = cipherKey()
        val iv = encrypted.copyOfRange(0, 12)
        val body = encrypted.copyOfRange(12, encrypted.size)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            doFinal(body)
        }
    }

    private fun cipherKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}

private class MemoryBlobStore {
    var value: ByteArray? = null
    var writeCount: Int = 0
        private set

    fun write(blob: ByteArray) {
        writeCount++
        value = blob
    }
}

private class ApiKeyVault(
    private val store: MemoryBlobStore,
    private val cipherFactory: () -> SecretCipher,
) {
    fun store(value: ByteArray) {
        val encrypted = cipherFactory().encrypt(value)
        store.write(encrypted)
    }
}

private fun interface SecretCipher {
    fun encrypt(value: ByteArray): ByteArray
}

private data class MutableClock(var nowMillis: Long)

private data class FailureState(val count: Int, val lockUntilMillis: Long, val delayMillis: Long)

private class PinFailureLimiter(private val prefs: android.content.SharedPreferences, private val clock: MutableClock) {
    fun isLocked(): Boolean = clock.nowMillis < prefs.getLong(LOCK_PREF_UNTIL, 0L)

    fun recordFailure(): FailureState {
        check(!isLocked())
        val count = prefs.getInt(LOCK_PREF_COUNT, 0) + 1
        val delay = if (count < 5) 0L else {
            var value = 30_000L
            repeat((count - 5).coerceAtMost(5)) { value = minOf(900_000L, value * 2) }
            value
        }
        val until = if (delay == 0L) 0L else clock.nowMillis + delay
        check(prefs.edit().putInt(LOCK_PREF_COUNT, count).putLong(LOCK_PREF_UNTIL, until).commit())
        return FailureState(count, until, delay)
    }

    fun resetAfterSuccess() {
        check(prefs.edit().putInt(LOCK_PREF_COUNT, 0).putLong(LOCK_PREF_UNTIL, 0L).commit())
    }
}

private data class Timing(val values: List<Double>, val median: Double, val min: Double, val max: Double)
