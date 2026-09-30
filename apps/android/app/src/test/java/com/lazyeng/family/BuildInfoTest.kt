package com.lazyeng.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BuildInfoTest {
    @Test
    fun buildHasStableV1ReleaseIdentity() {
        assertEquals("1.0.0", BuildConfig.VERSION_NAME)
        assertEquals(2, BuildConfig.VERSION_CODE)
        assertEquals("com.lazyeng.family", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun releaseNeverEnablesLocalCleartextAi() {
        if (BuildConfig.BUILD_TYPE == "release") {
            assertFalse(BuildConfig.DEBUG)
            assertFalse(BuildConfig.ALLOW_INSECURE_LOCAL_AI_ENDPOINTS)
        }
    }
}
