package com.lazyeng.family.feature.dictionary

import android.database.sqlite.SQLiteDatabase
import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.testing.TestDispatchers
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SqliteDictionaryRepositoryTest {
    private lateinit var file: File
    private lateinit var repository: SqliteDictionaryRepository
    private lateinit var testDispatchers: TestDispatchers

    @Before
    fun setUp() {
        file = File(RuntimeEnvironment.getApplication().cacheDir, "fixture-dictionary.sqlite")
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.version = 1
            db.execSQL("CREATE TABLE dictionary (word TEXT NOT NULL COLLATE NOCASE PRIMARY KEY, phonetic TEXT NOT NULL, definition TEXT NOT NULL, translation TEXT NOT NULL, pos TEXT NOT NULL, bnc INTEGER, frq INTEGER) WITHOUT ROWID")
            db.execSQL("INSERT INTO dictionary VALUES ('hello', 'həˈləʊ', '', '你好' || char(10) || '您好', 'n.', 1, 2)")
        }
        testDispatchers = TestDispatchers()
        repository = SqliteDictionaryRepository(file, object : DispatcherProvider {
            override val io = testDispatchers.dispatcher
            override val computation = testDispatchers.dispatcher
            override val main = testDispatchers.dispatcher
        })
    }

    @After
    fun tearDown() {
        repository.close()
        file.delete()
    }

    @Test
    fun exactLookupMapsDomainFieldsWithoutInventingCefr() = runTest(testDispatchers.scheduler) {
        val entry = repository.findEntry("  HELLO ")!!
        assertEquals("hello", entry.lemma)
        assertEquals("n.", entry.partOfSpeech)
        assertEquals("həˈləʊ", entry.phonetic)
        assertEquals(listOf("你好", "您好"), entry.senses)
        assertNull(entry.cefr)
        assertNull(entry.frequencySource)
        assertTrue(file.isFile)
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertThrows(android.database.sqlite.SQLiteException::class.java) {
                db.execSQL("INSERT INTO dictionary VALUES ('write-attempt', '', '', '', '', NULL, NULL)")
            }
        }
    }

    @Test
    fun missesAndBlankLemmasReturnNull() = runTest(testDispatchers.scheduler) {
        assertNull(repository.findEntry("not-in-fixture"))
        assertNull(repository.findEntry("   "))
    }

    @Test
    fun rejectsUnsupportedDatabaseVersion() = runTest(testDispatchers.scheduler) {
        repository.close()
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 2 }
        try {
            repository.findEntry("hello")
            fail("Unsupported schema should be rejected")
        } catch (_: android.database.sqlite.SQLiteException) {
            // Expected: dictionary schema versions are independent of Room migrations.
        }
    }
}
