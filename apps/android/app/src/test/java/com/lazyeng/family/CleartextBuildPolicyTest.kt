package com.lazyeng.family

import org.junit.Assert.assertFalse
import org.junit.Test

class CleartextBuildPolicyTest {
    @Test fun defaultDebugBuildDisallowsInsecureAiEndpoints() {
        assertFalse(BuildConfig.ALLOW_INSECURE_LOCAL_AI_ENDPOINTS)
    }
}
