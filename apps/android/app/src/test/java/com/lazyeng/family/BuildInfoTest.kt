package com.lazyeng.family

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildInfoTest {
    @Test
    fun debugBuildHasStableVersionName() {
        assertEquals("0.1.0", BuildConfig.VERSION_NAME)
    }
}
