package com.lazyeng.family

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.feature.onboarding.OnboardingPhase
import com.lazyeng.family.feature.onboarding.OnboardingRoute
import com.lazyeng.family.feature.onboarding.OnboardingUiEffect
import com.lazyeng.family.feature.onboarding.OnboardingViewModel
import com.lazyeng.family.feature.profiles.ProfilesViewModel
import com.lazyeng.family.feature.profiles.ProfilesRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val dependencies = application as LazyEngApplication
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                OnboardingViewModel(dependencies.onboardingCoordinator(), dependencies.clock) as T
        }
        val profilesFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ProfilesViewModel(dependencies::profileCoordinator, dependencies.clock) as T
        }
        setContent {
            LazyEngTheme {
                val onboarding: OnboardingViewModel = viewModel(factory = factory)
                val state by onboarding.state.collectAsStateWithLifecycle()
                val navigation = rememberNavController()
                LaunchedEffect(onboarding, navigation) {
                    onboarding.effects.collect { effect ->
                        if (effect == OnboardingUiEffect.EnterHome && onboarding.state.value.phase == OnboardingPhase.COMPLETE) {
                            navigation.navigate("home") { popUpTo("onboarding") { inclusive = true }; launchSingleTop = true }
                        }
                    }
                }
                NavHost(navigation, startDestination = "onboarding") {
                    composable("onboarding") { OnboardingRoute(onboarding) }
                    composable("home") {
                        // A restored back stack cannot bypass durable initialization checks.
                        if (state.phase == OnboardingPhase.COMPLETE) {
                            val profiles: ProfilesViewModel = viewModel(factory = profilesFactory)
                            ProfilesRoute(profiles)
                        } else OnboardingRoute(onboarding)
                    }
                }
            }
        }
    }
}
