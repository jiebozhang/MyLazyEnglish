package com.lazyeng.family.feature.profiles

import androidx.lifecycle.ViewModelStore
import com.lazyeng.family.core.testing.TestDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MineViewModelTest {
    @Test fun loadingCompletesWithCurrentProfileSummary() = runTest {
        Dispatchers.setMain(TestDispatchers(testScheduler).dispatcher)
        val store = ViewModelStore()
        try {
            val vm = MineViewModel({ MineProfileInfo("Fixture Learner", "star", "child", "Power Up 2", 3, "Today done") })
            store.put("mine", vm)
            assertEquals(MineUiState.Loading, vm.state.value)
            advanceUntilIdle()
            val content = vm.state.value as MineUiState.Content
            assertEquals("Fixture Learner", content.profile.nickname)
            assertEquals(3, content.profile.streakDays)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun refreshShowsLoadingAndThenUpdatedData() = runTest {
        Dispatchers.setMain(TestDispatchers(testScheduler).dispatcher)
        val gate = CompletableDeferred<MineProfileInfo?>()
        val store = ViewModelStore()
        try {
            val vm = MineViewModel({ gate.await() })
            store.put("mine", vm)
            assertEquals(MineUiState.Loading, vm.state.value)
            gate.complete(MineProfileInfo("New Learner", "moon", "child", "A1", 0, "Start today"))
            advanceUntilIdle()
            assertEquals("New Learner", (vm.state.value as MineUiState.Content).profile.nickname)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun navigationRequestsAreOneShotEffects() = runTest {
        Dispatchers.setMain(TestDispatchers(testScheduler).dispatcher)
        val store = ViewModelStore()
        try {
            val vm = MineViewModel({ null })
            store.put("mine", vm)
            val switch = async { vm.effects.first() }
            vm.onEvent(MineUiEvent.SwitchProfile)
            advanceUntilIdle()
            assertEquals(MineUiEffect.NavigateToProfiles, switch.await())
            val parent = async { vm.effects.first() }
            vm.onEvent(MineUiEvent.ParentSettings)
            advanceUntilIdle()
            assertEquals(MineUiEffect.NavigateToParentGate, parent.await())
            assertTrue(vm.state.value is MineUiState.Error)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
