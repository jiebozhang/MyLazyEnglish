package com.lazyeng.family.feature.dictionary

import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.testing.TestDispatchers
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LookupEventCoordinatorTest {
    private val testDispatchers = TestDispatchers()

    @Test fun equalConcurrentTapsShareOneLookupAndAppendOneEvent() = runTest(testDispatchers.scheduler) {
        val gate = CompletableDeferred<LocalDictionaryLookup?>()
        var queryCount = 0
        val repository = RecordingLookupEvents()
        val scope = CoroutineScope(SupervisorJob() + testDispatchers.dispatcher)
        try {
            val coordinator = coordinator(scope, repository, { queryCount++; gate.await() })
            coordinator.activateProfile(profileA)
            val first = async { coordinator.lookup(request()) }
            runCurrent()
            val second = async { coordinator.lookup(request("run")) }
            val third = async { coordinator.lookup(request()) }
            runCurrent()
            assertEquals(1, queryCount)

            gate.complete(hit("run"))
            assertEquals("run", first.await()?.matchedLemma)
            assertEquals("run", second.await()?.matchedLemma)
            assertEquals("run", third.await()?.matchedLemma)
            assertEquals(1, repository.events.size)
            assertEquals(profileA, repository.events.single().profileId)
            assertEquals("run", repository.events.single().lemma)
            assertEquals(line, repository.events.single().lineId)
        } finally {
            scope.cancel()
        }
    }

    @Test fun switchingProfileCancelsOldRequestAndItCannotWriteIntoNewProfile() = runTest(testDispatchers.scheduler) {
        val gate = CompletableDeferred<LocalDictionaryLookup?>()
        var queryCancelled = false
        val repository = RecordingLookupEvents()
        val scope = CoroutineScope(SupervisorJob() + testDispatchers.dispatcher)
        try {
            val coordinator = coordinator(scope, repository, {
                try { gate.await() } catch (cancelled: CancellationException) {
                    queryCancelled = true
                    throw cancelled
                }
            })
            coordinator.activateProfile(profileA)
            val pending = async { coordinator.lookup(request()) }
            runCurrent()
            coordinator.activateProfile(profileB)
            runCurrent()

            assertTrue(queryCancelled)
            assertTrue(runCatching { pending.await() }.isFailure)
            gate.complete(hit("run"))
            assertTrue(repository.events.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private fun coordinator(
        scope: CoroutineScope,
        repository: RecordingLookupEvents,
        query: suspend () -> LocalDictionaryLookup?,
    ) = LookupEventCoordinator(
        scope = scope,
        events = repository,
        clock = Clock { Instant.parse("2026-01-02T03:04:05Z") },
        newId = { "fixture-event-id" },
        isProfileCurrent = { it == profileA },
        query = { query() },
    )

    private fun request(surface: String = "running") = LookupRequest(
        profileId = profileA,
        videoId = VideoId("fixture-video"),
        surface = surface,
        lemma = surface,
        lineId = line,
        sentence = if (surface == "run") "They run daily." else "They are running.",
        start = if (surface == "run") 5 else 9,
        end = if (surface == "run") 8 else 16,
    )

    private fun hit(lemma: String) = LocalDictionaryLookup(
        surface = "running",
        sentence = "They are running.",
        start = 9,
        end = 16,
        kind = LookupSelectionKind.WORD,
        matchedLemma = lemma,
        entry = null,
        missReason = null,
        spellingMayBeIncorrect = false,
    )

    private class RecordingLookupEvents : LookupEventRepository {
        val events = mutableListOf<LookupEvent>()
        override suspend fun append(profileId: ProfileId, event: LookupEvent) {
            assertEquals(profileId, event.profileId)
            if (events.none { it.profileId == profileId && it.lemma == event.lemma && it.lineId == event.lineId }) events += event
        }
        override suspend fun listEvents(profileId: ProfileId, from: Instant, to: Instant) =
            events.filter { it.profileId == profileId && it.occurredAt in from..to }
    }

    private companion object {
        val profileA = ProfileId("fixture-profile-a")
        val profileB = ProfileId("fixture-profile-b")
        val line = SubtitleLineId("fixture-line")
    }
}
