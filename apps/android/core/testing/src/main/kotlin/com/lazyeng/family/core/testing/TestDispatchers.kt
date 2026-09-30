package com.lazyeng.family.core.testing

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher

class TestDispatchers(
    val scheduler: TestCoroutineScheduler = TestCoroutineScheduler(),
) {
    val dispatcher: TestDispatcher = StandardTestDispatcher(scheduler)
}
