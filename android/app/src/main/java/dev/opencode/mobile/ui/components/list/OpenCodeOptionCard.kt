package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

private val OptionCardPadding = 12.dp
private val OptionCardCornerRadius = 14.dp
private val OptionCardMinHeight = 72.dp
private val OptionCardGap = 12.dp
private val OptionCardRadioSize = 20.dp
private val OptionCardRadioRing = 2.dp
private val OptionCardRadioDot = 10.dp
private val OptionCardPreviewWidth = 92.dp
private val OptionCardPreviewHeight = 56.dp
private val OptionCardPreviewCornerRadius = 10.dp
private val OptionCardPreviewTopPadding = 4.dp

@Suppress("ktlint:standard:property-naming")
private const val OptionCardPressedScale = 0.98f

@Suppress("ktlint:standard:property-naming")
private const val OptionCardDisabledAlpha = 0.5f

@Suppress("ktlint:standard:property-naming")
private const val OptionCardTitleMaxLines = 2

/** Three lines, so a description beside the 92dp preview does not truncate after two. */
@Suppress("ktlint:standard:property-naming")
private const val OptionCardSubtitleMaxLines = 3

/** Visual preview at the trailing end of an [OpenCodeOptionCard]. Decorative. */
sealed class OpenCodeOptionCardPreview {
    data object None : OpenCodeOptionCardPreview()

    /** 92x56 skeleton illustration of the option. */
    data class Illustration(
        val topic: OpenCodeSkeletonIllustrationTopic,
    ) : OpenCodeOptionCardPreview()
}

/** Spoken label of an [OpenCodeOptionCard]: title, then subtitle, then tag. */
internal fun openCodeOptionCardAccessibilityLabel(
    title: String,
    subtitle: String?,
    tag: String?,
): String = listOf(title, subtitle, tag).filterNot { it.isNullOrBlank() }.joinToString(", ")

/**
 * One choice in a single-choice list whose options each carry a short description and a visual
 * preview: a bordered card with a radio, the title (and an optional small [tag]), the
 * [subtitle], and a trailing [preview]. Stack cards 8dp apart; inside a bottom sheet, inset the
 * stack with the sheet's content inset.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OpenCodeOptionCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tag: String? = null,
    tagTone: OpenCodeTagTone = OpenCodeTagTone.Neutral,
    preview: OpenCodeOptionCardPreview = OpenCodeOptionCardPreview.None,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    require(title.isNotBlank()) { "Option Card requires a non-blank title" }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (enabled && isPressed) OptionCardPressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium),
        label = "optionCardPressScale",
    )
    val shape = RoundedCornerShape(OptionCardCornerRadius)
    val label = openCodeOptionCardAccessibilityLabel(title, subtitle, tag)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .scale(pressScale)
                .alpha(if (enabled) 1f else OptionCardDisabledAlpha)
                .clip(shape)
                .background(openCodeColor(if (selected) "surface-interactive-weak" else "surface-raised-base"), shape)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = openCodeColor(if (selected) "interactive" else "border-weak-base"),
                    shape = shape,
                ).clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics {
                    contentDescription = label
                    this.selected = selected
                }.defaultMinSize(minHeight = OptionCardMinHeight)
                .padding(OptionCardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OptionCardGap),
    ) {
        OptionCardRadio(selected = selected)
        Column(
            modifier = Modifier.weight(1f).clearAndSetSemantics {},
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // The title never wraps to make room for the tag: the tag sits on the title line
            // when both fit, otherwise it moves to its own line under the title.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = openCodeColor("text-strong"),
                    maxLines = OptionCardTitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!tag.isNullOrBlank()) {
                    OpenCodeTag(
                        title = tag,
                        modifier = Modifier.align(Alignment.CenterVertically),
                        size = OpenCodeTagSize.Small,
                        tone = tagTone,
                    )
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-weak"),
                    maxLines = OptionCardSubtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        OptionCardPreviewContent(preview)
    }
}

@Composable
private fun OptionCardRadio(selected: Boolean) {
    Box(
        modifier =
            Modifier
                .size(OptionCardRadioSize)
                .clearAndSetSemantics {}
                .border(
                    width = OptionCardRadioRing,
                    color = openCodeColor(if (selected) "interactive" else "icon-weak-base"),
                    shape = CircleShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier =
                    Modifier
                        .size(OptionCardRadioDot)
                        .background(openCodeColor("interactive"), CircleShape),
            )
        }
    }
}

@Composable
private fun OptionCardPreviewContent(preview: OpenCodeOptionCardPreview) {
    when (preview) {
        OpenCodeOptionCardPreview.None -> Unit
        is OpenCodeOptionCardPreview.Illustration -> {
            val shape = RoundedCornerShape(OptionCardPreviewCornerRadius)
            OpenCodeSkeletonIllustration(
                topic = preview.topic,
                modifier =
                    Modifier
                        .size(OptionCardPreviewWidth, OptionCardPreviewHeight)
                        .clip(shape)
                        .background(openCodeColor("surface-raised-base"))
                        .border(1.dp, openCodeColor("border-weak-base"), shape)
                        .padding(top = OptionCardPreviewTopPadding),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeOptionCardStatesPreview() {
    OpenCodeTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeOptionCard(
                title = "Activity Card",
                subtitle = "Running steps on one live card that folds into a summary",
                tag = "Recommended",
                tagTone = OpenCodeTagTone.Accent,
                preview = OpenCodeOptionCardPreview.Illustration(OpenCodeSkeletonIllustrationTopic.TimelineActivityCard),
                selected = true,
                onClick = {},
            )
            OpenCodeOptionCard(
                title = "Standard",
                subtitle = "Tool cards and groups between the agent's messages",
                tag = "Current",
                preview = OpenCodeOptionCardPreview.Illustration(OpenCodeSkeletonIllustrationTopic.TimelineStandard),
                onClick = {},
            )
            OpenCodeOptionCard(title = "Disabled", subtitle = "Not available right now", enabled = false, onClick = {})
        }
    }
}
