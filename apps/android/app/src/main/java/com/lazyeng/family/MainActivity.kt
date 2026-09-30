package com.lazyeng.family

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.lazyeng.family.core.designsystem.*
import com.lazyeng.family.feature.onboarding.*
import com.lazyeng.family.navigation.*
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private var pendingLink by mutableStateOf<String?>(null)
    var currentConfiguration by mutableStateOf(Configuration())
        private set

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        currentConfiguration = Configuration(newConfig)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingLink = intent.dataString
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending-navigation-link", pendingLink)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentConfiguration = Configuration(resources.configuration)
        pendingLink = if (savedInstanceState != null) savedInstanceState.getString("pending-navigation-link") else intent.dataString
        enableEdgeToEdge()
        val dependencies = application as LazyEngApplication
        setContent {
            CompositionLocalProvider(LocalConfiguration provides currentConfiguration) {
            LazyEngTheme {
                val onboarding: OnboardingViewModel = viewModel(factory = modelFactory { OnboardingViewModel(dependencies.onboardingCoordinator(), dependencies.clock) })
                val state by onboarding.state.collectAsStateWithLifecycle()
                // Durable initialization is the outer gate, not a saved authorization route flag.
                // EnterHome effects cannot reset a restored player/parent/tab back stack.
                if (state.phase != OnboardingPhase.COMPLETE) {
                    val navigation = rememberNavController()
                    NavHost(navigation, startDestination = "onboarding") { composable("onboarding") { OnboardingRoute(onboarding) } }
                } else {
                    val scope: NavigationScopeViewModel = viewModel(factory = modelFactory {
                        NavigationScopeViewModel(dependencies::navigationScope, dependencies.profileChanges.events.map { Unit })
                    })
                    val selection by scope.state.collectAsStateWithLifecycle()
                    val owner = LocalLifecycleOwner.current
                    DisposableEffect(owner, scope) {
                        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) scope.refresh() }
                        owner.lifecycle.addObserver(observer)
                        onDispose { owner.lifecycle.removeObserver(observer) }
                    }
                    when {
                        selection.loading -> Text("正在读取档案…", Modifier.safeDrawingPadding().padding(LazyEngSpacing.large))
                        selection.failed -> Column(Modifier.safeDrawingPadding().padding(LazyEngSpacing.large)) {
                            Text("无法读取当前档案，请重试。")
                            SecondaryButton("重试", scope::refresh)
                        }
                        selection.scope == null -> ExistingDestination(dependencies, ShellDestination.Profiles, {}, { scope.refresh() })
                    }
                    ProfileNavigationScope(selection.scope) {
                        ChildShell(
                            pendingLink = pendingLink,
                            onLinkConsumed = { pendingLink = null },
                            tabContent = { tab, memory, go ->
                                when (tab) {
                                    ChildTab.HOME -> HomeTabDestination(dependencies, memory, go)
                                    ChildTab.LIBRARY -> LibraryTabDestination(dependencies, memory, go)
                                    ChildTab.VOCABULARY -> VocabularyTabDestination(dependencies, memory, go)
                                    ChildTab.ME -> MineTabDestination(dependencies, memory, go)
                                }
                            },
                        ) { destination, back, go ->
                            ExistingDestination(dependencies, destination, back, go)
                        }
                    }
                }
            }
            }
        }
    }
}
