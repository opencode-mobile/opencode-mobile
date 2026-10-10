package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodePillShape
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

enum class OpenCodeChoiceBadgeVariant {
    /** Surface pill with rounded-rectangle stroke (gallery / dense controls). */
    Standard,

    /** Capsule glass / Material stand-in chrome aligned with Filter Chip. */
    Glass,
}

private val StandardShape = RoundedCornerShape(16.dp)
private val GlassShape = OpenCodePillShape
private val BadgePaddingHorizontal = 12.dp
private val BadgePaddingVertical = 8.dp

@Composable
fun OpenCodeChoiceBadge(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: OpenCodeChoiceBadgeVariant = OpenCodeChoiceBadgeVariant.Standard,
) {
    when (variant) {
        OpenCodeChoiceBadgeVariant.Standard ->
            StandardChoiceBadge(
                label = label,
                selected = selected,
                enabled = enabled,
                onClick = onClick,
                modifier = modifier,
            )
        OpenCodeChoiceBadgeVariant.Glass ->
            GlassChoiceBadge(
                label = label,
                selected = selected,
                enabled = enabled,
                onClick = onClick,
                modifier = modifier,
            )
    }
}

@Composable
private fun StandardChoiceBadge(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        when {
            !enabled -> openCodeColor("surface-raised-base")
            selected -> openCodeColor("surface-raised-base")
            else -> openCodeColor("surface-base")
        }
    val borderColor =
        when {
            !enabled -> openCodeColor("border-weaker-base")
            selected -> openCodeColor("border-weak-base")
            else -> openCodeColor("border-weaker-base")
        }
    val contentColor =
        when {
            !enabled -> openCodeColor("text-base")
            else -> openCodeColor("text-strong")
        }

    Surface(
        modifier =
            modifier
                .clip(StandardShape)
                .semantics { this.selected = selected }
                .clickable(enabled = enabled, onClick = onClick),
        shape = StandardShape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier =
                Modifier.padding(
                    horizontal = BadgePaddingHorizontal,
                    vertical = BadgePaddingVertical,
                ),
        )
    }
}

@Composable
private fun GlassChoiceBadge(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStrong = openCodeColor("text-strong")
    val labelColor =
        if (enabled) {
            textStrong
        } else {
            openCodeColor("text-base")
        }
    // Fill-only stand-in (same recipe as iOS pre–26 glass fallback).
    val fill = textStrong.copy(alpha = if (selected) 0.14f else 0.08f)

    Box(
        modifier =
            modifier
                .semantics { this.selected = selected }
                .clip(GlassShape)
                .background(fill)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .heightIn(min = 36.dp)
                .padding(horizontal = BadgePaddingHorizontal, vertical = BadgePaddingVertical),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = labelColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeChoiceBadgeDefaultPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeChoiceBadge(label = "Label", selected = false, onClick = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeChoiceBadgeSelectedPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeChoiceBadge(label = "Label", selected = true, onClick = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeChoiceBadgeGlassPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeChoiceBadge(
            label = "Grok 4.5",
            selected = true,
            variant = OpenCodeChoiceBadgeVariant.Glass,
            onClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeChoiceBadgeDisabledPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeChoiceBadge(label = "Label", selected = false, enabled = false, onClick = {})
    }
}
