package com.lazyeng.family

import android.os.SystemClock
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.VocabularyItem
import com.lazyeng.family.core.model.VocabularyStatus
import com.lazyeng.family.feature.vocabulary.VocabularyRow
import com.lazyeng.family.feature.vocabulary.VocabularyScreen
import com.lazyeng.family.feature.vocabulary.VocabularyUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Device measurements use packaged or synthetic data, never the installed household database. */
class PerformanceIntegrityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<PhysicalRotationHostActivity>()

    @Test fun bundledDictionaryLookupReportsP50AndP95() = runBlocking(Dispatchers.IO) {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LazyEngApplication
        application.ensureDictionaryReady()
        val sentence = "She is running home."
        repeat(20) { assertEquals("run", application.lookupDictionary(sentence, 7, 14)?.matchedLemma) }
        val nanos = LongArray(1_000) {
            val start = SystemClock.elapsedRealtimeNanos()
            assertEquals("run", application.lookupDictionary(sentence, 7, 14)?.matchedLemma)
            SystemClock.elapsedRealtimeNanos() - start
        }.sorted()
        val p50 = nanos[499] / 1_000_000.0
        val p95 = nanos[949] / 1_000_000.0
        println("INT3_LOOKUP_LATENCY count=1000 p50_ms=$p50 p95_ms=$p95")
        assertTrue("PRD lookup budget exceeded: p95=$p95 ms", p95 < 300.0)
    }

    @Test fun fiveHundredVocabularyRowsRemainScrollable() {
        val profile = ProfileId("int3-synthetic-profile")
        val rows = (1L..501L).map { index ->
            VocabularyRow(VocabularyItem(profile, "word-$index", VocabularyStatus.LEARNING, null, id = index), null, null)
        }
        compose.setContent {
            LazyEngTheme {
                VocabularyScreen(VocabularyUiState(loading = false, rows = rows), rememberLazyListState(), onEvent = {})
            }
        }
        val started = SystemClock.elapsedRealtimeNanos()
        compose.onNodeWithTag("vocabulary-list").performScrollToIndex(500)
        compose.onNodeWithTag("vocabulary-card-501").assertExists()
        compose.waitForIdle()
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
        println("INT3_VOCABULARY_SCROLL rows=501 last_visible_ms=$elapsedMs")
    }

}
