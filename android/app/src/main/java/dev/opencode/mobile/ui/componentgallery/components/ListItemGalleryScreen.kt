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
import dev.opencode.mobile.ui.components.OpenCodeListItemStatus
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeListItemSubtitleSegment
import dev.opencode.mobile.ui.components.OpenCodeListItemSubtitleTone
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private enum class GalleryDecoratorKind {
    None,
    Letter,
    Icon,
    IconCircle,
    ;

    val label: String
        get() =
            when (this) {
                None -> "None"
                Letter -> "Letter"
                Icon -> "Icon"
                IconCircle -> "Icon circle"
            }
}

private enum class GallerySubtitleKind {
    Plain,
    Diff,
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

        if (decoratorKind == GalleryDecoratorKind.Letter) {
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
            status = status,
            caption = caption,
            selected = selected,
            enabled = enabled,
            style = if (cardStyle) OpenCodeListItemStyle.Card else OpenCodeListItemStyle.Plain,
            startDecorator = startDecorator,
        )

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
