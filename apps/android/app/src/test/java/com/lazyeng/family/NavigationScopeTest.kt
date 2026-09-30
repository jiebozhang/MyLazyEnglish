package com.lazyeng.family

import androidx.lifecycle.ViewModelStore
import com.lazyeng.family.navigation.NavigationScopeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationScopeTest {
    @Test fun refreshClearsOldScopeAndCancelledReadCannotPublishStaleResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val holder = ViewModelStore()
        try {
            var next = CompletableDeferred<String?>().apply { complete("fixture-a") }
            val changes = MutableSharedFlow<Unit>()
            val model = NavigationScopeViewModel({ next.await() }, changes)
            holder.put("scope", model)
            runCurrent()
            assertEquals("fixture-a", model.state.value.scope)
            next = CompletableDeferred()
            model.refresh()
            assertNull(model.state.value.scope)
            assertTrue(model.state.value.loading)
            runCurrent()
            val cancelled = next
            next = CompletableDeferred<String?>().apply { complete("fixture-b") }
            model.refresh()
            runCurrent()
            cancelled.complete("stale")
            runCurrent()
            assertEquals("fixture-b", model.state.value.scope)
            next = CompletableDeferred<String?>().apply { complete(null) }
            changes.emit(Unit)
            runCurrent()
            assertNull(model.state.value.scope)
            assertFalse(model.state.value.failed)
        } finally { holder.clear(); Dispatchers.resetMain() }
    }
    @Test fun failedSelectionReadIsAnErrorNotAnImplicitDefaultProfile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val holder = ViewModelStore()
        try {
            val model = NavigationScopeViewModel({ error("fixture failure") }, MutableSharedFlow())
            holder.put("scope", model)
            runCurrent()
            assertTrue(model.state.value.failed)
            assertNull(model.state.value.scope)
        } finally { holder.clear(); Dispatchers.resetMain() }
    }
}
