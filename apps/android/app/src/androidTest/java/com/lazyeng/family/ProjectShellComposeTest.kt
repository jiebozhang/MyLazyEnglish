package com.lazyeng.family

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectShellComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun projectShellShowsApplicationNameAndVersion() {
        composeRule.onNodeWithText("LazyEng 家庭版").assertIsDisplayed()
        composeRule.onNodeWithText("Android 工程已就绪 · 0.1.0").assertIsDisplayed()
    }
}
