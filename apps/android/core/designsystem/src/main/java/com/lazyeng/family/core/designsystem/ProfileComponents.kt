package com.lazyeng.family.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** Generic visual avatar; unknown IDs preserve their accessible label and use a neutral initial. */
@Composable
fun ProfileAvatar(avatar: String?, nickname: String, modifier: Modifier = Modifier) {
    Box(modifier.size(LazyEngSpacing.xxLarge * 2).background(LazyEngColors.BlueSoft, CircleShape)
        .semantics { contentDescription = "头像 ${avatar.orEmpty()}" }, contentAlignment = Alignment.Center) {
        Text(when (avatar) { "sun" -> "☀"; "moon" -> "☾"; "star" -> "★"; else -> nickname.take(1) },
            style = MaterialTheme.typography.headlineSmall, color = LazyEngColors.BlueDeep)
    }
}

@Composable
fun ProfileChoiceCard(
    nickname: String, detail: String, avatar: String?, selected: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier, actionDescription: String = "切换到此档案",
) {
    Card(onClick, modifier.fillMaxWidth().semantics { stateDescription = if (selected) "当前档案" else actionDescription },
        shape = LazyEngShapes.largeCard, colors = CardDefaults.cardColors(containerColor = LazyEngColors.Surface)) {
        Row(Modifier.padding(LazyEngSpacing.large), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.large)) {
            ProfileAvatar(avatar, nickname)
            Column(Modifier.weight(1f)) {
                Text(nickname, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Shared keypad for onboarding and parent gates; no PIN value or business verification lives here. */
@Composable
fun PinKeypad(
    enteredCount: Int, enabled: Boolean, submitLabel: String,
    onDigit: (Int) -> Unit, onErase: () -> Unit, onSubmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
        for (row in listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))) {
            Row(horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium)) {
                row.forEach { number -> SecondaryButton(number.toString(), { onDigit(number) },
                    Modifier.weight(1f).testTag("pin-digit-$number"), enabled) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            SecondaryButton("0", { onDigit(0) }, Modifier.weight(1f).testTag("pin-digit-0"), enabled)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(onErase, Modifier.size(LazyEngTouchTarget.minimum).testTag("pin-backspace"), enabled) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "删除上一位")
                }
            }
        }
        PrimaryButton(submitLabel, onSubmit, Modifier.fillMaxWidth().testTag("pin-submit"), enabled && enteredCount == 4)
    }
}
