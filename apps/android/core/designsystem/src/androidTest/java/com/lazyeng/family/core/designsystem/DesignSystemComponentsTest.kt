package com.lazyeng.family.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.platform.testTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DesignSystemComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun primaryActionAndSubtitleWordMeetTouchTargetAndAccessibilityContract() {
        var clicked = false
        compose.setContent {
            LazyEngTheme {
                Column {
                    PrimaryButton("继续学习", onClick = { clicked = true }, modifier = androidx.compose.ui.Modifier.testTag("primary-action"))
                    SubtitleLine(
                        words = listOf(SubtitleWord("kite", lookupTarget = true)),
                        active = true,
                        onWordClick = { clicked = true },
                    )
                }
            }
        }

        assertMinTouchSize("primary-action")
        assertMinTouchSize("subtitle-word-0")
        compose.onNodeWithContentDescription("查词 kite").assertExists()
        compose.onNodeWithTag("subtitle-word-0").performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }

    @Test
    fun inactiveSubtitleWordsAreDisabledSemantically() {
        compose.setContent {
            LazyEngTheme {
                SubtitleLine(
                    words = listOf(SubtitleWord("park")),
                    active = false,
                    onWordClick = {},
                )
            }
        }
        compose.onNodeWithTag("subtitle-word-0").assertIsNotEnabled()
    }

    @Test
    fun selectedRatingAnnouncesItsState() {
        compose.setContent {
            LazyEngTheme {
                RatingButton("有点模糊", RatingChoice.UNCERTAIN, onClick = {}, selected = true)
            }
        }
        compose.onNode(hasStateDescription("已选择")).assertExists()
    }

    @Test
    fun primaryActionRemainsVisibleAtOnePointThreeFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides remember(density) {
                Density(density.density, fontScale = 1.3f)
            }) {
                LazyEngTheme {
                    PrimaryButton("加入生词本", onClick = {}, modifier = androidx.compose.ui.Modifier.testTag("scaled-action"))
                }
            }
        }
        compose.onNodeWithText("加入生词本").assertIsDisplayed()
        val heightDp = with(compose.density) {
            compose.onNodeWithTag("scaled-action").fetchSemanticsNode().boundsInRoot.height.toDp()
        }
        assertTrue(heightDp >= LazyEngTouchTarget.minimum)
    }

    @Test
    fun visualTokensMatchRuntimePrototypeValues() {
        assertEquals(Color(0xFFFAF8F3), LazyEngColors.Background)
        assertEquals(Color(0xFFCFF7E2), LazyEngColors.SubtitleHighlight)
        assertEquals(19.sp, LazyEngTypeScale.subtitle)
        assertEquals(16.dp, LazyEngCornerSize.card)
        assertEquals(20.dp, LazyEngCornerSize.prominentCard)
        assertEquals(24.dp, LazyEngCornerSize.largeCard)
        assertEquals(28.dp, LazyEngCornerSize.bottomSheet)
        assertEquals(200, LazyEngMotion.normalMillis)
        assertEquals(300, LazyEngMotion.slowMillis)
    }

    private fun assertMinTouchSize(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val widthDp = with(compose.density) { node.boundsInRoot.width.toDp() }
        val heightDp = with(compose.density) { node.boundsInRoot.height.toDp() }
        assertTrue("$tag width was $widthDp", widthDp >= LazyEngTouchTarget.minimum)
        assertTrue("$tag height was $heightDp", heightDp >= LazyEngTouchTarget.minimum)
    }
}
