package com.lazyeng.family.feature.dictionary

import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.model.EventUuid
import com.lazyeng.family.core.model.LookupEvent
import com.lazyeng.family.core.model.LookupEventId
import com.lazyeng.family.core.model.LookupEventRepository
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.SubtitleLineId
import com.lazyeng.family.core.model.VideoId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

data class LookupRequest(
    val profileId: ProfileId,
    val videoId: VideoId,
    val surface: String,
    val lemma: String,
    val lineId: SubtitleLineId,
    val sentence: String,
    val start: Int,
    val end: Int,
)

/** Shares equal in-flight lookups and records one append-only event per profile/lemma/line. */
class LookupEventCoordinator(
    private val scope: CoroutineScope,
    private val events: LookupEventRepository,
    private val clock: Clock,
    private val newId: () -> String,
    private val isProfileCurrent: suspend (ProfileId) -> Boolean,
    private val query: suspend (LookupRequest) -> LocalDictionaryLookup?,
) {
    private data class ScopeKey(val profileId: ProfileId, val lineId: SubtitleLineId)
    private data class Flight(
        val normalizedLemma: String,
        val candidates: Set<String>,
        val task: Deferred<LocalDictionaryLookup?>,
    )

    private val monitor = Any()
    private var activeProfileId: ProfileId? = null
    private val inFlight = mutableMapOf<ScopeKey, MutableList<Flight>>()

    fun activateProfile(profileId: ProfileId?) {
        val cancelled = synchronized(monitor) {
            activeProfileId = profileId
            inFlight.filterKeys { it.profileId != profileId }.values.flatten().map { it.task }.also { jobs ->
                inFlight.entries.removeAll { it.key.profileId != profileId }
            }
        }
        cancelled.forEach { it.cancel() }
    }

    suspend fun lookup(request: LookupRequest): LocalDictionaryLookup? {
        val key = ScopeKey(request.profileId, request.lineId)
        val normalizedLemma = request.lemma.trim().lowercase(Locale.ROOT)
        val candidates = candidateSet(request, normalizedLemma)
        val task = synchronized(monitor) {
            if (activeProfileId != request.profileId) return null
            val bucket = inFlight.getOrPut(key) { mutableListOf() }
            bucket.firstOrNull { existing ->
                existing.normalizedLemma == normalizedLemma || existing.candidates.intersect(candidates).size == 1
            }?.task ?: scope.async(start = CoroutineStart.LAZY) {
                val result = query(request)
                if (isActiveFor(request.profileId) && isProfileCurrent(request.profileId) && isActiveFor(request.profileId)) {
                    val persistedLemma = result?.matchedLemma?.takeIf(String::isNotBlank) ?: normalizedLemma
                    events.append(
                        request.profileId,
                        LookupEvent(
                            id = LookupEventId(newId()),
                            eventUuid = EventUuid(newId()),
                            profileId = request.profileId,
                            word = request.surface,
                            lemma = persistedLemma,
                            lineId = request.lineId,
                            videoId = request.videoId,
                            occurredAt = clock.now(),
                        ),
                    )
                }
                result
            }.let { deferred ->
                val flight = Flight(normalizedLemma, candidates, deferred)
                bucket += flight
                deferred.invokeOnCompletion {
                    synchronized(monitor) {
                        inFlight[key]?.let { flights ->
                            flights.remove(flight)
                            if (flights.isEmpty()) inFlight.remove(key)
                        }
                    }
                }
                deferred.start()
                deferred
            }
        }
        return task.await()
    }

    private fun candidateSet(request: LookupRequest, fallback: String): Set<String> {
        val selection = when (val parsed = DictionarySelectionParser.fromRange(request.sentence, request.start, request.end)) {
            is SelectionResult.Accepted -> parsed.selection
            is SelectionResult.Rejected -> return setOf(fallback)
        }
        val forms = if (selection.kind == LookupSelectionKind.PHRASE) listOf(selection.queryForm)
        else EnglishLemmaCandidates.candidates(selection.queryForm, selection.kind)
        return forms.map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty).toSet().ifEmpty { setOf(fallback) }
    }

    private fun isActiveFor(profileId: ProfileId): Boolean = synchronized(monitor) {
        activeProfileId == profileId
    }
}
