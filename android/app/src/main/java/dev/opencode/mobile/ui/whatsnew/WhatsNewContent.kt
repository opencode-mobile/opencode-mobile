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
import dev.opencode.mobile.ui.components.OpenCodeFeatureItem
import dev.opencode.mobile.ui.components.OpenCodeFeatureItemTone
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** The app icon closest to a release-notes icon key; an unknown key reads as the sparkle. */
internal fun releaseNoteIcon(key: String): ImageVector =
    when (key) {
        "edit" -> OpenCodeIcons.Edit
        "goal" -> OpenCodeIcons.Target
        "agent" -> OpenCodeIcons.Agent
        "image" -> OpenCodeIcons.Image
        "note" -> OpenCodeIcons.Description
        "search" -> OpenCodeIcons.Search
        "keyboard" -> OpenCodeIcons.Keyboard
        "attach" -> OpenCodeIcons.Paperclip
        "text-select" -> OpenCodeIcons.TextSelect
        "chat" -> OpenCodeIcons.MessageSquare
        "time" -> OpenCodeIcons.Clock
        "sheet" -> OpenCodeIcons.ExpandContent
        "server" -> OpenCodeIcons.Cloud
        "send" -> OpenCodeIcons.ArrowUpward
        "fix" -> OpenCodeIcons.Wrench
        "code" -> OpenCodeIcons.Code
        "qr" -> OpenCodeIcons.QrCode
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
        "fork" -> OpenCodeIcons.GitFork
        "feedback" -> OpenCodeIcons.RateReview
        "phone" -> OpenCodeIcons.Smartphone
        "star" -> OpenCodeIcons.Star
        else -> WhatsNewSparkleIcon
    }

/** The What's new eyebrow and the icon for an unknown key. */
internal val WhatsNewSparkleIcon: ImageVector
    get() = OpenCodeIcons.Sparkles

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
    OpenCodeFeatureItem(
        title = item.title,
        body = item.body,
        icon = releaseNoteIcon(item.iconKey),
        tone = if (item.kind == ReleaseNoteKind.Fixed) OpenCodeFeatureItemTone.Success else OpenCodeFeatureItemTone.Neutral,
    )
}
