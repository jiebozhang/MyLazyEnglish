package com.lazyeng.family

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Test host mirrors MainActivity's explicit configuration hand-off to Compose. */
class PhysicalRotationHostActivity : ComponentActivity() {
    var currentConfiguration by mutableStateOf(Configuration())
        private set

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        currentConfiguration = Configuration(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        currentConfiguration = Configuration(newConfig)
    }
}
