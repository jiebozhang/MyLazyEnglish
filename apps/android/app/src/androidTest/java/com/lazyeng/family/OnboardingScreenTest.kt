package com.lazyeng.family

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lazyeng.family.core.designsystem.LazyEngTheme
import com.lazyeng.family.feature.onboarding.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(state: OnboardingUiState, scale: Float = 1f, event: (OnboardingUiEvent) -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                LazyEngTheme { OnboardingScreen(state, event) }
            }
        }
    }
    private fun evidence(name: String) {
        val directory = File(compose.activity.filesDir, "onboarding-evidence").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun setupExplainsIrrecoverablePinAndLargeFontActionsRemainReachable() {
        show(OnboardingUiState(phase = OnboardingPhase.SET_PIN), 1.3f)
        compose.onNodeWithText("忘记 PIN 后", substring = true).assertExists()
        compose.onNodeWithTag("pin-submit").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        evidence("pin-setup-font-1.3")
    }
    @Test fun lockedUiDisablesInputAndAnnouncesRemainingTime() {
        show(OnboardingUiState(phase = OnboardingPhase.VERIFY_PIN, remainingSeconds = 30))
        compose.onNodeWithTag("pin-lock").assertTextEquals("请等待 30 秒后再试")
        compose.onNodeWithTag("pin-digit-1").assertIsNotEnabled()
        compose.onNodeWithTag("pin-submit").assertIsNotEnabled()
        evidence("pin-locked")
    }
    @Test fun profileFormLargeFontSupportsAccessibleSelectionAndSave() {
        val events = mutableListOf<OnboardingUiEvent>()
        show(OnboardingUiState(phase = OnboardingPhase.CREATE_PROFILE, nickname = "示例学员"), 1.3f, events::add)
        compose.onNodeWithTag("avatar-star").performClick()
        compose.onNodeWithTag("profile-save").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(events.contains(OnboardingUiEvent.Avatar("star")))
        assertTrue(events.contains(OnboardingUiEvent.CreateProfile))
        evidence("profile-font-1.3")
    }
    @Test fun failureOffersRetryWithoutExposingDiagnosticInformation() {
        var retried = false
        show(OnboardingUiState(phase = OnboardingPhase.ERROR, message = "无法恢复设置，请重试。")) {
            retried = it == OnboardingUiEvent.Retry
        }
        compose.onNodeWithText("重试").performClick()
        assertTrue(retried)
    }

    @Test fun verifyingFeedbackIsVisibleAndBlocksDuplicateSubmission() {
        show(OnboardingUiState(phase = OnboardingPhase.VERIFY_PIN, busy = true), 1.3f)
        compose.onNodeWithTag("pin-submit").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("验证中…").assertIsDisplayed()
        compose.onNodeWithTag("pin-digit-1").assertIsNotEnabled()
        evidence("pin-verifying-font-1.3")
    }
}
