package com.lazyeng.family.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NavigationScopeState(val loading: Boolean = true, val scope: String? = null, val failed: Boolean = false)

class NavigationScopeViewModel(private val load: suspend () -> String?, changes: Flow<Unit>) : ViewModel() {
    private val mutable = MutableStateFlow(NavigationScopeState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    init { refresh(); viewModelScope.launch { changes.collect { refresh() } } }
    fun refresh() {
        operation?.cancel()
        // Remove the previous scope before the first suspension, including when selection was deleted.
        mutable.value = NavigationScopeState()
        operation = viewModelScope.launch {
            try { mutable.value = NavigationScopeState(loading = false, scope = load()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = NavigationScopeState(loading = false, failed = true) }
        }
    }
}
