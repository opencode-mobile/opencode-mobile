package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val BranchPopoverWidth = 288.dp
private val BranchPopoverShape = RoundedCornerShape(12.dp)
private val BranchPopoverPaddingHorizontal = 16.dp
private val BranchPopoverSectionPaddingVertical = 12.dp
private val BranchPopoverIconSize = 16.dp
private val BranchPopoverIconSpacing = 10.dp
private val BranchPopoverFileRowSpacing = 8.dp

/** One uncommitted file change on the session's branch. */
data class OpenCodeSessionBranchChangedFile(
    val path: String,
    val additions: Int,
    val deletions: Int,
) {
    val linesChanged: Int get() = additions + deletions
}

/**
 * What the Session Branch Pill and its popover show: the session's branch, its base,
 * whether it is a worktree checkout, and the uncommitted file changes.
 */
data class OpenCodeSessionBranchSummary(
    val branch: String,
    val baseBranch: String? = null,
    val isWorktree: Boolean = false,
    val files: List<OpenCodeSessionBranchChangedFile> = emptyList(),
) {
    val additions: Int get() = files.sumOf { it.additions }.coerceAtLeast(0)
    val deletions: Int get() = files.sumOf { it.deletions }.coerceAtLeast(0)
    val hasChanges: Boolean get() = files.isNotEmpty()

    /** Worktree checkouts use the worktree icon from Home and Settings; other sessions the branch icon. */
    val icon: ImageVector get() = if (isWorktree) OpenCodeIcons.CallSplit else OpenCodeIcons.GitBranch

    /**
     * "+128 −14" with zero sides left out, or "2 files" when the changes have no line counts.
     * Null without changes: the pill shows its icon only.
     */
    val changeLabel: String?
        get() {
            if (!hasChanges) return null
            val parts =
                buildList {
                    if (additions > 0) add("+$additions")
                    if (deletions > 0) add("−$deletions")
                }
            return if (parts.isEmpty()) fileCount(files.size, "") else parts.joinToString(" ")
        }

    val pillLayout: OpenCodeFloatingPillLayout
        get() =
            changeLabel?.let { OpenCodeFloatingPillLayout.IconLabel(icon, it) }
                ?: OpenCodeFloatingPillLayout.IconOnly(icon, accessibilityLabel)

    /** "Branch feature-x, 128 added, 14 removed" or "Branch feature-x, no changes". */
    val accessibilityLabel: String
        get() {
            val name = "${if (isWorktree) "Worktree branch" else "Branch"} $branch"
            if (!hasChanges) return "$name, no changes"
            val parts =
                buildList {
                    add(name)
                    if (additions > 0) add("$additions added")
                    if (deletions > 0) add("$deletions removed")
                    if (size == 1) add(fileCount(files.size, " changed"))
                }
            return parts.joinToString(", ")
        }

    /** The base branch when the server reports one that differs from the branch itself. */
    val visibleBaseBranch: String?
        get() = baseBranch?.takeIf { it.isNotEmpty() && it != branch }

    /** "3 files changed"; null without changes. */
    val fileCountLabel: String?
        get() = if (hasChanges) fileCount(files.size, " changed") else null

    /** The most-changed files by lines added plus removed, ties by path. */
    val topFiles: List<OpenCodeSessionBranchChangedFile>
        get() =
            files
                .sortedWith(compareByDescending<OpenCodeSessionBranchChangedFile> { it.linesChanged }.thenBy { it.path })
                .take(TOP_FILE_LIMIT)

    companion object {
        /** The popover lists at most this many files. */
        const val TOP_FILE_LIMIT = 3

        private fun fileCount(
            count: Int,
            suffix: String,
        ): String = "$count ${if (count == 1) "file" else "files"}$suffix"
    }
}

/**
 * Session Chat context pill for the session's branch: an icon and the line counts, or the
 * icon alone with no changes. Tapping it opens a popover with the branch details, the
 * most-changed files, View all changes and Copy branch name. Placement is up to the caller.
 *
 * [onBranchNameCopied] runs after the branch name is on the clipboard, so the host can confirm it.
 */
@Composable
fun OpenCodeSessionBranchPill(
    summary: OpenCodeSessionBranchSummary,
    onViewAllChanges: () -> Unit,
    modifier: Modifier = Modifier,
    onBranchNameCopied: () -> Unit = {},
) {
    require(summary.branch.isNotEmpty()) { "Session Branch Pill requires a non-empty branch" }
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Box(modifier = modifier) {
        OpenCodeFloatingPill(
            layout = summary.pillLayout,
            state = OpenCodeFloatingPillState.Ready,
            accessibilityLabel = summary.accessibilityLabel,
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = BranchPopoverShape,
            containerColor = openCodeColor("surface-raised-base"),
            border = BorderStroke(1.dp, openCodeColor("border-weak-base")),
        ) {
            OpenCodeSessionBranchPopoverContent(
                summary = summary,
                onViewAllChanges = {
                    expanded = false
                    onViewAllChanges()
                },
                onCopyBranchName = {
                    expanded = false
                    clipboard.setText(AnnotatedString(summary.branch))
                    onBranchNameCopied()
                },
            )
        }
    }
}

/** Body of the branch popover. Public so the Component Gallery can show it open. */
@Composable
fun OpenCodeSessionBranchPopoverContent(
    summary: OpenCodeSessionBranchSummary,
    onViewAllChanges: () -> Unit,
    onCopyBranchName: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStrong = openCodeColor("text-strong")
    val textWeak = openCodeColor("text-weak")
    val iconStrong = openCodeColor("icon-strong-base")
    val divider = openCodeColor("border-weak-base")

    Column(modifier = modifier.width(BranchPopoverWidth)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BranchPopoverPaddingHorizontal, vertical = BranchPopoverSectionPaddingVertical)
                    .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(BranchPopoverIconSpacing),
        ) {
            Icon(
                imageVector = summary.icon,
                contentDescription = null,
                tint = iconStrong,
                modifier = Modifier.padding(top = 2.dp).size(BranchPopoverIconSize),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = summary.branch,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = textStrong,
                    maxLines = 2,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                summary.visibleBaseBranch?.let { base ->
                    Text(
                        text = "Based on $base",
                        style = MaterialTheme.typography.bodySmall,
                        color = textWeak,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }
        if (summary.hasChanges) {
            HorizontalDivider(color = divider)
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = BranchPopoverPaddingHorizontal,
                        vertical = BranchPopoverSectionPaddingVertical,
                    ),
                verticalArrangement = Arrangement.spacedBy(BranchPopoverFileRowSpacing),
            ) {
                summary.fileCountLabel?.let { label ->
                    Row(
                        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = textWeak)
                        Spacer(Modifier.weight(1f))
                        OpenCodeDiffStat(additions = summary.additions, deletions = summary.deletions)
                    }
                }
                summary.topFiles.forEach { file ->
                    Row(
                        modifier =
                            Modifier.fillMaxWidth().clearAndSetSemantics {
                                contentDescription = "${file.path}, ${file.additions} added, ${file.deletions} removed"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = file.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = textStrong,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                            modifier = Modifier.weight(1f),
                        )
                        OpenCodeDiffStat(additions = file.additions, deletions = file.deletions)
                    }
                }
            }
        }
        HorizontalDivider(color = divider)
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            if (summary.hasChanges) {
                BranchPopoverAction("View all changes", OpenCodeIcons.Description, onViewAllChanges)
            }
            BranchPopoverAction("Copy branch name", OpenCodeIcons.ContentCopy, onCopyBranchName)
        }
    }
}

@Composable
private fun BranchPopoverAction(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(title, style = MaterialTheme.typography.bodyMedium, color = openCodeColor("text-strong")) },
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = openCodeColor("icon-strong-base"), modifier = Modifier.size(20.dp))
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = BranchPopoverPaddingHorizontal),
        modifier = Modifier.height(48.dp),
    )
}

enum class SessionBranchPillPreviewCase(
    val galleryLabel: String,
    val summary: OpenCodeSessionBranchSummary,
) {
    Changes(
        "Changes",
        OpenCodeSessionBranchSummary("feature/session-branch", baseBranch = "main", files = SessionBranchPillSampleFiles),
    ),
    NoChanges("None", OpenCodeSessionBranchSummary("feature/session-branch", baseBranch = "main")),
    Worktree(
        "Worktree",
        OpenCodeSessionBranchSummary(
            "feature/worktree-checkout",
            baseBranch = "main",
            isWorktree = true,
            files = SessionBranchPillSampleFiles.take(2),
        ),
    ),
    ManyFiles(
        "Many",
        OpenCodeSessionBranchSummary(
            "feature/very-long-worktree-branch-name-that-must-ellipsis",
            baseBranch = "main",
            files =
                SessionBranchPillSampleFiles +
                    listOf(
                        OpenCodeSessionBranchChangedFile("README.md", 3, 0),
                        OpenCodeSessionBranchChangedFile("docs/notes.md", 0, 4),
                    ),
        ),
    ),
}

private val SessionBranchPillSampleFiles =
    listOf(
        OpenCodeSessionBranchChangedFile("app/src/main/session/BranchPill.kt", 96, 4),
        OpenCodeSessionBranchChangedFile("app/src/main/session/BranchPopover.kt", 24, 2),
        OpenCodeSessionBranchChangedFile("app/src/test/BranchPillTest.kt", 8, 8),
    )

@Composable
private fun SessionBranchPillPreview(darkTheme: Boolean) {
    OpenCodeTheme(darkTheme = darkTheme) {
        Surface(color = openCodeColor("background-base")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SessionBranchPillPreviewCase.entries.forEach {
                    OpenCodeSessionBranchPill(summary = it.summary, onViewAllChanges = {})
                }
                OpenCodeSessionBranchPopoverContent(
                    summary = SessionBranchPillPreviewCase.Changes.summary,
                    onViewAllChanges = {},
                    onCopyBranchName = {},
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionBranchPillLightPreview() {
    SessionBranchPillPreview(darkTheme = false)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionBranchPillDarkPreview() {
    SessionBranchPillPreview(darkTheme = true)
}
