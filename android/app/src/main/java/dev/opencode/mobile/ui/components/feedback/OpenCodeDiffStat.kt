package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

/** Type role for an addition and deletion count. */
enum class OpenCodeDiffStatStyle {
    /** Caption-scale counts, as in a tool-call meta row. */
    Caption,

    /** Button-scale counts, as in a floating pill. */
    Button,

    /** Monospace counts, as on the branch pill. */
    Mono,

    /** Label-scale counts, as in a changed-file row. */
    Label,
}

/** Space between the addition count and the deletion count. */
enum class OpenCodeDiffStatSeparator {
    /** One space, as in a tool-call meta row. */
    Space,

    /** Two spaces, as in a summary line. */
    Wide,

    /** Fixed gap, as between counts on a file row. */
    Split,
}

/**
 * Colored `+N −M` run for embedding in an existing annotated string.
 * A null side is omitted. Zero is shown when passed.
 */
@Composable
fun openCodeDiffStatAnnotatedString(
    additions: Int? = null,
    deletions: Int? = null,
    enabled: Boolean = true,
    separator: OpenCodeDiffStatSeparator = OpenCodeDiffStatSeparator.Space,
): AnnotatedString {
    if (additions != null) require(additions >= 0) { "Diff Stat additions must be nonnegative" }
    if (deletions != null) require(deletions >= 0) { "Diff Stat deletions must be nonnegative" }
    val additionColor = diffStatAdditionColor(enabled)
    val deletionColor = diffStatDeletionColor(enabled)
    val gap =
        when (separator) {
            OpenCodeDiffStatSeparator.Wide -> "  "
            OpenCodeDiffStatSeparator.Space, OpenCodeDiffStatSeparator.Split -> " "
        }
    return buildAnnotatedString {
        if (additions != null) {
            withStyle(SpanStyle(color = additionColor)) { append("+$additions") }
        }
        if (additions != null && deletions != null) append(gap)
        if (deletions != null) {
            withStyle(SpanStyle(color = deletionColor)) { append("−$deletions") }
        }
    }
}

/** `+N` and `−M` counts. A null side is omitted. Zero is shown when passed. */
@Composable
fun OpenCodeDiffStat(
    additions: Int? = null,
    deletions: Int? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: OpenCodeDiffStatStyle = OpenCodeDiffStatStyle.Caption,
    separator: OpenCodeDiffStatSeparator = OpenCodeDiffStatSeparator.Space,
) {
    if (additions == null && deletions == null) return
    if (additions != null) require(additions >= 0) { "Diff Stat additions must be nonnegative" }
    if (deletions != null) require(deletions >= 0) { "Diff Stat deletions must be nonnegative" }
    val textStyle = style.textStyle()
    if (separator == OpenCodeDiffStatSeparator.Split) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (additions != null) {
                Text(
                    text = "+$additions",
                    style = textStyle,
                    color = diffStatAdditionColor(enabled),
                    maxLines = 1,
                )
            }
            if (deletions != null) {
                Text(
                    text = "−$deletions",
                    style = textStyle,
                    color = diffStatDeletionColor(enabled),
                    maxLines = 1,
                )
            }
        }
    } else {
        Text(
            text =
                openCodeDiffStatAnnotatedString(
                    additions = additions,
                    deletions = deletions,
                    enabled = enabled,
                    separator = separator,
                ),
            modifier = modifier,
            style = textStyle,
            maxLines = 1,
        )
    }
}

@Composable
private fun diffStatAdditionColor(enabled: Boolean): Color =
    if (enabled) openCodeColor("diff-add") else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun diffStatDeletionColor(enabled: Boolean): Color =
    if (enabled) openCodeColor("diff-delete") else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun OpenCodeDiffStatStyle.textStyle(): TextStyle =
    when (this) {
        OpenCodeDiffStatStyle.Caption -> MaterialTheme.typography.bodySmall
        OpenCodeDiffStatStyle.Button -> MaterialTheme.typography.labelLarge
        OpenCodeDiffStatStyle.Label -> MaterialTheme.typography.labelMedium
        OpenCodeDiffStatStyle.Mono ->
            MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
    }

@Preview(showBackground = true)
@Composable
private fun OpenCodeDiffStatPreview() {
    OpenCodeTheme {
        OpenCodeDiffStat(additions = 12, deletions = 3)
    }
}
