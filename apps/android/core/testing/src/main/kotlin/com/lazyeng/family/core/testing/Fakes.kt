package com.lazyeng.family.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant

class FakeClock(initialTime: Instant = Instant.EPOCH) {
    var now: Instant = initialTime
        private set

    fun advanceBy(duration: Duration) {
        require(!duration.isNegative) { "duration must not be negative" }
        now = now.plus(duration)
    }

    fun set(instant: Instant) {
        now = instant
    }
}

class FakeIdGenerator(
    private val prefix: String = "test-id",
    startingAt: Long = 0L,
) {
    private var nextValue = startingAt.also { require(it >= 0L) }

    fun nextId(): String = "$prefix-${nextValue++}"
}

enum class FakeNetworkState {
    ONLINE,
    OFFLINE,
}

class FakeNetworkMonitor(initialState: FakeNetworkState = FakeNetworkState.ONLINE) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<FakeNetworkState> = mutableState.asStateFlow()

    fun setState(state: FakeNetworkState) {
        mutableState.value = state
    }
}
