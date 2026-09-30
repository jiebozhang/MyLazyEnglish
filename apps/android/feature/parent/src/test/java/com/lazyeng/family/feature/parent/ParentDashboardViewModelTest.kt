package com.lazyeng.family.feature.parent

import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.testing.TestDispatchers
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ParentDashboardViewModelTest {
    private val testDispatchers = TestDispatchers()

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun profileSelectionReloadsScopedSnapshotWithoutChangingOtherProfiles() = runTest(testDispatchers.dispatcher) {
        Dispatchers.setMain(testDispatchers.dispatcher)
        val first = profile("child-a", "A")
        val second = profile("child-b", "B")
        val loaded = mutableListOf<ProfileId>()
        val source = ParentDashboardDataSource { id ->
            loaded += id
            ParentDashboardData(id, 2, if (id == first.id) 1 else 3, 1, emptyList())
        }
        val vm = ParentDashboardViewModel(listOf(first, second), first.id, source, provider())

        advanceUntilIdle()
        val initial = vm.state.value as ParentDashboardUiState.Content
        assertEquals(first.id, initial.selectedProfileId)
        assertEquals(1, initial.data.vocabularyCount)

        vm.onEvent(ParentDashboardUiEvent.SelectProfile(second.id))
        advanceUntilIdle()
        val selected = vm.state.value as ParentDashboardUiState.Content
        assertEquals(second.id, selected.selectedProfileId)
        assertEquals(3, selected.data.vocabularyCount)
        assertEquals(listOf(first.id, second.id), loaded)
    }

    @Test fun unavailableDataReturnsRecoverableErrorAndActionsAreExplicit() = runTest(testDispatchers.dispatcher) {
        Dispatchers.setMain(testDispatchers.dispatcher)
        val child = profile("child", "Kid")
        val vm = ParentDashboardViewModel(listOf(child), child.id,
            ParentDashboardDataSource { error("storage unavailable") }, provider())
        advanceUntilIdle()
        assertTrue(vm.state.value is ParentDashboardUiState.Error)
        vm.onEvent(ParentDashboardUiEvent.Refresh)
        advanceUntilIdle()
        assertTrue(vm.state.value is ParentDashboardUiState.Error)
    }

    @Test fun llmSettingsActionEmitsProtectedParentRouteEffect() = runTest(testDispatchers.dispatcher) {
        Dispatchers.setMain(testDispatchers.dispatcher)
        val child = profile("child", "Kid")
        val vm = ParentDashboardViewModel(listOf(child), child.id,
            ParentDashboardDataSource { ParentDashboardData(it, 0, 0, 0, emptyList()) }, provider())
        advanceUntilIdle()
        vm.onEvent(ParentDashboardUiEvent.LlmSettings)
        assertEquals(ParentDashboardUiEffect.OpenLlmSettings, vm.effects.first())
    }

    private fun provider() = object : DispatcherProvider {
        override val io = testDispatchers.dispatcher
        override val computation = testDispatchers.dispatcher
        override val main = testDispatchers.dispatcher
    }

    private fun profile(id: String, name: String) = Profile(
        ProfileId(id), FamilyId("family"), name, null, ProfileRole.CHILD, "child", "A1", emptyMap(), Instant.EPOCH, null,
    )
}
