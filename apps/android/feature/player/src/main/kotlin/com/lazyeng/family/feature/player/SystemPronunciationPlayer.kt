package com.lazyeng.family.feature.player

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Thin TTS adapter. The caller owns its lifetime and releases it with the lookup sheet. */
class SystemPronunciationPlayer(context: Context) : PronunciationPlayer, TextToSpeech.OnInitListener {
    private val mutableState = MutableStateFlow(PronunciationState.INITIALIZING)
    override val state: StateFlow<PronunciationState> = mutableState.asStateFlow()
    @Volatile private var engine: TextToSpeech? = null

    init { engine = TextToSpeech(context.applicationContext, this) }

    override fun onInit(status: Int) {
        mutableState.value = if (status == TextToSpeech.SUCCESS) PronunciationState.READY else PronunciationState.UNAVAILABLE
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { mutableState.value = PronunciationState.SPEAKING }
            override fun onDone(utteranceId: String?) { mutableState.value = PronunciationState.READY }
            @Deprecated("Deprecated by Android")
            override fun onError(utteranceId: String?) { mutableState.value = PronunciationState.ERROR }
            override fun onError(utteranceId: String?, errorCode: Int) { mutableState.value = PronunciationState.ERROR }
        })
    }

    override fun speak(text: String, accent: PronunciationAccent) {
        val tts = engine
        if (tts == null || mutableState.value == PronunciationState.INITIALIZING) {
            mutableState.value = PronunciationState.UNAVAILABLE
            return
        }
        val locale = resolvePronunciationLocale(accent, tts::isLanguageAvailable)
        if (locale == null) {
            mutableState.value = PronunciationState.UNAVAILABLE
            return
        }
        tts.language = locale
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        mutableState.value = if (result == TextToSpeech.ERROR) PronunciationState.ERROR else PronunciationState.SPEAKING
    }

    override fun stop() {
        engine?.stop()
        if (engine != null) mutableState.value = PronunciationState.READY
    }

    override fun release() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        mutableState.value = PronunciationState.UNAVAILABLE
    }
}

internal fun resolvePronunciationLocale(
    accent: PronunciationAccent,
    isLanguageAvailable: (Locale) -> Int,
): Locale? {
    val candidates = when (accent) {
        PronunciationAccent.AMERICAN -> listOf(Locale.US, Locale.UK)
        PronunciationAccent.BRITISH -> listOf(Locale.UK, Locale.US)
    }
    return candidates.firstOrNull { isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
}
