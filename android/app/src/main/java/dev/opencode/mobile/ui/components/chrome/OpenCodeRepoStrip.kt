package dev.opencode.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val RepoStripShape = RoundedCornerShape(14.dp)
private val RepoStripBadgeShape = RoundedCornerShape(6.dp)

/** Working-copy status of one file shown in the repo strip. */
enum class OpenCodeRepoStripFileStatus {
    Added,
    Modified,
    Deleted,
}

data class OpenCodeRepoStripFile(
    val path: String,
    val status: OpenCodeRepoStripFileStatus,
    val additions: Int,
    val deletions: Int,
)

data class RepoStripSummary(
    /** "4 files", "1 file", or "clean" when nothing is changed. */
    val fileLabel: String,
    val additionLabel: String?,
    val deletionLabel: String?,
) {
    val isClean: Boolean
        get() = additionLabel == null && deletionLabel == null && fileLabel == REPO_STRIP_CLEAN_LABEL
}

internal const val REPO_STRIP_CLEAN_LABEL = "clean"

fun formatRepoStripSummary(files: List<OpenCodeRepoStripFile>): RepoStripSummary {
    if (files.isEmpty()) return RepoStripSummary(REPO_STRIP_CLEAN_LABEL, null, null)
    val additions = files.sumOf { it.additions }
    val deletions = files.sumOf { it.deletions }
    return RepoStripSummary(
        fileLabel = "${files.size} ${if (files.size == 1) "file" else "files"}",
        additionLabel = "+$additions",
        deletionLabel = "−$deletions",
    )
}

fun formatRepoStripContentDescription(
    branch: String,
    files: List<OpenCodeRepoStripFile>,
    expanded: Boolean,
): String {
    val summary = formatRepoStripSummary(files)
    val changes =
        if (files.isEmpty()) {
            "clean working tree"
        } else {
            val additions = files.sumOf { it.additions }
            val deletions = files.sumOf { it.deletions }
            "${summary.fileLabel} changed, $additions ${if (additions == 1) "addition" else "additions"}, " +
                "$deletions ${if (deletions == 1) "deletion" else "deletions"}"
        }
    val disclosure =
        when {
            files.isEmpty() -> ""
            expanded -> ", expanded"
            else -> ", collapsed"
        }
    return "Branch $branch, $changes$disclosure"
}

fun repoStripStatusLetter(status: OpenCodeRepoStripFileStatus): String =
    when (status) {
        OpenCodeRepoStripFileStatus.Added -> "A"
        OpenCodeRepoStripFileStatus.Modified -> "M"
        OpenCodeRepoStripFileStatus.Deleted -> "D"
    }

/**
 * Collapsible summary of the current branch and uncommitted working-tree changes.
 *
 * Collapsed: one row with branch, changed-file count, and line totals.
 * Expanded: the same row plus one line per changed file and an "Open in Changes" footer.
 * A clean working tree renders the row only and cannot expand.
 */
@Composable
fun OpenCodeRepoStrip(
    branch: String,
    defaultBranch: String?,
    files: List<OpenCodeRepoStripFile>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenChanges: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = formatRepoStripSummary(files)
    val canExpand = files.isNotEmpty()
    val isExpanded = expanded && canExpand
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RepoStripShape,
        color = openCodeColor("surface-raised-base"),
        border = BorderStroke(1.dp, openCodeColor("border-weaker-base")),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = formatRepoStripContentDescription(branch, files, isExpanded)
                        }.clickable(enabled = canExpand, onClick = onToggle)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = OpenCodeIcons.CallSplit,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = openCodeColor("icon-base"),
                )
                Text(
                    text = branch,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                    color = openCodeColor("text-strong"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (summary.isClean) {
                    Text(
                        text = summary.fileLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = openCodeColor("text-weak"),
                    )
                } else {
                    Text(
                        text = summary.fileLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = openCodeColor("text-strong"),
                    )
                    summary.additionLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = openCodeColor("diff-add")) }
                    summary.deletionLabel?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = openCodeColor("diff-delete"),
                        )
                    }
                    Icon(
                        imageVector = OpenCodeIcons.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).rotate(if (isExpanded) 180f else 0f),
                        tint = openCodeColor("icon-weak-base"),
                    )
                }
            }
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    HorizontalDivider(color = openCodeColor("border-weaker-base"))
                    files.forEachIndexed { index, file ->
                        if (index > 0) HorizontalDivider(color = openCodeColor("border-weaker-base"))
                        RepoStripFileRow(file)
                    }
                    HorizontalDivider(color = openCodeColor("border-weaker-base"))
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenChanges)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Open in Changes",
                            style = MaterialTheme.typography.labelMedium,
                            color = openCodeColor("interactive"),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (defaultBranch != null && defaultBranch != branch) {
                            Text(
                                text = "$branch → $defaultBranch",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = openCodeColor("text-weak"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoStripFileRow(file: OpenCodeRepoStripFile) {
    val (badgeBackground, badgeForeground) =
        when (file.status) {
            OpenCodeRepoStripFileStatus.Added -> openCodeColor("diff-add").copy(alpha = 0.16f) to openCodeColor("diff-add")
            OpenCodeRepoStripFileStatus.Modified -> openCodeColor("warning").copy(alpha = 0.16f) to openCodeColor("warning")
            OpenCodeRepoStripFileStatus.Deleted -> openCodeColor("diff-delete").copy(alpha = 0.16f) to openCodeColor("diff-delete")
        }
    val separator = file.path.lastIndexOf('/')
    val directory = if (separator >= 0) file.path.substring(0, separator + 1) else ""
    val name = if (separator >= 0) file.path.substring(separator + 1) else file.path
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.size(20.dp).background(badgeBackground, RepoStripBadgeShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = repoStripStatusLetter(file.status),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                color = badgeForeground,
            )
        }
        Text(
            text =
                buildAnnotatedString {
                    withStyle(SpanStyle(color = openCodeColor("text-weak"))) { append(directory) }
                    withStyle(SpanStyle(color = openCodeColor("text-strong"))) { append(name) }
                },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("+${file.additions}", style = MaterialTheme.typography.labelSmall, color = openCodeColor("diff-add"))
        Spacer(modifier = Modifier.width(2.dp))
        Text("−${file.deletions}", style = MaterialTheme.typography.labelSmall, color = openCodeColor("diff-delete"))
    }
}

internal val RepoStripPreviewFiles =
    listOf(
        OpenCodeRepoStripFile(
            "android/app/src/main/java/dev/opencode/mobile/data/remote/HealthApi.kt",
            OpenCodeRepoStripFileStatus.Modified,
            42,
            7,
        ),
        OpenCodeRepoStripFile(
            "ios/OpenCode Mobile/OpenCode Mobile/Data/Remote/HealthAPIClient.swift",
            OpenCodeRepoStripFileStatus.Modified,
            38,
            5,
        ),
        OpenCodeRepoStripFile("android/app/src/test/java/dev/opencode/mobile/HealthProbeTest.kt", OpenCodeRepoStripFileStatus.Added, 61, 0),
        OpenCodeRepoStripFile("docs/features/health-probe.md", OpenCodeRepoStripFileStatus.Deleted, 0, 19),
    )

@Preview(showBackground = true)
@Composable
private fun OpenCodeRepoStripCollapsedPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeRepoStrip(
            branch = "fix/opencode-2-health-probe",
            defaultBranch = "main",
            files = RepoStripPreviewFiles,
            expanded = false,
            onToggle = {},
            onOpenChanges = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeRepoStripExpandedPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeRepoStrip(
            branch = "fix/opencode-2-health-probe",
            defaultBranch = "main",
            files = RepoStripPreviewFiles,
            expanded = true,
            onToggle = {},
            onOpenChanges = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeRepoStripCleanPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeRepoStrip(
            branch = "main",
            defaultBranch = "main",
            files = emptyList(),
            expanded = true,
            onToggle = {},
            onOpenChanges = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
