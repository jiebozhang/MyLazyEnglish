package com.lazyeng.family.feature.onboarding

import androidx.lifecycle.ViewModelStore
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.datastore.OnboardingDraft
import com.lazyeng.family.core.datastore.OnboardingDraftStore
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.security.*
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingTest {
    private val dispatcher = StandardTestDispatcher()
    private val holder = ViewModelStore()
    private val fixture = Fixture()
    private val clock = Clock { Instant.parse("2026-01-01T00:00:00Z") }
    private val sample = CharArray(4) { ('0'.code + it).toChar() }
    private val pins = PinService(fixture, clock,
        PinDerivation { pin, _, _ -> ByteArray(32) { pin[it % pin.size].code.toByte() } }, dispatcher)
    private fun coordinator() = OnboardingCoordinator(fixture, pins, fixture, fixture, fixture)
    private fun vm(): OnboardingViewModel = OnboardingViewModel(coordinator(), clock).also { holder.put("onboarding", it) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { holder.clear(); Dispatchers.resetMain() }

    private fun runOnboardingTest(body: suspend TestScope.() -> Unit) = runTest {
        try { body() } finally { holder.clear() }
    }

    @Test fun cannotSkipPinOrCreateProfileBySendingEvents() = runOnboardingTest {
        val vm = vm(); runCurrent()
        vm.onEvent(OnboardingUiEvent.CreateProfile)
        vm.onEvent(OnboardingUiEvent.EnterHome)
        runCurrent()
        assertEquals(OnboardingPhase.SET_PIN, vm.state.value.phase)
        assertNull(fixture.family)
        assertNull(fixture.profile)
    }

    @Test fun mismatchedPinNeverCreatesVerifierOrFamily() = runOnboardingTest {
        val vm = vm(); runCurrent()
        sample.forEach { vm.onEvent(OnboardingUiEvent.Digit(it.digitToInt())) }
        vm.onEvent(OnboardingUiEvent.SubmitPin); runCurrent()
        assertEquals(OnboardingPhase.CONFIRM_PIN, vm.state.value.phase)
        sample.reversed().forEach { vm.onEvent(OnboardingUiEvent.Digit(it.digitToInt())) }
        vm.onEvent(OnboardingUiEvent.SubmitPin); runCurrent()
        assertEquals(OnboardingPhase.SET_PIN, vm.state.value.phase)
        assertNotNull(vm.state.value.message)
        assertNull(fixture.record)
        assertNull(fixture.family)
    }

    @Test fun backgroundClearsPinAndConfirmationWithoutPersistingSecret() = runOnboardingTest {
        val vm = vm(); runCurrent()
        sample.forEach { vm.onEvent(OnboardingUiEvent.Digit(it.digitToInt())) }
        vm.onEvent(OnboardingUiEvent.SubmitPin); runCurrent()
        vm.onBackground()
        assertEquals(0, vm.state.value.enteredCount)
        assertEquals(OnboardingPhase.SET_PIN, vm.state.value.phase)
        assertNull(fixture.record)
    }

    @Test fun profileDraftSurvivesNewViewModelButNeedsPinBeforeSaving() = runOnboardingTest {
        coordinator().setPin(sample)
        fixture.saveDetails("Fixture child", "Fixture level", "star")
        val vm = vm(); runCurrent()
        assertEquals(OnboardingPhase.VERIFY_PIN, vm.state.value.phase)
        assertEquals("Fixture child", vm.state.value.nickname)
        assertEquals("star", vm.state.value.avatar)
        sample.forEach { vm.onEvent(OnboardingUiEvent.Digit(it.digitToInt())) }
        vm.onEvent(OnboardingUiEvent.SubmitPin); runCurrent()
        assertEquals(OnboardingPhase.CREATE_PROFILE, vm.state.value.phase)
        vm.onEvent(OnboardingUiEvent.CreateProfile); runCurrent()
        assertEquals(OnboardingPhase.COMPLETE, vm.state.value.phase)
        assertEquals(fixture.draft.profileId, fixture.selected)
        assertEquals("Fixture level", fixture.profile?.englishLevel)
        assertEquals(OnboardingPhase.COMPLETE, coordinator().restore().phase)
    }

    @Test fun pinCommitThenFamilyFailureRecoversWithoutResettingCredential() = runOnboardingTest {
        fixture.failFamilySave = true
        assertTrue(runCatching { coordinator().setPin(sample) }.isFailure)
        fixture.failFamilySave = false
        val recreated = coordinator()
        assertEquals(OnboardingPhase.VERIFY_PIN, recreated.restore().phase)
        assertEquals(PinCheck.Accepted, recreated.verify(sample))
        assertNotNull(fixture.family)
    }

    @Test fun existingFamilyWithMissingPinFailsClosed() = runOnboardingTest {
        fixture.family = Family(fixture.draft.familyId, emptyMap(), fixture.draft.createdAt)
        assertTrue(runCatching { coordinator().restore() }.isFailure)
        val vm = vm(); runCurrent()
        assertEquals(OnboardingPhase.ERROR, vm.state.value.phase)
    }

    @Test fun lockIsRestoredIntoUiStateAndNoAttemptIsAcceptedBeforeDeadline() = runOnboardingTest {
        coordinator().setPin(sample)
        repeat(5) { pins.verify(fixture.draft.familyId, sample.reversedArray()) }
        val vm = vm(); runCurrent()
        assertEquals(30L, vm.state.value.remainingSeconds)
        vm.onEvent(OnboardingUiEvent.Digit(1)); runCurrent()
        assertEquals(0, vm.state.value.enteredCount)
        assertEquals(5, fixture.record?.failures)
    }

    private class Fixture : FamilyRepository, ProfileRepository, CurrentProfileStore, OnboardingDraftStore, PinRecordStore {
        var draft = OnboardingDraft(FamilyId("fixture-family"), ProfileId("fixture-profile"), Instant.EPOCH)
        var family: Family? = null
        var profile: Profile? = null
        var record: PinRecord? = null
        var selected: ProfileId? = null
        var failFamilySave = false
        override suspend fun getFamily(familyId: FamilyId) = family?.takeIf { it.id == familyId }
        override suspend fun saveFamily(family: Family) { check(!failFamilySave); this.family = family }
        override suspend fun getProfile(familyId: FamilyId, profileId: ProfileId) =
            profile?.takeIf { it.familyId == familyId && it.id == profileId && it.deletedAt == null }
        override suspend fun saveProfile(familyId: FamilyId, profileId: ProfileId, profile: Profile) { this.profile = profile }
        override suspend fun deleteProfile(familyId: FamilyId, profileId: ProfileId, deletedAt: Instant) {
            getProfile(familyId, profileId)?.let { profile = it.copy(deletedAt = deletedAt) }
        }
        override suspend fun getCurrentProfileId(familyId: FamilyId) = selected
        override suspend fun selectProfile(familyId: FamilyId, profileId: ProfileId): Boolean {
            if (getProfile(familyId, profileId) == null) return false
            selected = profileId; return true
        }
        override suspend fun clearSelection(familyId: FamilyId) { selected = null }
        override suspend fun loadOrCreate() = draft
        override suspend fun saveDetails(nickname: String, level: String, avatar: String): OnboardingDraft {
            draft = draft.copy(nickname = nickname, level = level, avatar = avatar); return draft
        }
        override suspend fun read() = record
        override suspend fun write(record: PinRecord) { this.record = record }
    }
}
