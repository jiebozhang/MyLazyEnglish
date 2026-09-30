package com.lazyeng.family.feature.player

import android.speech.tts.TextToSpeech
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemPronunciationPlayerTest {
    @Test
    fun preferredAccentIsChosenWhenAvailable() {
        val locale = resolvePronunciationLocale(PronunciationAccent.AMERICAN) { available ->
            if (available == Locale.US) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
        }

        assertEquals(Locale.US, locale)
    }

    @Test
    fun availableEnglishAccentIsUsedAsFallback() {
        val locale = resolvePronunciationLocale(PronunciationAccent.AMERICAN) { available ->
            if (available == Locale.UK) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_MISSING_DATA
        }

        assertEquals(Locale.UK, locale)
    }

    @Test
    fun unavailableEnglishVoicesAreReportedAsUnavailable() {
        val locale = resolvePronunciationLocale(PronunciationAccent.BRITISH) { TextToSpeech.LANG_NOT_SUPPORTED }

        assertNull(locale)
    }
}
