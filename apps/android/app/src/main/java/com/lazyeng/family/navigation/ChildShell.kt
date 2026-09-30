package com.lazyeng.family.navigation

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.lazyeng.family.core.designsystem.*
import com.lazyeng.family.feature.library.LibrarySubtitleFilter
import com.lazyeng.family.core.model.VocabularyStatus
import kotlinx.coroutines.flow.first

/** Owned by the tab's back-stack entry, so future real screens inherit the same save/restore boundary. */
@Stable
class TabMemory(
    val list: LazyListState,
    val filter: MutableState<String>,
    val level: MutableState<String> = mutableStateOf(""),
    val topic: MutableState<String> = mutableStateOf(""),
    val source: MutableState<String> = mutableStateOf(""),
    val subtitle: MutableState<String> = mutableStateOf(LibrarySubtitleFilter.ALL.name),
    val vocabularyStatus: MutableState<String> = mutableStateOf(""),
    val vocabularySource: MutableState<String> = mutableStateOf(""),
)

@Composable
private fun rememberTabMemory(): TabMemory {
    val list = rememberLazyListState()
    val filter = rememberSaveable { mutableStateOf("") }
    val level = rememberSaveable { mutableStateOf("") }
    val topic = rememberSaveable { mutableStateOf("") }
    val source = rememberSaveable { mutableStateOf("") }
    val subtitle = rememberSaveable { mutableStateOf(LibrarySubtitleFilter.ALL.name) }
    val vocabularyStatus = rememberSaveable { mutableStateOf("") }
    val vocabularySource = rememberSaveable { mutableStateOf("") }
    return remember(list, filter, level, topic, source, subtitle, vocabularyStatus, vocabularySource) {
        TabMemory(list, filter, level, topic, source, subtitle, vocabularyStatus, vocabularySource)
    }
}

@Composable
fun ProfileNavigationScope(scope: String?, content: @Composable () -> Unit) {
    val holder = rememberSaveableStateHolder()
    var previous by rememberSaveable { mutableStateOf(scope) }
    if (scope != null && previous != scope) {
        previous?.let(holder::removeState)
        previous = scope
    }
    if (scope != null) holder.SaveableStateProvider(scope) { content() }
}

@Composable
fun ChildShell(
    pendingLink: String? = null,
    onLinkConsumed: () -> Unit = {},
    tabContent: @Composable (ChildTab, TabMemory, (ShellDestination) -> Unit) -> Unit = { tab, _, go -> TabPlaceholder(tab, go) },
    destinationContent: @Composable (ShellDestination, () -> Unit, (ShellDestination) -> Unit) -> Unit,
) {
    val navigation = rememberNavController()
    val entry by navigation.currentBackStackEntryAsState()
    val currentTab = ChildTab.entries.firstOrNull { it.route == entry?.destination?.route }
    val go: (ShellDestination) -> Unit = { destination ->
        navigation.navigate(ShellRoutes.route(destination)) {
            launchSingleTop = true
            if (destination is ShellDestination.Tab) {
                popUpTo(navigation.graph.findStartDestination().id) { saveState = true }
                restoreState = true
            }
        }
    }
    BackHandler(enabled = currentTab != null && currentTab != ChildTab.HOME) {
        go(ShellDestination.Tab(ChildTab.HOME))
    }
    val back: () -> Unit = {
        if (!navigation.popBackStack()) go(ShellDestination.Tab(ChildTab.HOME))
    }
    LaunchedEffect(pendingLink) {
        pendingLink?.let {
            // Scaffold subcomposes NavHost after this effect can start. Await a real graph entry.
            navigation.currentBackStackEntryFlow.first()
            go(ShellRoutes.deepLink(it))
            onLinkConsumed()
        }
    }
    Scaffold(bottomBar = {
        if (currentTab != null) NavigationBar(Modifier.testTag("child-bottom-bar"), containerColor = LazyEngColors.Surface) {
            ChildTab.entries.forEach { tab ->
                NavigationBarItem(selected = currentTab == tab, onClick = { go(ShellDestination.Tab(tab)) },
                    modifier = Modifier.testTag("tab-${tab.route}").defaultMinSize(minHeight = LazyEngTouchTarget.minimum),
                    icon = { Icon(when (tab) {
                        ChildTab.HOME -> Icons.Default.Home
                        ChildTab.LIBRARY -> Icons.AutoMirrored.Filled.List
                        ChildTab.VOCABULARY -> Icons.Default.Star
                        ChildTab.ME -> Icons.Default.Person
                    }, contentDescription = null) }, label = { Text(tab.label) })
            }
        }
    }) { padding ->
        NavHost(navigation, startDestination = ChildTab.HOME.route,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            ChildTab.entries.forEach { tab -> composable(tab.route) { tabContent(tab, rememberTabMemory(), go) } }
            composable("profiles") { destinationContent(ShellDestination.Profiles, back, go) }
            composable("parent") { destinationContent(ShellDestination.Parent, back, go) }
            composable("review") { destinationContent(ShellDestination.Review, back, go) }
            composable("import") { destinationContent(ShellDestination.Import, back, go) }
            for (parent in listOf(false, true)) {
                composable("${if (parent) "parent-video" else "video"}/{videoId}") { item ->
                    val id = item.arguments?.getString("videoId")
                    if (ShellRoutes.validId(id)) destinationContent(ShellDestination.Video(id!!, parent), back, go)
                    else NavigationError(back)
                }
            }
            composable("repair/{videoId}") { item ->
                val id = item.arguments?.getString("videoId")
                if (ShellRoutes.validId(id)) destinationContent(ShellDestination.Repair(id!!), back, go) else NavigationError(back)
            }
            composable("player/{videoId}?positionMs={positionMs}", arguments = listOf(navArgument("positionMs") { type = NavType.LongType; defaultValue = 0L })) { item ->
                val id = item.arguments?.getString("videoId")
                val position = item.arguments?.getLong("positionMs") ?: -1L
                if (ShellRoutes.validId(id) && position >= 0) destinationContent(ShellDestination.Player(id!!, position), back, go)
                else NavigationError(back)
            }
            dialog("dictionary") {
                // Dialog destination keeps the player entry, its saved state and position alive underneath.
                if (navigation.previousBackStackEntry?.destination?.route?.startsWith("player/") == true) {
                    AlertDialog(onDismissRequest = back, title = { Text("词典") }, text = { Text("词典内容尚未接入") },
                        confirmButton = { TextButton(onClick = back, modifier = Modifier.testTag("close-dictionary")) { Text("返回") } })
                } else NavigationError(back)
            }
            composable("not-found") { NavigationError(back) }
        }
    }
}

@Composable
fun NavigationError(back: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(LazyEngSpacing.large), verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium)) {
        Text("无法打开此页面", style = MaterialTheme.typography.headlineSmall)
        Text("链接可能已失效，或当前档案无法访问该内容。请返回后重新选择。", Modifier.testTag("navigation-error"))
        SecondaryButton("返回", back, Modifier.testTag("navigation-back"))
    }
}

@Composable
fun TabPlaceholder(tab: ChildTab, go: (ShellDestination) -> Unit) {
    Column(Modifier.fillMaxSize().padding(LazyEngSpacing.large), verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium)) {
        Text(tab.label, style = MaterialTheme.typography.headlineSmall)
        Text("此页面尚未开放", color = LazyEngColors.TextSecondary)
        if (tab == ChildTab.ME) {
            PrimaryButton("切换档案", { go(ShellDestination.Profiles) })
            SecondaryButton("家长控制台", { go(ShellDestination.Parent) })
        }
    }
}
