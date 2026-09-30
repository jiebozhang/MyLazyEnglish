package com.lazyeng.family.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleLanguageProjectionTest {
    @Test fun separatesTwoLineBilingualCue() {
        assertEquals("His manners were impeccable." to "他的举止无可挑剔。",
            splitSubtitleLanguages("His manners were impeccable.\n他的举止无可挑剔。"))
    }

    @Test fun separatesInlineBilingualCueWithoutMovingEnglishPunctuation() {
        assertEquals("And his grades were perfection." to "他的成绩也完美无瑕。",
            splitSubtitleLanguages("And his grades were perfection.他的成绩也完美无瑕。"))
    }

    @Test fun leavesEnglishOnlyCueWithoutFabricatingTranslation() {
        assertEquals("Give it up!" to null, splitSubtitleLanguages("Give it up!"))
    }
}
