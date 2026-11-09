package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val SplitButtonOuterRadius = OpenCodeRadius.small
private val SplitButtonInnerRadius = 3.dp
private val SplitButtonGap = 2.dp
private val SplitButtonMenuWidth = 30.dp
private val SplitButtonMenuIconSize = 16.dp

private val SplitButtonPrimaryShape =
    RoundedCornerShape(
        topStart = SplitButtonOuterRadius,
        bottomStart = SplitButtonOuterRadius,
        topEnd = SplitButtonInnerRadius,
        bottomEnd = SplitButtonInnerRadius,
    )

private val SplitButtonMenuShape =
    RoundedCornerShape(
        topStart = SplitButtonInnerRadius,
        bottomStart = SplitButtonInnerRadius,
        topEnd = SplitButtonOuterRadius,
        bottomEnd = SplitButtonOuterRadius,
    )

/**
 * A compact text button with a menu half: the [title] segment runs [onClick] in one tap, the
 * narrow ▾ segment runs [onMenu]. Long-press (or a secondary click) on the title segment runs
 * [onMenu] too. Both halves match [OpenCodeButtonVariant.CompactText] in height, fill and type,
 * joined by a 2 dp gap. Screen readers get two buttons: [title] (with [menuContentDescription]
 * as its long-press action) and [menuContentDescription].
 *
 * A focus requester in [modifier] focuses the title segment.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OpenCodeSplitButton(
    title: String,
    menuContentDescription: String,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contrast: OpenCodeButtonContrast = OpenCodeButtonContrast.High,
) {
    val colors = openCodeButtonColors(contrast, enabled)
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(SplitButtonGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .defaultMinSize(minHeight = OpenCodeButtonCompactMinHeight)
                    .clip(SplitButtonPrimaryShape)
                    .openCodeFocusRing(SplitButtonPrimaryShape, enabled)
                    .openCodeSecondaryClick(enabled = enabled) { onMenu() }
                    .combinedClickable(
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                        onLongClickLabel = menuContentDescription,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMenu()
                        },
                    ),
            shape = SplitButtonPrimaryShape,
            color = colors.container,
            contentColor = colors.content,
            border = BorderStroke(OpenCodeButtonBorderWidth, colors.border),
        ) {
            Box(
                modifier =
                    Modifier.padding(
                        horizontal = OpenCodeButtonTextHorizontalPadding,
                        vertical = OpenCodeButtonTextVerticalPadding,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        Surface(
            modifier =
                Modifier
                    .width(SplitButtonMenuWidth)
                    .fillMaxHeight()
                    .defaultMinSize(minHeight = OpenCodeButtonCompactMinHeight)
                    .clip(SplitButtonMenuShape)
                    .openCodeFocusRing(SplitButtonMenuShape, enabled)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onMenu)
                    .semantics { contentDescription = menuContentDescription },
            shape = SplitButtonMenuShape,
            color = colors.container,
            contentColor = colors.content,
            border = BorderStroke(OpenCodeButtonBorderWidth, colors.border),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = OpenCodeIcons.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(SplitButtonMenuIconSize),
                    tint = colors.content,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSplitButtonLowPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeSplitButton(
            title = "Deny",
            menuContentDescription = "More deny options",
            contrast = OpenCodeButtonContrast.Low,
            onClick = {},
            onMenu = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSplitButtonHighDarkPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeSplitButton(
            title = "Deny",
            menuContentDescription = "More deny options",
            onClick = {},
            onMenu = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSplitButtonDisabledPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeSplitButton(
            title = "Deny",
            menuContentDescription = "More deny options",
            enabled = false,
            contrast = OpenCodeButtonContrast.Low,
            onClick = {},
            onMenu = {},
        )
    }
}
