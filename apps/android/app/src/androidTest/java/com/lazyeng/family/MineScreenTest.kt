package com.lazyeng.family

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.feature.profiles.MineProfileInfo
import com.lazyeng.family.feature.profiles.MineScreen
import com.lazyeng.family.feature.profiles.MineUiEvent
import com.lazyeng.family.feature.profiles.MineUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MineScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun showsProfileAndRoutesBothActionsWithoutBypassingParentGate() {
        var event: MineUiEvent? = null
        compose.setContent {
            LazyEngTheme {
                MineScreen(
                    MineUiState.Content(MineProfileInfo("Fixture Learner", "star", "child", "Power Up 2", 3, "")),
                    { event = it },
                    rememberLazyListState(),
                )
            }
        }
        compose.onNodeWithText("Fixture Learner").assertIsDisplayed()
        compose.onNodeWithText("儿童模式 · Power Up 2").assertIsDisplayed()
        compose.onNodeWithText("连续学习 3 天").assertIsDisplayed()
        compose.onNodeWithTag("mine-switch-profile").performClick()
        compose.runOnIdle { assertEquals(MineUiEvent.SwitchProfile, event) }
        compose.onNodeWithTag("mine-parent-settings").performClick()
        compose.runOnIdle { assertEquals(MineUiEvent.ParentSettings, event) }
    }
}
