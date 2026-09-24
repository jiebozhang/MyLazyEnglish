package com.lazyeng.family.core.common

import com.lazyeng.family.core.model.FileReference
import com.lazyeng.family.core.model.AiFeature
import com.lazyeng.family.core.model.SecretReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.time.Instant
import kotlin.time.Duration

/** Supplies deterministic instants to domain and feature code. */
fun interface Clock {
    fun now(): Instant
}

interface DispatcherProvider {
    val io: CoroutineDispatcher
    val computation: CoroutineDispatcher
    val main: CoroutineDispatcher
}

/** Resolves opaque SAF/platform references; implementations must not expose raw paths to UI. */
interface FileAccess {
    suspend fun openRead(reference: FileReference): AppResult<InputStream>
    suspend fun getSizeBytes(reference: FileReference): AppResult<Long>
    suspend fun delete(reference: FileReference): AppResult<Unit>
}

data class PlaybackSnapshot(val positionMs: Long, val isPlaying: Boolean, val durationMs: Long?)
/** Player boundary; state is observed as snapshots and commands contain no UI types. */
interface MediaSession {
    val playback: Flow<PlaybackSnapshot>
    suspend fun play(reference: FileReference, startPositionMs: Long = 0L): AppResult<Unit>
    suspend fun pause()
    suspend fun seekTo(positionMs: Long)
    suspend fun setSpeed(speed: Float)
    suspend fun release()
}

/** Stores secret material by opaque reference; callers must clear mutable secret buffers. */
interface SecureSecretStore {
    suspend fun read(reference: SecretReference): AppResult<CharArray?>
    suspend fun write(reference: SecretReference, secret: CharArray): AppResult<Unit>
    suspend fun delete(reference: SecretReference): AppResult<Unit>
}

data class AiProviderConfig(
    val baseUrl: String,
    val model: String,
    val secretReference: SecretReference,
    val timeout: Duration,
)
data class ContextExplanationRequest(
    val word: String,
    val lemma: String,
    val sentenceEnglish: String,
    val previousLineEnglish: String?,
    val nextLineEnglish: String?,
    val learnerLevel: String,
    val uiLanguage: String,
)
data class ContextExplanation(
    val meaningZh: String,
    val whyHereZh: String,
    val simpleExampleEnglish: String?,
    val simpleExampleChinese: String?,
    val partOfSpeech: String?,
    val confidence: Float?,
    val safetyFlag: String?,
)
data class AiProviderResult<T>(
    val requestId: String,
    val feature: AiFeature,
    val value: T,
    val provider: String,
    val model: String,
    val promptVersion: String,
    val cacheHit: Boolean,
    val latencyMs: Long,
    val usageBucket: String?,
    val safetyFlag: String?,
)
/** Provider-neutral AI boundary. Implementations must not put credentials or raw responses in diagnostics. */
interface AiProvider {
    suspend fun validateConnection(config: AiProviderConfig): AppResult<Unit>
    suspend fun explainContext(
        config: AiProviderConfig,
        request: ContextExplanationRequest,
    ): AppResult<AiProviderResult<ContextExplanation>>
}
