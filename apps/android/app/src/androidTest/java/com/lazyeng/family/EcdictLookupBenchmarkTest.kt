package com.lazyeng.family

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.feature.dictionary.DictionarySelection
import com.lazyeng.family.feature.dictionary.LocalDictionaryLookupUseCase
import com.lazyeng.family.feature.dictionary.LookupSelectionKind
import com.lazyeng.family.feature.dictionary.SqliteDictionaryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EcdictLookupBenchmarkTest {
    @Test
    fun tenThousandRandomExactLookupsOnDevice() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            assumeTrue(
                "Pass -e ecdict.benchmark true to opt in to the on-device benchmark",
                InstrumentationRegistry.getArguments().getString("ecdict.benchmark") == "true",
            )
            val application = instrumentation.targetContext.applicationContext as LazyEngApplication
            val dictionaryFile = application.ensureDictionaryReady()
            assertTrue(dictionaryFile.isFile)
            val words = SQLiteDatabase.openDatabase(
                dictionaryFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { db ->
                db.rawQuery("SELECT word FROM dictionary ORDER BY RANDOM() LIMIT 10000", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
            }
            assertEquals(10_000, words.size)

            val repository = SqliteDictionaryRepository(dictionaryFile, application.dispatchers)
            try {
                val lookup = LocalDictionaryLookupUseCase(repository)
                val durations = LongArray(words.size)
                words.forEachIndexed { index, word ->
                    val start = android.os.SystemClock.elapsedRealtimeNanos()
                    val result = lookup(DictionarySelection(word, word, word, 0, word.length, LookupSelectionKind.WORD))
                    val elapsed = android.os.SystemClock.elapsedRealtimeNanos() - start
                    assertNotNull(result.entry)
                    durations[index] = elapsed
                }
                durations.sort()
                val p50 = durations[(durations.size * 50 + 99) / 100 - 1] / 1_000_000.0
                val p95 = durations[(durations.size * 95 + 99) / 100 - 1] / 1_000_000.0
                instrumentation.sendStatus(2, Bundle().apply {
                    putInt("ecdict_queries", words.size)
                    putDouble("ecdict_p50_ms", p50)
                    putDouble("ecdict_p95_ms", p95)
                    putString("ecdict_device", android.os.Build.MODEL)
                    putBoolean("ecdict_warm_indexed_lookup_use_case", true)
                })
                Log.i(TAG, "device=${android.os.Build.MODEL}; api=${android.os.Build.VERSION.SDK_INT}; queries=${words.size}; p50_ms=$p50; p95_ms=$p95; warm_indexed_lookup_use_case=true")
            } finally {
                repository.close()
            }
        }
    }

    private companion object {
        const val TAG = "EcdictBenchmark"
    }
}
