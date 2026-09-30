package com.lazyeng.family.core.testing

import com.lazyeng.family.core.testing.fixtures.TestFixtures
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TestFixturesAndFakesTest {
    @Test
    fun fixtureFactoriesReturnStableData() {
        assertEquals(TestFixtures.profile(), TestFixtures.profile())
        assertEquals(TestFixtures.video(), TestFixtures.video())
        assertEquals(TestFixtures.subtitleLine(), TestFixtures.subtitleLine())
        assertEquals(TestFixtures.dictionaryEntry(), TestFixtures.dictionaryEntry())
        assertEquals(TestFixtures.vocabularyItem(), TestFixtures.vocabularyItem())
        assertEquals(TestFixtures.profile().id, TestFixtures.vocabularyItem().profileId)
    }

    @Test
    fun clockAndIdsAdvanceOnlyWhenRequested() {
        val clock = FakeClock()
        val ids = FakeIdGenerator()

        assertEquals(Instant.EPOCH, clock.now)
        assertEquals("test-id-0", ids.nextId())
        clock.advanceBy(Duration.ofSeconds(5))
        assertEquals(Instant.EPOCH.plusSeconds(5), clock.now)
        assertEquals("test-id-1", ids.nextId())
    }

    @Test
    fun networkStateChangesOnlyWhenControlled() {
        val network = FakeNetworkMonitor(FakeNetworkState.OFFLINE)
        assertEquals(FakeNetworkState.OFFLINE, network.state.value)

        network.setState(FakeNetworkState.ONLINE)

        assertEquals(FakeNetworkState.ONLINE, network.state.value)
    }

    @Test
    fun testDispatcherUsesItsVirtualScheduler() = runTest {
        val dispatchers = TestDispatchers(testScheduler)
        var ran = false

        launch(dispatchers.dispatcher) { ran = true }
        assertFalse(ran)

        runCurrent()

        assertTrue(ran)
    }
}
