package com.lazyeng.family.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = LazyEngTouchTarget.minimum),
        enabled = enabled,
        shape = LazyEngShapes.prominentCard,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = LazyEngSpacing.large,
            vertical = LazyEngSpacing.mediumSmall,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = LazyEngColors.PrimaryMint,
            contentColor = LazyEngColors.TextPrimary,
            disabledContainerColor = LazyEngColors.SurfaceSoft,
            disabledContentColor = LazyEngColors.TextSecondary,
        ),
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = LazyEngTouchTarget.minimum),
        enabled = enabled,
        shape = LazyEngShapes.prominentCard,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = LazyEngSpacing.large,
            vertical = LazyEngSpacing.mediumSmall,
        ),
        border = BorderStroke(LazyEngStroke.thin, LazyEngColors.BorderStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = LazyEngColors.PrimaryDeep),
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

enum class StatusTone { SUCCESS, LEARNING, WARNING, DANGER, NEUTRAL }

@Composable
fun StatusChip(label: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val (container, content) = when (tone) {
        StatusTone.SUCCESS -> LazyEngColors.MintSoft to LazyEngColors.PrimaryDeep
        StatusTone.LEARNING -> LazyEngColors.BlueSoft to LazyEngColors.BlueDeep
        StatusTone.WARNING -> LazyEngColors.Warning.copy(alpha = 0.18f) to LazyEngColors.TextPrimary
        StatusTone.DANGER -> LazyEngColors.Danger.copy(alpha = 0.12f) to LazyEngColors.Danger
        StatusTone.NEUTRAL -> LazyEngColors.SurfaceSoft to LazyEngColors.TextSecondary
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Text(label, modifier = Modifier.padding(horizontal = LazyEngSpacing.medium, vertical = LazyEngSpacing.small), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun VideoCard(
    title: String,
    detail: String,
    duration: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: String? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = LazyEngTouchTarget.minimum),
        shape = LazyEngShapes.largeCard,
        colors = CardDefaults.cardColors(containerColor = LazyEngColors.Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = LazyEngElevation.raised),
    ) {
        Row(
            modifier = Modifier.padding(LazyEngSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
        ) {
            Box(
                modifier = Modifier.size(LazyEngSpacing.xxLarge * 2).clip(LazyEngShapes.card)
                    .background(LazyEngColors.BlueSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(duration, style = MaterialTheme.typography.labelLarge, color = LazyEngColors.BlueDeep)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = LazyEngColors.TextPrimary)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = LazyEngColors.TextSecondary)
                if (status != null) StatusChip(status, StatusTone.LEARNING)
            }
        }
    }
}

@Composable
fun DataCard(
    label: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = LazyEngShapes.card,
        color = LazyEngColors.Surface,
        border = BorderStroke(LazyEngStroke.thin, LazyEngColors.Border),
        shadowElevation = LazyEngElevation.raised,
    ) {
        Column(
            modifier = Modifier.padding(LazyEngSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = LazyEngColors.TextSecondary)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = LazyEngColors.TextPrimary)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = LazyEngColors.TextSecondary)
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = LazyEngColors.TextPrimary)
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.defaultMinSize(minHeight = LazyEngTouchTarget.minimum)) {
                Text(actionLabel, color = LazyEngColors.PrimaryDeep)
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(LazyEngSpacing.xLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = LazyEngColors.TextPrimary)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = LazyEngColors.TextSecondary)
        if (actionLabel != null && onAction != null) PrimaryButton(actionLabel, onAction)
    }
}

@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(LazyEngSpacing.xLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = LazyEngColors.Danger)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = LazyEngColors.TextPrimary)
        if (retryLabel != null && onRetry != null) SecondaryButton(retryLabel, onRetry)
    }
}

@Composable
fun LoadingSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().clip(LazyEngShapes.card).background(LazyEngColors.SurfaceSoft)
            .padding(LazyEngSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small),
    ) {
        SkeletonBar(LazyEngSpacing.xxLarge, LazyEngSpacing.large * 2)
        SkeletonBar(LazyEngSpacing.large, LazyEngSpacing.xxLarge * 4)
        SkeletonBar(LazyEngSpacing.large, LazyEngSpacing.xxLarge * 3)
    }
}

@Composable
private fun SkeletonBar(height: Dp, width: Dp) {
    Spacer(
        modifier = Modifier.width(width).height(height).clip(LazyEngShapes.small)
            .background(LazyEngColors.Border),
    )
}

data class SubtitleWord(val text: String, val lookupTarget: Boolean = false)

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SubtitleLine(
    words: List<SubtitleWord>,
    active: Boolean,
    modifier: Modifier = Modifier,
    translation: String? = null,
    onWordClick: (SubtitleWord) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().clip(LazyEngShapes.card)
            .background(if (active) LazyEngColors.SubtitleHighlight else LazyEngColors.Surface)
            .padding(LazyEngSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall)) {
            words.forEachIndexed { index, word ->
                Box(
                    modifier = Modifier.defaultMinSize(
                        minWidth = LazyEngTouchTarget.minimum,
                        minHeight = LazyEngTouchTarget.minimum,
                    ).clip(LazyEngShapes.small)
                        .clickable(enabled = active, role = Role.Button, onClick = { onWordClick(word) })
                        .testTag("subtitle-word-$index")
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (word.lookupTarget) "查词 ${word.text}" else word.text
                            if (!active) disabled()
                        }
                        .padding(horizontal = LazyEngSpacing.xSmall, vertical = LazyEngSpacing.small),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        word.text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = LazyEngTypeScale.subtitle),
                        color = if (active) LazyEngColors.TextPrimary else LazyEngColors.TextSecondary,
                        fontWeight = if (word.lookupTarget) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        if (translation != null) {
            Text(translation, style = MaterialTheme.typography.bodyMedium.copy(fontSize = LazyEngTypeScale.subtitleTranslation), color = LazyEngColors.TextSecondary)
        }
    }
}

@Composable
fun BottomSheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = LazyEngSpacing.mediumSmall)
            .semantics { contentDescription = "底部弹层把手" },
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier.size(width = LazyEngSpacing.xxLarge + LazyEngSpacing.small, height = LazyEngSpacing.xSmall + LazyEngSpacing.xSmall / 2)
                .clip(CircleShape).background(LazyEngColors.BorderStrong),
        )
    }
}

@Composable
fun PinDots(enteredCount: Int, count: Int = 4, modifier: Modifier = Modifier) {
    require(count > 0 && enteredCount in 0..count)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "PIN 输入，已输入 $enteredCount 个，共 $count 个"
        },
        horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
    ) {
        repeat(count) { index ->
            Box(
                modifier = Modifier.size(LazyEngSpacing.mediumSmall)
                    .clip(CircleShape)
                    .background(if (index < enteredCount) LazyEngColors.PrimaryMint else LazyEngColors.Surface)
                    .then(if (index < enteredCount) Modifier else Modifier.border(LazyEngSpacing.xSmall / 2, LazyEngColors.BorderStrong, CircleShape)),
            )
        }
    }
}

enum class RatingChoice { FORGOT, UNCERTAIN, REMEMBERED }

@Composable
fun RatingButton(
    label: String,
    choice: RatingChoice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val toneColor = when (choice) {
        RatingChoice.FORGOT -> LazyEngColors.Danger
        RatingChoice.UNCERTAIN -> LazyEngColors.Warning
        RatingChoice.REMEMBERED -> LazyEngColors.PrimaryDeep
    }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = LazyEngTouchTarget.minimum).semantics {
            stateDescription = if (selected) "已选择" else "未选择"
        },
        enabled = enabled,
        shape = LazyEngShapes.card,
        border = BorderStroke(LazyEngStroke.thin, if (selected) toneColor else LazyEngColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) toneColor.copy(alpha = 0.12f) else LazyEngColors.Surface,
            contentColor = toneColor,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
