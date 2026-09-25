package com.lazyeng.family.feature.profiles

import androidx.lifecycle.ViewModelStore
import com.lazyeng.family.core.common.*
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.security.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesTest {
    private fun scenario(body: suspend TestScope.(Fixture, ProfilesViewModel) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val holder = ViewModelStore()
        try {
            val f = Fixture(dispatcher)
            f.pins.setup(f.family, f.sample)
            val vm = ProfilesViewModel({ f.coordinator }, f.clock)
            holder.put("profiles", vm)
            runCurrent()
            body(f, vm)
        } finally { holder.clear(); Dispatchers.resetMain() }
    }

    @Test fun switchingClearsStateSynchronouslyAndPublishesScopedEvent() = scenario { f, vm ->
        val events = mutableListOf<ProfileChanged>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.changes.events.toList(events) }
        assertEquals(f.first, vm.state.value.current?.id)
        vm.onEvent(ProfilesUiEvent.Picker); runCurrent()
        f.readGate = CompletableDeferred()
        vm.onEvent(ProfilesUiEvent.Select(f.second))
        assertEquals(ProfilesPhase.LOADING, vm.state.value.phase)
        assertNull(vm.state.value.current)
        assertTrue(vm.state.value.choices.isEmpty())
        assertTrue(vm.state.value.members.isEmpty())
        runCurrent()
        assertTrue(events.isEmpty())
        f.readGate!!.complete(Unit); runCurrent()
        assertEquals(f.second, vm.state.value.current?.id)
        assertEquals(listOf(ProfileChanged(f.family, f.first, f.second)), events)
    }

    @Test fun roleNeverAuthorizesAndForgedEventsCannotMutate() = scenario { f, vm ->
        vm.onEvent(ProfilesUiEvent.Create)
        vm.onEvent(ProfilesUiEvent.Edit(f.first))
        vm.onEvent(ProfilesUiEvent.Delete(f.first))
        vm.onEvent(ProfilesUiEvent.Save); runCurrent()
        assertEquals(2, f.rows.size)
        assertFalse(f.parent.authorized())
        assertTrue(runCatching { f.coordinator.editor(null) }.isFailure)
        assertTrue(runCatching { f.coordinator.overview() }.isFailure)
    }

    @Test fun parentGateLoadingWrongPinThenCreateAndEditPreserveFields() = scenario { f, vm ->
        vm.onEvent(ProfilesUiEvent.Parent); runCurrent()
        submit(vm, f.sample.reversedArray()); runCurrent()
        assertEquals(ProfilesPhase.PIN, vm.state.value.phase)
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.message)
        f.pinGate = CompletableDeferred()
        submit(vm, f.sample); runCurrent()
        assertTrue(vm.state.value.busy)
        assertEquals(0, vm.state.value.enteredCount)
        f.pinGate!!.complete(Unit); runCurrent()
        assertEquals(ProfilesPhase.OVERVIEW, vm.state.value.phase)
        assertFalse(vm.state.value.busy)
        vm.onEvent(ProfilesUiEvent.Create); runCurrent()
        val draft = vm.state.value.editor!!.copy(nickname = "Fixture created", avatar = "moon", role = ProfileRole.ADULT,
            ageMode = "future-mode", level = "Fixture level")
        vm.onEvent(ProfilesUiEvent.Draft(draft)); vm.onEvent(ProfilesUiEvent.Save); runCurrent()
        assertEquals("future-mode", f.rows[draft.id]?.ageMode)
        vm.onEvent(ProfilesUiEvent.Edit(f.first)); runCurrent()
        assertNull(vm.state.value.editor?.avatar)
        vm.onEvent(ProfilesUiEvent.Draft(vm.state.value.editor!!.copy(nickname = "Fixture edited")))
        vm.onEvent(ProfilesUiEvent.Save); runCurrent()
        assertEquals(mapOf("fixture" to "retained"), f.rows[f.first]?.uiPreferences)
        assertEquals(Instant.EPOCH, f.rows[f.first]?.createdAt)
        assertNull(f.rows[f.first]?.avatarId)
    }

    @Test fun deletionRequiresFreshPinAndCancellationDoesNotDelete() = scenario { f, vm ->
        enterParent(f, vm)
        vm.onEvent(ProfilesUiEvent.Delete(f.first)); runCurrent()
        assertEquals(ProfilesPhase.DELETE_PIN, vm.state.value.phase)
        assertEquals(0, vm.state.value.enteredCount)
        submit(vm, f.sample.reversedArray()); runCurrent()
        assertNull(f.rows[f.first]?.deletedAt)
        vm.onEvent(ProfilesUiEvent.Back); runCurrent()
        assertNull(f.rows[f.first]?.deletedAt)
        enterParent(f, vm)
        vm.onEvent(ProfilesUiEvent.Delete(f.first)); runCurrent()
        submit(vm, f.sample); runCurrent()
        assertNotNull(f.rows[f.first]?.deletedAt)
        assertNull(f.rows[f.second]?.deletedAt)
        assertNull(f.selected)
        assertEquals(1, vm.state.value.members.size)
        assertEquals(1, f.cleaned)
    }

    @Test fun backgroundDuringVerificationCannotRestoreParentState() = scenario { f, vm ->
        vm.onEvent(ProfilesUiEvent.Parent); runCurrent()
        f.pinGate = CompletableDeferred()
        submit(vm, f.sample); runCurrent()
        vm.onBackground()
        assertNull(vm.state.value.deleting)
        assertTrue(vm.state.value.members.isEmpty())
        f.pinGate!!.complete(Unit); runCurrent()
        assertFalse(f.parent.authorized())
        assertEquals(ProfilesPhase.PICKER, vm.state.value.phase)
    }

    @Test fun parentExpiresAndLastDeletedProfileRestoresEmptyPicker() = scenario { f, vm ->
        enterParent(f, vm)
        f.now = f.now.plusSeconds(300)
        advanceTimeBy(251); runCurrent()
        assertEquals(ProfilesPhase.PICKER, vm.state.value.phase)
        assertTrue(vm.state.value.members.isEmpty())
        enterParent(f, vm)
        for (id in listOf(f.first, f.second)) {
            vm.onEvent(ProfilesUiEvent.Delete(id)); runCurrent()
            submit(vm, f.sample); runCurrent()
        }
        vm.onEvent(ProfilesUiEvent.Back); runCurrent()
        assertTrue(vm.state.value.choices.isEmpty())
        assertNull(f.coordinator.current())
    }

    @Test fun crossFamilyReadAndSelectionFailWithoutLeakingData() = scenario { f, _ ->
        assertNull(f.getProfile(FamilyId("other"), f.first))
        assertTrue(f.choices(FamilyId("other")).isEmpty())
        assertTrue(runCatching { f.coordinator.switchTo(ProfileId("absent")) }.isFailure)
        assertEquals(f.first, f.selected)
    }

    private suspend fun TestScope.enterParent(f: Fixture, vm: ProfilesViewModel) {
        vm.onEvent(ProfilesUiEvent.Parent); runCurrent()
        submit(vm, f.sample); runCurrent()
        assertEquals(ProfilesPhase.OVERVIEW, vm.state.value.phase)
    }
    private fun submit(vm: ProfilesViewModel, input: CharArray) {
        input.forEach { vm.onEvent(ProfilesUiEvent.Digit(it.digitToInt())) }
        vm.onEvent(ProfilesUiEvent.SubmitPin)
    }

    private class Fixture(dispatcher: CoroutineDispatcher) : ProfileRepository, ProfileDirectory, CurrentProfileStore, PinRecordStore {
        val family = FamilyId("fixture-family")
        val first = ProfileId("fixture-first")
        val second = ProfileId("fixture-second")
        val sample = CharArray(4) { ('0'.code + it).toChar() }
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val clock = Clock { now }
        val rows = listOf(first, second).associateWith { id -> Profile(id, family, "Fixture ${id.value}", null,
            ProfileRole.PARENT, "child", "Power Up 2", mapOf("fixture" to "retained"), Instant.EPOCH, null) }.toMutableMap()
        var selected: ProfileId? = first
        var record: PinRecord? = null
        var readGate: CompletableDeferred<Unit>? = null
        var pinGate: CompletableDeferred<Unit>? = null
        var cleaned = 0
        val pins = PinService(this, clock, PinDerivation { pin, _, _ -> ByteArray(32) { pin[it % pin.size].code.toByte() } }, dispatcher)
        val parent = ParentSession(family, pins, clock)
        val changes = ProfileChanges()
        val deletions = ProfileDeletionCoordinator(ProfileDeletionTransaction { block ->
            val before = rows.toMap()
            try { block() } catch (e: Exception) { rows.clear(); rows.putAll(before); throw e }
        }).apply { register("body") { deleteProfile(it.familyId, it.profileId, it.requestedAt); cleaned++ } }
        val coordinator = ProfileCoordinator(family, this, this, this, parent, deletions, changes, clock) { ProfileId("fixture-new") }
        override suspend fun getProfile(familyId: FamilyId, profileId: ProfileId): Profile? {
            readGate?.await()
            return rows[profileId]?.takeIf { it.familyId == familyId && it.deletedAt == null }
        }
        override suspend fun saveProfile(familyId: FamilyId, profileId: ProfileId, profile: Profile) { rows[profileId] = profile }
        override suspend fun deleteProfile(familyId: FamilyId, profileId: ProfileId, deletedAt: Instant) {
            getProfile(familyId, profileId)?.let { rows[profileId] = it.copy(deletedAt = deletedAt) }
        }
        override suspend fun choices(familyId: FamilyId) = rows.values.filter { it.familyId == familyId && it.deletedAt == null }
            .map { ProfileChoice(it.id, it.nickname, it.avatarId, it.role, it.englishLevel) }
        override suspend fun getCurrentProfileId(familyId: FamilyId): ProfileId? {
            if (selected?.let { getProfile(familyId, it) } == null) selected = null
            return selected
        }
        override suspend fun selectProfile(familyId: FamilyId, profileId: ProfileId): Boolean {
            if (getProfile(familyId, profileId) == null) return false
            selected = profileId; return true
        }
        override suspend fun clearSelection(familyId: FamilyId) { selected = null }
        override suspend fun read() = record
        override suspend fun write(record: PinRecord) { pinGate?.await(); this.record = record }
    }
}
