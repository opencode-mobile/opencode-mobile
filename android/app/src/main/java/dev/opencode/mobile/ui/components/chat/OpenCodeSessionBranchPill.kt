package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val SessionBranchPillShape = RoundedCornerShape(percent = 50)

fun sessionBranchPillAdditionLabel(additions: Int): String? = additions.takeIf { it > 0 }?.let { "+$it" }

fun sessionBranchPillDeletionLabel(deletions: Int): String? = deletions.takeIf { it > 0 }?.let { "−$it" }

fun sessionBranchPillAccessibilityLabel(
    branch: String,
    additions: Int,
    deletions: Int,
): String {
    val parts =
        buildList {
            add("Branch $branch")
            additions.takeIf { it > 0 }?.let { add("$it ${if (it == 1) "addition" else "additions"}") }
            deletions.takeIf { it > 0 }?.let { add("$it ${if (it == 1) "deletion" else "deletions"}") }
        }
    return parts.joinToString(", ")
}

enum class SessionBranchPillPreviewCase(
    val branch: String,
    val additions: Int,
    val deletions: Int,
    val enabled: Boolean,
    val darkTheme: Boolean,
) {
    MixedLight("feature/session-branch", 12, 3, true, false),
    MixedDark("feature/session-branch", 12, 3, true, true),
    AdditionsOnlyLight("feature/session-branch", 12, 0, true, false),
    DeletionsOnlyDark("feature/session-branch", 0, 3, true, true),
    CleanLight("main", 0, 0, true, false),
    LongBranchLight("feature/very-long-worktree-branch-name-that-must-ellipsis", 48, 2, true, false),
    DisabledLight("feature/session-branch", 12, 3, false, false),
    DisabledDark("main", 0, 0, false, true),
}

@Composable
private fun SessionBranchPillPreview(case: SessionBranchPillPreviewCase) {
    OpenCodeTheme(darkTheme = case.darkTheme) {
        OpenCodeSessionBranchPill(
            branch = case.branch,
            additions = case.additions,
            deletions = case.deletions,
            enabled = case.enabled,
            onClick = {},
        )
    }
}

@Composable
fun OpenCodeSessionBranchPill(
    branch: String,
    additions: Int,
    deletions: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    require(branch.isNotEmpty()) { "Session Branch Pill requires a non-empty branch" }
    require(additions >= 0) { "Session Branch Pill requires nonnegative additions" }
    require(deletions >= 0) { "Session Branch Pill requires nonnegative deletions" }
    val additionLabel = sessionBranchPillAdditionLabel(additions)
    val deletionLabel = sessionBranchPillDeletionLabel(deletions)
    val interactionSource = remember { MutableInteractionSource() }
    val contentColor =
        if (enabled) openCodeColor("text-strong") else MaterialTheme.colorScheme.onSurfaceVariant
    val additionsColor = if (enabled) openCodeColor("diff-add") else MaterialTheme.colorScheme.onSurfaceVariant
    val deletionsColor = if (enabled) openCodeColor("diff-delete") else MaterialTheme.colorScheme.onSurfaceVariant
    val iconColor = if (enabled) openCodeColor("icon-base") else MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceRaised = openCodeColor("surface-raised-base")
    val branchStyle =
        MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier.semantics {
                contentDescription = sessionBranchPillAccessibilityLabel(branch, additions, deletions)
            },
        shape = SessionBranchPillShape,
        color = surfaceRaised.copy(alpha = if (enabled) 0.95f else 0.7f),
        contentColor = contentColor,
        border = BorderStroke(1.dp, openCodeColor("border-weaker-base")),
        tonalElevation = 2.dp,
        shadowElevation = if (enabled) 4.dp else 0.dp,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier =
                Modifier
                    .widthIn(max = 320.dp)
                    .defaultMinSize(minHeight = 36.dp)
                    .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = OpenCodeIcons.CallSplit,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = iconColor,
            )
            Text(
                text = branch,
                style = branchStyle,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            additionLabel?.let { label ->
                Text(
                    text = label,
                    style = branchStyle,
                    color = additionsColor,
                    maxLines = 1,
                )
            }
            deletionLabel?.let { label ->
                Text(
                    text = label,
                    style = branchStyle,
                    color = deletionsColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionBranchPillMixedLightPreview() {
    SessionBranchPillPreview(SessionBranchPillPreviewCase.MixedLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionBranchPillMixedDarkPreview() {
    SessionBranchPillPreview(SessionBranchPillPreviewCase.MixedDark)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionBranchPillAdditionsOnlyLightPreview() {
    SessionBranchPillPreview(SessionBranchPillPreviewCase.AdditionsOnlyLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionBranchPillDeletionsOnlyDarkPreview() {
    SessionBranchPillPreview(SessionBranchPillPreviewCase.DeletionsOnlyDark)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionBranchPillCleanLightPreview() {
    SessionBranchPillPreview(SessionBranchPillPreviewCase.CleanLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionBranchPillLongBranchLightPreview() {
    SessionBranchPillPreview(SessionBranchPillPreviewCase.LongBranchLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionBranchPillDisabledLightPreview() {
    SessionBranchPillPreview(SessionBranchPillPreviewCase.DisabledLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionBranchPillDisabledDarkPreview() {
    SessionBranchPillPreview(SessionBranchPillPreviewCase.DisabledDark)
}
