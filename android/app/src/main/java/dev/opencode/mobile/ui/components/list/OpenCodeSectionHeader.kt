package dev.opencode.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

private val SectionHeaderInsetStandard = 16.dp
private val SectionHeaderTopPadding = 16.dp
private val SectionHeaderBottomPadding = 4.dp
private val SectionHeaderTrailingSpacing = 8.dp
private val SectionHeaderLabelTracking = 0.5.sp

/** How the title text is treated. */
enum class OpenCodeSectionHeaderVariant {
    /** Short group label rendered uppercase with semibold label weight (`SERVER`, `RECENT`). */
    Label,

    /** Free text kept as written and truncated to one line (a directory path, a summary line). */
    Plain,
}

/** Optional content on the trailing edge of the header. */
sealed interface OpenCodeSectionHeaderTrailing {
    /** Quiet item count for the group below. */
    data class Count(
        val value: Int,
    ) : OpenCodeSectionHeaderTrailing

    /** Compact text action scoped to the group below. */
    data class Action(
        val label: String,
        val onClick: () -> Unit,
    ) : OpenCodeSectionHeaderTrailing
}

/** Horizontal inset relative to the container edge. */
enum class OpenCodeSectionHeaderInset {
    /** Aligns with `OpenCodeListItem` text (16dp). */
    Standard,

    /** No horizontal inset; the container already positions the header. */
    Flush,
}

/**
 * Caption-level title above a group of rows or a region, with an optional trailing count or action.
 */
@Composable
fun OpenCodeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    variant: OpenCodeSectionHeaderVariant = OpenCodeSectionHeaderVariant.Label,
    trailing: OpenCodeSectionHeaderTrailing? = null,
    inset: OpenCodeSectionHeaderInset = OpenCodeSectionHeaderInset.Standard,
) {
    val horizontalInset =
        when (inset) {
            OpenCodeSectionHeaderInset.Standard -> SectionHeaderInsetStandard
            OpenCodeSectionHeaderInset.Flush -> 0.dp
        }
    val semanticsModifier =
        when (trailing) {
            is OpenCodeSectionHeaderTrailing.Count ->
                Modifier.clearAndSetSemantics {
                    contentDescription =
                        "$title, ${trailing.value} ${if (trailing.value == 1) "item" else "items"}"
                }
            else -> Modifier
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(semanticsModifier)
                .padding(
                    start = horizontalInset,
                    end = horizontalInset,
                    top = SectionHeaderTopPadding,
                    bottom = SectionHeaderBottomPadding,
                ),
        horizontalArrangement = Arrangement.spacedBy(SectionHeaderTrailingSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (variant) {
            OpenCodeSectionHeaderVariant.Label ->
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = SectionHeaderLabelTracking,
                    color = openCodeColor("text-base"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            OpenCodeSectionHeaderVariant.Plain ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-base"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
        }
        when (trailing) {
            is OpenCodeSectionHeaderTrailing.Count ->
                Text(
                    text = trailing.value.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-weak"),
                )
            is OpenCodeSectionHeaderTrailing.Action ->
                Text(
                    text = trailing.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = openCodeColor("interactive"),
                    modifier = Modifier.clickable(role = Role.Button, onClick = trailing.onClick),
                )
            null -> Unit
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSectionHeaderPreview() {
    OpenCodeTheme(darkTheme = false) {
        Column {
            OpenCodeSectionHeader(title = "Server")
            OpenCodeSectionHeader(
                title = "Sources/App/Features",
                variant = OpenCodeSectionHeaderVariant.Plain,
                trailing = OpenCodeSectionHeaderTrailing.Count(3),
            )
            OpenCodeSectionHeader(
                title = "3 waiting",
                variant = OpenCodeSectionHeaderVariant.Plain,
                trailing = OpenCodeSectionHeaderTrailing.Action("Clear all") {},
            )
            OpenCodeSectionHeader(title = "Context breakdown", inset = OpenCodeSectionHeaderInset.Flush)
        }
    }
}
