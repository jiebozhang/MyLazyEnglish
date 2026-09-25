package com.lazyeng.family

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyeng.family.core.security.KeystorePinRecordStore
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in destructive-to-fixture flow: requires an unused app data directory, never clears user data. */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun awaitText(text: String) = compose.waitUntil(20_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun enter(pin: CharArray) {
        pin.forEach { compose.onNodeWithTag("pin-digit-$it").performScrollTo().performClick() }
        compose.onNodeWithTag("pin-submit").performScrollTo().performClick()
    }
    @Test fun setupMismatchDraftRecreationThrottleResetAndHome() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("onboardingAcceptance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = KeystorePinRecordStore.create(context)
        assertNull("Requires unused test installation; existing records are never deleted", runBlocking { store.read() })
        val random = SecureRandom()
        val pin = CharArray(4) { ('0'.code + random.nextInt(10)).toChar() }
        val wrong = pin.map { ('0'.code + (it.digitToInt() + 1) % 10).toChar() }.toCharArray()
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitText("创建家庭与家长 PIN")
            enter(pin); awaitText("再输入一次 PIN")
            enter(wrong); awaitText("两次 PIN 不一致，请重新设置。")
            enter(pin); awaitText("再输入一次 PIN")
            enter(pin); awaitText("创建孩子档案")
            compose.onNodeWithTag("profile-nickname").performTextInput("Fixture child")
            compose.onNodeWithTag("avatar-star").performClick()
            scenario.recreate()
            awaitText("创建孩子档案")
            compose.onNodeWithTag("profile-nickname").assertTextContains("Fixture child")
            compose.onNodeWithTag("avatar-star").assertIsSelected()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("profile-nickname").assertTextContains("Fixture child")
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            awaitText("验证家长 PIN")
            repeat(5) {
                enter(wrong)
                awaitText("PIN 未通过验证，请稍后重试。")
            }
            compose.onNodeWithTag("pin-lock").assertExists()
            compose.onNodeWithTag("pin-digit-1").assertIsNotEnabled()
            assertEquals(5, runBlocking { store.read()!!.failures })
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            awaitText("验证家长 PIN")
            compose.onNodeWithTag("pin-lock").assertExists()
            compose.waitUntil(40_000) { compose.onAllNodesWithTag("pin-lock").fetchSemanticsNodes().isEmpty() }
            enter(pin); awaitText("创建孩子档案")
            assertEquals(0, runBlocking { store.read()!!.failures })
            compose.onNodeWithTag("profile-nickname").assertTextContains("Fixture child")
            compose.onNodeWithTag("profile-save").performScrollTo().performClick()
            awaitText("首页")
            scenario.recreate()
            awaitText("首页")
        } finally {
            pin.fill('\u0000'); wrong.fill('\u0000'); scenario.close()
        }
    }
}
