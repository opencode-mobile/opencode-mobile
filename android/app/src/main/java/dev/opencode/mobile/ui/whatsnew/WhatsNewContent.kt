package dev.opencode.mobile.ui.whatsnew

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.releasenotes.ReleaseNote
import dev.opencode.mobile.domain.releasenotes.ReleaseNoteItem
import dev.opencode.mobile.domain.releasenotes.ReleaseNoteKind
import dev.opencode.mobile.ui.components.OpenCodeFeatureRow
import dev.opencode.mobile.ui.components.OpenCodeFeatureRowTone
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** The app icon closest to a release-notes icon key; an unknown key reads as the sparkle. */
internal fun releaseNoteIcon(key: String): ImageVector =
    when (key) {
        "edit" -> OpenCodeIcons.Edit
        "goal" -> OpenCodeIcons.RadioButtonChecked
        "agent" -> OpenCodeIcons.Agent
        "image" -> OpenCodeIcons.Widgets
        "note" -> OpenCodeIcons.Description
        "search" -> OpenCodeIcons.Search
        "keyboard" -> OpenCodeIcons.Terminal
        "attach" -> OpenCodeIcons.Add
        "text-select" -> OpenCodeIcons.TextSelect
        "chat" -> OpenCodeIcons.RateReview
        "time" -> OpenCodeIcons.Restart
        "sheet" -> OpenCodeIcons.ExpandContent
        "server" -> OpenCodeIcons.Cloud
        "send" -> OpenCodeIcons.ArrowUpward
        "fix" -> OpenCodeIcons.Check
        "code" -> OpenCodeIcons.Code
        "qr" -> OpenCodeIcons.Widgets
        "git-branch" -> OpenCodeIcons.GitBranch
        "list" -> OpenCodeIcons.List
        "terminal" -> OpenCodeIcons.Terminal
        "slash" -> OpenCodeIcons.Slash
        "lock" -> OpenCodeIcons.Lock
        "archive" -> OpenCodeIcons.Archive
        "folder" -> OpenCodeIcons.Folder
        "sidebar" -> OpenCodeIcons.Sidebar
        "mic" -> OpenCodeIcons.Mic
        "at-sign" -> OpenCodeIcons.AtSign
        "fork" -> OpenCodeIcons.CallSplit
        "feedback" -> OpenCodeIcons.RateReview
        "phone" -> OpenCodeIcons.Widgets
        "star" -> OpenCodeIcons.Star
        else -> WhatsNewSparkleIcon
    }

/** The app has no sparkle icon; the star stands in for it. */
internal val WhatsNewSparkleIcon: ImageVector
    get() = OpenCodeIcons.Star

/** One release's items: new, then improved, then a Fixes header over the fixes. */
@Composable
internal fun WhatsNewReleaseItems(
    release: ReleaseNote,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        release.highlights.forEach { item -> WhatsNewItemRow(item) }
        val fixes = release.fixes
        if (fixes.isNotEmpty()) {
            OpenCodeSectionHeader(title = stringResource(R.string.whats_new_fixes))
            fixes.forEach { item -> WhatsNewItemRow(item) }
        }
    }
}

@Composable
private fun WhatsNewItemRow(item: ReleaseNoteItem) {
    OpenCodeFeatureRow(
        title = item.title,
        body = item.body,
        icon = releaseNoteIcon(item.iconKey),
        tone = if (item.kind == ReleaseNoteKind.Fixed) OpenCodeFeatureRowTone.Success else OpenCodeFeatureRowTone.Neutral,
    )
}
