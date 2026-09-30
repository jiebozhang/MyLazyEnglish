package com.lazyeng.family

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.feature.library.ChildVideoDetail
import com.lazyeng.family.feature.library.DetailAvailability
import com.lazyeng.family.feature.player.PlayerPhase
import com.lazyeng.family.feature.player.PlayerScreen
import com.lazyeng.family.feature.player.PlayerUiState
import com.lazyeng.family.navigation.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationShellTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val memories = mutableMapOf<ChildTab, TabMemory>()
    private val clip = ChildVideoDetail("Synthetic navigation clip", 60000, null, emptyList(), 0, null, DetailAvailability.READY)

    @Composable private fun FixtureTab(tab: ChildTab, memory: TabMemory, go: (ShellDestination) -> Unit) {
        SideEffect { memories[tab] = memory }
        Column {
            Text("${tab.route}:${memory.filter.value}", Modifier.testTag("filter-value"))
            Button(onClick = { memory.filter.value = "fixture-${tab.route}" }, modifier = Modifier.testTag("set-filter")) { Text("Set fixture filter") }
            Button(onClick = { go(ShellDestination.Player("fixture-video", 12345)) }, modifier = Modifier.testTag("open-player")) { Text("Open fixture origin") }
            Button(onClick = { go(ShellDestination.Parent) }, modifier = Modifier.testTag("open-parent-route")) { Text("Parent fixture route") }
            LazyColumn(state = memory.list, modifier = Modifier.testTag("fixture-list")) {
                items((0..99).toList()) { Text("Fixture row $it", Modifier.fillMaxWidth().height(48.dp)) }
            }
        }
    }
    @Composable private fun Destination(target: ShellDestination, back: () -> Unit, go: (ShellDestination) -> Unit) {
        when (target) {
            is ShellDestination.Player -> if (target.id == "fixture-video") {
                PlayerScreen(
                    state = PlayerUiState(PlayerPhase.READY, clip.title, durationMs = clip.durationMs ?: 0L),
                    player = null,
                    positionMs = MutableStateFlow(target.positionMs),
                    subtitles = MutableStateFlow(emptyList()),
                    activeSubtitleIndex = MutableStateFlow(null),
                    onEvent = {},
                )
            } else NavigationError(back)
            else -> Column { Text("PIN route fixture"); Button(onClick = back) { Text("Back fixture") } }
        }
    }
    @Composable private fun Harness(scope: String? = "fixture-a", link: String? = null) {
        ProfileNavigationScope(scope) { ChildShell(link, tabContent = { t, m, g -> FixtureTab(t, m, g) }, destinationContent = { t, b, g -> Destination(t, b, g) }) }
    }

    @Test fun allTabsRetainIndependentFiltersAndScroll() {
        compose.setContent { LazyEngTheme { Harness() } }
        ChildTab.entries.forEachIndexed { index, tab ->
            compose.onNodeWithTag("tab-${tab.route}").performClick()
            compose.onNodeWithTag("set-filter").performClick()
            compose.onNodeWithTag("fixture-list").performScrollToIndex(15 + index)
        }
        ChildTab.entries.forEachIndexed { index, tab ->
            compose.onNodeWithTag("tab-${tab.route}").performClick()
            compose.onNodeWithTag("filter-value").assertTextEquals("${tab.route}:fixture-${tab.route}")
            compose.runOnIdle { assertEquals(15 + index, memories.getValue(tab).list.firstVisibleItemIndex) }
        }
    }

    @Test fun savedStateRestoresActiveTabFilterAndScroll() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { LazyEngTheme { Harness() } }
        compose.onNodeWithTag("tab-vocabulary").performClick()
        compose.onNodeWithTag("set-filter").performClick()
        compose.onNodeWithTag("fixture-list").performScrollToIndex(24)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("tab-vocabulary").assertIsSelected()
        compose.onNodeWithTag("filter-value").assertTextEquals("vocabulary:fixture-vocabulary")
        compose.runOnIdle { assertEquals(24, memories.getValue(ChildTab.VOCABULARY).list.firstVisibleItemIndex) }
    }

    @Test fun profileChangeClearsAllPreviousTabStateAndRefreshPreservesSameScope() {
        val scope = mutableStateOf<String?>("fixture-a")
        compose.setContent { LazyEngTheme { Harness(scope.value) } }
        compose.onNodeWithTag("tab-library").performClick()
        compose.onNodeWithTag("set-filter").performClick()
        compose.runOnIdle { scope.value = null }
        compose.onNodeWithTag("child-bottom-bar").assertDoesNotExist()
        compose.runOnIdle { scope.value = "fixture-a" }
        compose.onNodeWithTag("filter-value").assertTextEquals("library:fixture-library")
        compose.runOnIdle { scope.value = "fixture-b" }
        compose.onNodeWithTag("tab-home").assertIsSelected()
        compose.onNodeWithTag("tab-library").performClick()
        compose.onNodeWithTag("filter-value").assertTextEquals("library:")
        compose.runOnIdle { scope.value = "fixture-a" }
        compose.onNodeWithTag("tab-library").performClick()
        compose.onNodeWithTag("filter-value").assertTextEquals("library:")
    }

    @Test fun dictionaryCloseAndRestorationKeepExactOriginWithoutBottomBar() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { LazyEngTheme { Harness() } }
        compose.onNodeWithTag("open-player").performClick()
        compose.onNodeWithTag("child-bottom-bar").assertDoesNotExist()
        compose.onNodeWithText("0:12 / 1:00").assertIsDisplayed()
        compose.onNodeWithText("词典").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("close-dictionary").performClick()
        compose.onNodeWithText("Synthetic navigation clip").assertIsDisplayed()
        compose.onNodeWithText("0:12 / 1:00").assertIsDisplayed()
        compose.onNodeWithTag("child-bottom-bar").assertDoesNotExist()
        capture("player-return")
        compose.onNodeWithText("返回", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("tab-home").assertIsSelected()
    }

    @Test fun parentRouteIsImmersiveAndBackReturnsToTab() {
        compose.setContent { LazyEngTheme { Harness() } }
        compose.onNodeWithTag("tab-me").performClick()
        compose.onNodeWithTag("open-parent-route").performClick()
        compose.onNodeWithTag("child-bottom-bar").assertDoesNotExist()
        compose.onNodeWithText("Back fixture").performClick()
        compose.onNodeWithTag("tab-me").assertIsSelected()
    }

    @Test fun systemBackFromNonHomeTabReturnsToHome() {
        compose.setContent { LazyEngTheme { Harness() } }
        compose.onNodeWithTag("tab-me").performClick()
        compose.onNodeWithTag("tab-me").assertIsSelected()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("tab-home").assertIsSelected()
    }

    @Test fun malformedLinkShowsActionableErrorAndBack() {
        compose.setContent { LazyEngTheme { Harness(link = "lazyeng://app/player/bad?positionMs=-1") } }
        compose.onNodeWithTag("navigation-error").assertIsDisplayed()
        capture("invalid-link")
        compose.onNodeWithTag("navigation-back").performClick()
        compose.onNodeWithTag("tab-home").assertIsSelected()
    }

    @Test fun syntacticallyValidButMissingVideoIsAlsoAnError() {
        compose.setContent { LazyEngTheme { Harness(link = "lazyeng://app/player/missing-video") } }
        compose.onNodeWithTag("navigation-error").assertIsDisplayed()
        compose.onNodeWithText("播放器尚未接入").assertDoesNotExist()
    }

    @Test fun realPlaceholderTabsAreReadableAtLargeFont() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                LazyEngTheme { ChildShell { t, b, g -> Destination(t, b, g) } }
            }
        }
        compose.onNodeWithTag("tab-me").performClick()
        compose.onNodeWithText("切换档案").assertIsDisplayed()
        compose.onNodeWithText("家长控制台").assertIsDisplayed()
        ChildTab.entries.forEach { compose.onNodeWithTag("tab-${it.route}").assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
        capture("me-font-1.3")
    }

    private fun capture(name: String) {
        val folder = File(compose.activity.filesDir, "eh-t1-evidence").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
