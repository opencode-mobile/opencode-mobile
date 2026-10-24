package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemAccessory
import dev.opencode.mobile.ui.components.OpenCodeListItemPath
import dev.opencode.mobile.ui.components.OpenCodeListItemStatus
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeListItemSubtitleSegment
import dev.opencode.mobile.ui.components.OpenCodeListItemSubtitleTone
import dev.opencode.mobile.ui.components.OpenCodeListItemVariant
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private enum class GalleryDecoratorKind {
    None,
    Letter,
    LetterWorktree,
    Icon,
    IconCircle,
    ;

    val label: String
        get() =
            when (this) {
                None -> "None"
                Letter -> "Letter"
                LetterWorktree -> "Letter + worktree"
                Icon -> "Icon"
                IconCircle -> "Icon circle"
            }
}

private enum class GallerySubtitleKind {
    Plain,
    Diff,
    Branch,
    Off,
}

private enum class GalleryFooterKind {
    Off,
    StatusAndCaption,
    StatusOnly,
    CaptionOnly,
}

@Composable
fun ListItemGalleryScreen(modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableStateOf(false) }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var cardStyle by rememberSaveable { mutableStateOf(false) }
    var subtitleKindName by rememberSaveable { mutableStateOf(GallerySubtitleKind.Plain.name) }
    var footerKindName by rememberSaveable { mutableStateOf(GalleryFooterKind.StatusAndCaption.name) }
    var statusToneName by rememberSaveable { mutableStateOf(OpenCodeListItemStatusTone.Interactive.name) }
    var decoratorKindName by rememberSaveable { mutableStateOf(GalleryDecoratorKind.None.name) }
    var letterAccentName by rememberSaveable { mutableStateOf(LetterAccent.Violet.name) }
    var toggleOn by rememberSaveable { mutableStateOf(true) }
    var showTitleTag by rememberSaveable { mutableStateOf(false) }

    val decoratorKind =
        try {
            GalleryDecoratorKind.valueOf(decoratorKindName)
        } catch (_: IllegalArgumentException) {
            GalleryDecoratorKind.None
        }
    val letterAccent =
        try {
            LetterAccent.valueOf(letterAccentName)
        } catch (_: IllegalArgumentException) {
            LetterAccent.Violet
        }
    val subtitleKind =
        try {
            GallerySubtitleKind.valueOf(subtitleKindName)
        } catch (_: IllegalArgumentException) {
            GallerySubtitleKind.Plain
        }
    val footerKind =
        try {
            GalleryFooterKind.valueOf(footerKindName)
        } catch (_: IllegalArgumentException) {
            GalleryFooterKind.StatusAndCaption
        }
    val statusTone =
        try {
            OpenCodeListItemStatusTone.valueOf(statusToneName)
        } catch (_: IllegalArgumentException) {
            OpenCodeListItemStatusTone.Interactive
        }

    val startDecorator: StartDecorator =
        when (decoratorKind) {
            GalleryDecoratorKind.None -> StartDecorator.None
            GalleryDecoratorKind.Letter -> StartDecorator.Letter('A', letterAccent)
            GalleryDecoratorKind.LetterWorktree ->
                StartDecorator.Letter('A', letterAccent, statusDot = statusTone, worktree = true)
            GalleryDecoratorKind.Icon ->
                StartDecorator.Icon(imageVector = OpenCodeIcons.Folder, contentDescription = "Folder")
            GalleryDecoratorKind.IconCircle ->
                StartDecorator.IconCircle(imageVector = OpenCodeIcons.Widgets, contentDescription = "All projects")
        }

    val subtitle: String? = if (subtitleKind == GallerySubtitleKind.Plain) "http://127.0.0.1:4096/" else null
    val subtitleSegments =
        if (subtitleKind == GallerySubtitleKind.Diff) {
            listOf(
                OpenCodeListItemSubtitleSegment("Modified · ", OpenCodeListItemSubtitleTone.Secondary),
                OpenCodeListItemSubtitleSegment("+51", OpenCodeListItemSubtitleTone.Addition),
                OpenCodeListItemSubtitleSegment(" ", OpenCodeListItemSubtitleTone.Secondary),
                OpenCodeListItemSubtitleSegment("−7", OpenCodeListItemSubtitleTone.Deletion),
            )
        } else if (subtitleKind == GallerySubtitleKind.Branch) {
            listOf(
                OpenCodeListItemSubtitleSegment("opencode-mobile", OpenCodeListItemSubtitleTone.Secondary, icon = OpenCodeIcons.Folder),
            )
        } else {
            null
        }
    val detailSegments =
        if (subtitleKind == GallerySubtitleKind.Branch) {
            listOf(
                OpenCodeListItemSubtitleSegment(
                    "feature/723-iphone-duo",
                    OpenCodeListItemSubtitleTone.Tertiary,
                    icon = OpenCodeIcons.CallSplit,
                ),
                OpenCodeListItemSubtitleSegment(" · opencode-mobile-723", OpenCodeListItemSubtitleTone.Tertiary),
            )
        } else {
            null
        }

    val status =
        when (footerKind) {
            GalleryFooterKind.Off, GalleryFooterKind.CaptionOnly -> null
            GalleryFooterKind.StatusAndCaption, GalleryFooterKind.StatusOnly ->
                OpenCodeListItemStatus(text = "Working · 14m", tone = statusTone)
        }
    val caption =
        when (footerKind) {
            GalleryFooterKind.Off, GalleryFooterKind.StatusOnly -> null
            GalleryFooterKind.StatusAndCaption, GalleryFooterKind.CaptionOnly -> "sidebar-v2-concepts"
        }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "List item", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenCodeChoiceBadge(label = "Selected", selected = selected, onClick = { selected = !selected })
                OpenCodeChoiceBadge(label = "Enabled", selected = enabled, onClick = { enabled = !enabled })
                OpenCodeChoiceBadge(label = "Title tag", selected = showTitleTag, onClick = { showTitleTag = !showTitleTag })
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Subtitle", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GallerySubtitleKind.entries.forEach { kind ->
                    OpenCodeChoiceBadge(
                        label = kind.name,
                        selected = subtitleKind == kind,
                        onClick = { subtitleKindName = kind.name },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Footer", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GalleryFooterKind.entries.forEach { kind ->
                    OpenCodeChoiceBadge(
                        label =
                            when (kind) {
                                GalleryFooterKind.Off -> "Off"
                                GalleryFooterKind.StatusAndCaption -> "Status+Caption"
                                GalleryFooterKind.StatusOnly -> "Status only"
                                GalleryFooterKind.CaptionOnly -> "Caption only"
                            },
                        selected = footerKind == kind,
                        onClick = { footerKindName = kind.name },
                    )
                }
            }
        }

        if (footerKind == GalleryFooterKind.StatusAndCaption || footerKind == GalleryFooterKind.StatusOnly) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Status tone", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpenCodeListItemStatusTone.entries.forEach { tone ->
                        OpenCodeChoiceBadge(
                            label = tone.name,
                            selected = statusTone == tone,
                            onClick = { statusToneName = tone.name },
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Style", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenCodeChoiceBadge(label = "Plain", selected = !cardStyle, onClick = { cardStyle = false })
                OpenCodeChoiceBadge(label = "Card", selected = cardStyle, onClick = { cardStyle = true })
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Start decorator", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryDecoratorKind.entries.forEach { kind ->
                    OpenCodeChoiceBadge(
                        label = kind.label,
                        selected = decoratorKind == kind,
                        onClick = { decoratorKindName = kind.name },
                    )
                }
            }
        }

        if (decoratorKind == GalleryDecoratorKind.Letter || decoratorKind == GalleryDecoratorKind.LetterWorktree) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Letter accent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LetterAccent.entries.forEach { accent ->
                        OpenCodeChoiceBadge(
                            label = accent.name,
                            selected = letterAccent == accent,
                            onClick = { letterAccentName = accent.name },
                        )
                    }
                }
            }
        }

        OpenCodeListItem(
            title = "Material Design Icons integration plan",
            subtitle = subtitle,
            subtitleSegments = subtitleSegments,
            detailSegments = detailSegments,
            status = status,
            caption = caption,
            titleTag = if (showTitleTag) "git" else null,
            selected = selected,
            enabled = enabled,
            style = if (cardStyle) OpenCodeListItemStyle.Card else OpenCodeListItemStyle.Plain,
            startDecorator = startDecorator,
        )

        Text(text = "Detail line", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Column {
            OpenCodeListItem(
                title = "Code samples request",
                subtitleSegments =
                    listOf(
                        OpenCodeListItemSubtitleSegment(
                            "opencode-mobile",
                            OpenCodeListItemSubtitleTone.Secondary,
                            icon = OpenCodeIcons.Folder,
                        ),
                    ),
                detailSegments =
                    listOf(
                        OpenCodeListItemSubtitleSegment("main", OpenCodeListItemSubtitleTone.Tertiary, icon = OpenCodeIcons.CallSplit),
                    ),
                status = OpenCodeListItemStatus(text = "Done", tone = OpenCodeListItemStatusTone.Success),
                caption = "1h ago",
                startDecorator = StartDecorator.Letter('O', LetterAccent.Red),
                onClick = {},
            )
            OpenCodeListItem(
                title = "Keep split pane in step with the sidebar",
                subtitleSegments =
                    listOf(
                        OpenCodeListItemSubtitleSegment(
                            "opencode-mobile-enterprise-edition",
                            OpenCodeListItemSubtitleTone.Secondary,
                            icon = OpenCodeIcons.Folder,
                        ),
                    ),
                detailSegments =
                    listOf(
                        OpenCodeListItemSubtitleSegment(
                            "feature/ope-1042-show-worktree-and-project-on-home-rows",
                            OpenCodeListItemSubtitleTone.Tertiary,
                            icon = OpenCodeIcons.CallSplit,
                        ),
                        OpenCodeListItemSubtitleSegment(" · opencode-mobile-1042", OpenCodeListItemSubtitleTone.Tertiary),
                    ),
                status = OpenCodeListItemStatus(text = "Running", tone = OpenCodeListItemStatusTone.Interactive),
                caption = "13m ago",
                startDecorator = StartDecorator.Letter('O', LetterAccent.Red, worktree = true),
                onClick = {},
            )
        }

        Text(text = "Compact variant", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Column {
            galleryCompactRows.forEach { row ->
                OpenCodeListItem(
                    title = row.title,
                    variant = OpenCodeListItemVariant.Compact,
                    path = row.path,
                    titleEmphasized = row.emphasized,
                    status = row.status,
                    caption = row.caption,
                    startDecorator =
                        StartDecorator.Tile(
                            character = row.path.project.first(),
                            accent = row.accent,
                            statusDot = row.status?.tone,
                            pulsing = row.pulsing,
                        ),
                    onClick = {},
                )
            }
        }

        Text(text = "Title tag", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Column {
            OpenCodeListItem(
                title = "opencode-mobile",
                subtitle = "On Home · 12 folders",
                titleTag = "git",
                startDecorator = StartDecorator.Icon(imageVector = OpenCodeIcons.Folder, contentDescription = null),
                onClick = {},
            )
            OpenCodeListItem(
                title = "a-very-long-repository-name-that-truncates-before-the-tag",
                subtitle = "Empty",
                titleTag = "git",
                startDecorator = StartDecorator.Icon(imageVector = OpenCodeIcons.Folder, contentDescription = null),
                onClick = {},
            )
        }

        Text(text = "Plain selection", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Column {
            OpenCodeListItem(
                title = "Unselected session",
                subtitle = "Plain row",
                style = OpenCodeListItemStyle.Plain,
                onClick = {},
            )
            OpenCodeListItem(
                title = "Selected session",
                subtitle = "Inset highlight",
                selected = true,
                style = OpenCodeListItemStyle.Plain,
                onClick = {},
            )
            OpenCodeListItem(
                title = "Unselected session",
                subtitle = "Plain row",
                style = OpenCodeListItemStyle.Plain,
                onClick = {},
            )
        }

        Text(text = "Trailing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        OpenCodeListItem(
            title = "Disclosure",
            subtitle = "Enabled",
            style = OpenCodeListItemStyle.Card,
            accessory = OpenCodeListItemAccessory.Disclosure,
            onClick = {},
        )
        OpenCodeListItem(
            title = "Disclosure",
            subtitle = "Disabled",
            enabled = false,
            style = OpenCodeListItemStyle.Card,
            accessory = OpenCodeListItemAccessory.Disclosure,
            onClick = {},
        )
        OpenCodeListItem(
            title = "Toggle",
            subtitle = "Enabled",
            style = OpenCodeListItemStyle.Card,
            accessory = OpenCodeListItemAccessory.Toggle(checked = toggleOn, onCheckedChange = { toggleOn = it }),
        )
        OpenCodeListItem(
            title = "Toggle",
            subtitle = "Disabled",
            enabled = false,
            style = OpenCodeListItemStyle.Card,
            accessory = OpenCodeListItemAccessory.Toggle(checked = false, onCheckedChange = {}),
        )
    }
}

private data class GalleryCompactRow(
    val title: String,
    val path: OpenCodeListItemPath,
    val accent: LetterAccent,
    val caption: String,
    val status: OpenCodeListItemStatus? = null,
    val pulsing: Boolean = false,
    val emphasized: Boolean = false,
)

private val galleryCompactRows =
    listOf(
        GalleryCompactRow(
            title = "Better action sheet tool results UI",
            path = OpenCodeListItemPath("opencode-mobile", "794-readable-tool-results"),
            accent = LetterAccent.Red,
            caption = "4m ago",
            status = OpenCodeListItemStatus("Running", OpenCodeListItemStatusTone.Interactive),
            pulsing = true,
        ),
        GalleryCompactRow(
            title = "Skill session image message duplicate prompt",
            path = OpenCodeListItemPath("opencode-mobile", "skill-image-duplicate-prompt"),
            accent = LetterAccent.Red,
            caption = "6m ago",
            status = OpenCodeListItemStatus("Done", OpenCodeListItemStatusTone.Success),
            emphasized = true,
        ),
        GalleryCompactRow(
            title = "Live reload for published sites",
            path = OpenCodeListItemPath("artifact-publisher", "live-reload"),
            accent = LetterAccent.Violet,
            caption = "35m ago",
            status = OpenCodeListItemStatus("Retrying · 2", OpenCodeListItemStatusTone.Warning),
        ),
        GalleryCompactRow(
            title = "Checking open PRs",
            path = OpenCodeListItemPath("opencode-mobile", "skill-image-duplicate-prompt"),
            accent = LetterAccent.Red,
            caption = "17h ago",
            status = OpenCodeListItemStatus("Failed", OpenCodeListItemStatusTone.Error),
            emphasized = true,
        ),
        GalleryCompactRow(
            title = "Fix zsh prompt slowness",
            path = OpenCodeListItemPath("dotfiles"),
            accent = LetterAccent.Teal,
            caption = "2h ago",
            status = OpenCodeListItemStatus("Stopped", OpenCodeListItemStatusTone.Warning),
            emphasized = true,
        ),
        GalleryCompactRow(
            title = "Idle on the main checkout: no dot, no status",
            path = OpenCodeListItemPath("opencode-mobile"),
            accent = LetterAccent.Red,
            caption = "2d ago",
        ),
        GalleryCompactRow(
            title = "Long names share line 2 by the 40/60 rule",
            path =
                OpenCodeListItemPath(
                    "opencode-mobile-enterprise-edition",
                    "ope-1042-show-worktree-and-project-on-home-rows",
                ),
            accent = LetterAccent.Red,
            caption = "13m ago",
            status = OpenCodeListItemStatus("Ready", OpenCodeListItemStatusTone.Success),
            emphasized = true,
        ),
        GalleryCompactRow(
            title = "A short project keeps its full width",
            path = OpenCodeListItemPath("dotfiles", "806-clear-the-worktree-filter-when-it-is-empty"),
            accent = LetterAccent.Teal,
            caption = "1d ago",
        ),
    )
