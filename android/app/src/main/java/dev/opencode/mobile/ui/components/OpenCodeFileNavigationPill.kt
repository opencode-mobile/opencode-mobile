package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val FileNavigationPillShape = RoundedCornerShape(percent = 50)

fun fileNavigationPillSummary(
    additions: Int,
    deletions: Int,
): String = "+$additions −$deletions"

fun fileNavigationPillAccessibilityLabel(
    additions: Int,
    deletions: Int,
): String =
    "$additions ${if (additions == 1) "addition" else "additions"}, " +
        "$deletions ${if (deletions == 1) "deletion" else "deletions"}"

@Composable
fun OpenCodeFileNavigationPill(
    additions: Int,
    deletions: Int,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    require(additions >= 0) { "File Navigation Pill requires nonnegative additions" }
    require(deletions >= 0) { "File Navigation Pill requires nonnegative deletions" }

    Surface(
        modifier = modifier,
        shape = FileNavigationPillShape,
        color = openCodeColor("surface-raised-base"),
        border = BorderStroke(1.dp, openCodeColor("border-weaker-base")),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileNavigationButton(
                previous = true,
                enabled = enabled && previousEnabled,
                onClick = onPrevious,
            )
            Text(
                text =
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = openCodeColor("diff-add"))) { append("+$additions") }
                        append(" ")
                        withStyle(SpanStyle(color = openCodeColor("diff-delete"))) { append("−$deletions") }
                    },
                modifier =
                    Modifier
                        .padding(horizontal = 12.dp)
                        .semantics {
                            contentDescription = fileNavigationPillAccessibilityLabel(additions, deletions)
                        },
                style = MaterialTheme.typography.labelLarge,
            )
            FileNavigationButton(
                previous = false,
                enabled = enabled && nextEnabled,
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun FileNavigationButton(
    previous: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        enabled = enabled,
        interactionSource = interactionSource,
        colors =
            IconButtonDefaults.iconButtonColors(
                containerColor =
                    if (pressed && enabled) {
                        openCodeColor("surface-raised-base-hover")
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    },
                contentColor = openCodeColor("icon-strong-base"),
                disabledContentColor = openCodeColor("icon-weak-base"),
            ),
    ) {
        Icon(
            imageVector = OpenCodeIcons.ChevronRight,
            contentDescription = if (previous) "Previous file" else "Next file",
            modifier = Modifier.size(20.dp).graphicsLayer(scaleX = if (previous) -1f else 1f),
        )
    }
}

@Composable
private fun FileNavigationPillPreview(
    darkTheme: Boolean,
    additions: Int,
    deletions: Int,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    enabled: Boolean = true,
) {
    OpenCodeTheme(darkTheme = darkTheme) {
        OpenCodeFileNavigationPill(
            additions = additions,
            deletions = deletions,
            previousEnabled = previousEnabled,
            nextEnabled = nextEnabled,
            enabled = enabled,
            onPrevious = {},
            onNext = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FileNavigationPillFirstLightPreview() = FileNavigationPillPreview(false, 12, 3, previousEnabled = false, nextEnabled = true)

@Preview(showBackground = true)
@Composable
private fun FileNavigationPillMiddleDarkPreview() = FileNavigationPillPreview(true, 12, 3, previousEnabled = true, nextEnabled = true)

@Preview(showBackground = true)
@Composable
private fun FileNavigationPillLastLightPreview() = FileNavigationPillPreview(false, 1, 2, previousEnabled = true, nextEnabled = false)

@Preview(showBackground = true)
@Composable
private fun FileNavigationPillZeroDarkPreview() = FileNavigationPillPreview(true, 0, 0, previousEnabled = true, nextEnabled = true)

@Preview(showBackground = true)
@Composable
private fun FileNavigationPillRefreshLightPreview() =
    FileNavigationPillPreview(false, 12, 0, previousEnabled = true, nextEnabled = true, enabled = false)
