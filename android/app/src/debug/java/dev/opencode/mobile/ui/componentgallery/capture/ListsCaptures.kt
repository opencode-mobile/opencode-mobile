package dev.opencode.mobile.ui.componentgallery.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.componentgallery.components.ChartsGalleryBandCard
import dev.opencode.mobile.ui.componentgallery.components.ChartsGalleryBarCard
import dev.opencode.mobile.ui.componentgallery.components.ChartsGalleryFooterSparkline
import dev.opencode.mobile.ui.componentgallery.components.ChartsGalleryProportionCard
import dev.opencode.mobile.ui.componentgallery.components.ChartsGallerySparkline
import dev.opencode.mobile.ui.componentgallery.components.ChartsGalleryStatTiles
import dev.opencode.mobile.ui.componentgallery.components.ChartsGalleryStripCard
import dev.opencode.mobile.ui.componentgallery.components.PickerCardGalleryLocal
import dev.opencode.mobile.ui.componentgallery.components.PickerCardGalleryNewWorktree
import dev.opencode.mobile.ui.componentgallery.components.PickerCardGalleryNotGit
import dev.opencode.mobile.ui.componentgallery.components.SelectableCardGalleryPreview
import dev.opencode.mobile.ui.componentgallery.components.listGroupGalleryFiles
import dev.opencode.mobile.ui.componentgallery.components.reorderableGalleryProjects
import dev.opencode.mobile.ui.components.OpenCodeChartTone
import dev.opencode.mobile.ui.components.OpenCodeFeatureItem
import dev.opencode.mobile.ui.components.OpenCodeFeatureItemTone
import dev.opencode.mobile.ui.components.OpenCodeKeyValueRow
import dev.opencode.mobile.ui.components.OpenCodeKeyValueStyle
import dev.opencode.mobile.ui.components.OpenCodeListGroup
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemAccessory
import dev.opencode.mobile.ui.components.OpenCodeListItemPath
import dev.opencode.mobile.ui.components.OpenCodeListItemStatus
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeListItemTextFlow
import dev.opencode.mobile.ui.components.OpenCodeListItemVariant
import dev.opencode.mobile.ui.components.OpenCodeOptionCard
import dev.opencode.mobile.ui.components.OpenCodeOptionCardPreview
import dev.opencode.mobile.ui.components.OpenCodeReorderableList
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderInset
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderTrailing
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderVariant
import dev.opencode.mobile.ui.components.OpenCodeSelectableCard
import dev.opencode.mobile.ui.components.OpenCodeSkeletonIllustrationTopic
import dev.opencode.mobile.ui.components.OpenCodeTagTone
import dev.opencode.mobile.ui.components.OpenCodeTreeRow
import dev.opencode.mobile.ui.components.OpenCodeTreeRowDisclosure
import dev.opencode.mobile.ui.components.OpenCodeTreeRowIcon
import dev.opencode.mobile.ui.components.OpenCodeTreeRowState
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

internal fun listsCaptures(): List<ComponentCaptureComponent> =
    listOf(
        keyValueRowCaptures(),
        chartsCaptures(),
        listGroupCaptures(),
        listItemCaptures(),
        treeRowCaptures(),
        selectableCardCaptures(),
        optionCardCaptures(),
        pickerCardCaptures(),
        featureItemCaptures(),
        sectionHeaderCaptures(),
        reorderableListCaptures(),
    )

private fun keyValueRowCaptures() =
    ComponentCaptureComponent(
        id = "key-value-row",
        states =
            listOf(
                ComponentCaptureState("inline") { OpenCodeKeyValueRow(label = "Outcome", value = "Highlighted") },
                ComponentCaptureState("inline-short") { OpenCodeKeyValueRow(label = "Backend", value = "native") },
                ComponentCaptureState("stacked") {
                    OpenCodeKeyValueRow(label = "Tokens", value = "12.4k", style = OpenCodeKeyValueStyle.Stacked)
                },
                ComponentCaptureState("stacked-cost") {
                    OpenCodeKeyValueRow(label = "Cost", value = "$0.02", style = OpenCodeKeyValueStyle.Stacked)
                },
            ),
    )

private fun chartsCaptures() =
    ComponentCaptureComponent(
        id = "charts",
        states =
            listOf(
                ComponentCaptureState("band-chart") { ChartsGalleryBandCard() },
                ComponentCaptureState("status-strip") { ChartsGalleryStripCard() },
                ComponentCaptureState("bar-chart") { ChartsGalleryBarCard() },
                ComponentCaptureState("sparkline") { ChartsGallerySparkline(tone = OpenCodeChartTone.Success, height = 40.dp) },
                ComponentCaptureState("sparkline-gaps-hidden") {
                    ChartsGallerySparkline(tone = OpenCodeChartTone.Warning, height = 40.dp, showsGaps = false)
                },
                ComponentCaptureState("stat-tiles") { ChartsGalleryStatTiles() },
                ComponentCaptureState("proportion-rows") { ChartsGalleryProportionCard() },
                ComponentCaptureState("list-item-footer") { listItemFooter() },
            ),
    )

private fun listGroupCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        rows: Int,
    ) = ComponentCaptureState(id) {
        val files = listGroupGalleryFiles.take(rows)
        OpenCodeListGroup(itemCount = files.size) { index ->
            OpenCodeListItem(title = files[index], accessory = OpenCodeListItemAccessory.Disclosure)
        }
    }
    return ComponentCaptureComponent(
        id = "list-group",
        states = listOf(state("three-rows", 3), state("two-rows", 2), state("one-row", 1), state("empty", 0)),
    )
}

private const val LIST_ITEM_TITLE = "opencode-mobile"
private const val LIST_ITEM_SUBTITLE = "Fix composer outline fade"
private val ReadyStatus = OpenCodeListItemStatus("Ready", OpenCodeListItemStatusTone.Success)
private val ListItemLetter = StartDecorator.Letter('O', LetterAccent.Violet)

@Composable
private fun listItemFooter() {
    OpenCodeListItem(
        title = "Workstation",
        subtitle = "192.168.1.2:4096",
        status = OpenCodeListItemStatus("Active", OpenCodeListItemStatusTone.Interactive),
        style = OpenCodeListItemStyle.Card,
        startDecorator = StartDecorator.Letter('W', LetterAccent.Blue),
        onClick = {},
        footer = { ChartsGalleryFooterSparkline() },
    )
}

private fun listItemCaptures(): ComponentCaptureComponent {
    fun standard(
        id: String,
        title: String = LIST_ITEM_TITLE,
        subtitle: String? = LIST_ITEM_SUBTITLE,
        caption: String? = "2m",
        status: OpenCodeListItemStatus? = ReadyStatus,
        style: OpenCodeListItemStyle = OpenCodeListItemStyle.Plain,
        decorator: StartDecorator = ListItemLetter,
        accessory: OpenCodeListItemAccessory? = null,
        selected: Boolean = false,
        enabled: Boolean = true,
        pressed: Boolean = false,
        textFlow: OpenCodeListItemTextFlow = OpenCodeListItemTextFlow.SingleLine,
    ) = ComponentCaptureState(id, press = if (pressed) CapturePress() else null) {
        OpenCodeListItem(
            title = title,
            subtitle = subtitle,
            status = status,
            caption = caption,
            selected = selected,
            enabled = enabled,
            style = style,
            startDecorator = decorator,
            accessory = accessory,
            onClick = {},
            textFlow = textFlow,
        )
    }

    fun compact(
        id: String,
        title: String,
        caption: String,
        path: OpenCodeListItemPath,
        status: OpenCodeListItemStatus,
        pulsing: Boolean = false,
        emphasized: Boolean = false,
    ) = ComponentCaptureState(id) {
        OpenCodeListItem(
            title = title,
            variant = OpenCodeListItemVariant.Compact,
            path = path,
            titleEmphasized = emphasized,
            status = status,
            caption = caption,
            startDecorator = StartDecorator.Tile('O', LetterAccent.Red, statusDot = status.tone, pulsing = pulsing),
            onClick = {},
        )
    }
    val interactiveDot = StartDecorator.Letter('O', LetterAccent.Violet, statusDot = OpenCodeListItemStatusTone.Interactive)
    return ComponentCaptureComponent(
        id = "list-item",
        states =
            listOf(
                standard("plain"),
                standard("card", style = OpenCodeListItemStyle.Card),
                standard("selected", selected = true),
                standard("pressed", pressed = true),
                standard("disabled", enabled = false),
                standard("status-dot", status = null, decorator = interactiveDot),
                standard(
                    "worktree",
                    decorator = StartDecorator.Letter('O', LetterAccent.Violet, OpenCodeListItemStatusTone.Interactive, worktree = true),
                ),
                standard(
                    "icon-chevron",
                    caption = null,
                    status = null,
                    decorator = StartDecorator.Icon(OpenCodeIcons.Folder, contentDescription = null),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                ),
                standard(
                    "icon-circle",
                    title = "All projects",
                    subtitle = "8 projects",
                    caption = null,
                    status = null,
                    decorator = StartDecorator.IconCircle(OpenCodeIcons.Widgets, contentDescription = null),
                ),
                standard(
                    "toggle",
                    caption = null,
                    status = null,
                    style = OpenCodeListItemStyle.Card,
                    decorator = StartDecorator.None,
                    accessory = OpenCodeListItemAccessory.Toggle(checked = true, onCheckedChange = {}),
                ),
                compact(
                    "compact",
                    title = "Keep split pane in step with the sidebar",
                    caption = "now",
                    path = OpenCodeListItemPath("opencode-mobile", "803-compact-home-rows"),
                    status = OpenCodeListItemStatus("Running", OpenCodeListItemStatusTone.Interactive),
                    pulsing = true,
                ),
                compact(
                    "compact-emphasized",
                    title = "Bump Android to 1.2.0 and iOS to 1.1.0",
                    caption = "3m ago",
                    path = OpenCodeListItemPath("opencode-mobile"),
                    status = OpenCodeListItemStatus("Done", OpenCodeListItemStatusTone.Success),
                    emphasized = true,
                ),
                standard(
                    "wrapped",
                    title = "Keep the current layout and move the settings into a separate screen",
                    subtitle =
                        "Every word of a long description stays readable, and the selection mark stays " +
                            "beside the first line of the title however tall the row grows.",
                    caption = null,
                    status = null,
                    style = OpenCodeListItemStyle.Card,
                    decorator = StartDecorator.Icon(OpenCodeIcons.RadioButtonUnchecked, contentDescription = null),
                    textFlow = OpenCodeListItemTextFlow.Wrapped,
                ),
                ComponentCaptureState("footer") { listItemFooter() },
            ),
    )
}

private fun treeRowCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        title: String,
        folder: Boolean = false,
        disclosure: OpenCodeTreeRowDisclosure = OpenCodeTreeRowDisclosure.None,
        rowState: OpenCodeTreeRowState = OpenCodeTreeRowState.Normal,
        subtitle: String? = null,
        tag: String? = null,
        depth: Int = 0,
        fileTypeIcon: Boolean = false,
    ) = ComponentCaptureState(id) {
        if (fileTypeIcon) {
            OpenCodeTreeRow(title = title, icon = OpenCodeTreeRowIcon.FileType(title), depth = depth, onClick = {})
        } else {
            OpenCodeTreeRow(
                title = title,
                icon = if (folder) OpenCodeIcons.Folder else OpenCodeIcons.Description,
                depth = depth,
                subtitle = subtitle,
                disclosure = disclosure,
                state = rowState,
                tag = tag,
                onClick = {},
            )
        }
    }
    return ComponentCaptureComponent(
        id = "tree-row",
        states =
            listOf(
                state("normal", "ContentView.swift"),
                state("selected", "ContentView.swift", rowState = OpenCodeTreeRowState.Selected),
                state(
                    "loading",
                    "Sources",
                    folder = true,
                    disclosure = OpenCodeTreeRowDisclosure.Expanded,
                    rowState = OpenCodeTreeRowState.Loading,
                ),
                state(
                    "dimmed-tag",
                    "node_modules",
                    folder = true,
                    disclosure = OpenCodeTreeRowDisclosure.Collapsed,
                    rowState = OpenCodeTreeRowState.Dimmed,
                    tag = "ignored",
                ),
                state("with-subtitle", "FilesView.swift", subtitle = "ios/OpenCode Mobile/UI/Files"),
                state("file-type-icon", "package.json", fileTypeIcon = true),
                state("depth-2", "ContentView.swift", depth = 2),
            ),
    )
}

private fun selectableCardCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        selected: Boolean,
    ) = ComponentCaptureState(id) {
        OpenCodeSelectableCard(
            title = "Porcelain",
            blurb = "Warm paper and ink.",
            selected = selected,
            onClick = {},
            preview = { SelectableCardGalleryPreview() },
        )
    }
    return ComponentCaptureComponent(id = "selectable-card", states = listOf(state("unselected", false), state("selected", true)))
}

private fun optionCardCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        title: String,
        subtitle: String? = null,
        tag: String? = null,
        tagTone: OpenCodeTagTone = OpenCodeTagTone.Accent,
        preview: OpenCodeSkeletonIllustrationTopic? = null,
        selected: Boolean = false,
        enabled: Boolean = true,
    ) = ComponentCaptureState(id) {
        OpenCodeOptionCard(
            title = title,
            onClick = {},
            subtitle = subtitle,
            tag = tag,
            tagTone = tagTone,
            preview = preview?.let { OpenCodeOptionCardPreview.Illustration(it) } ?: OpenCodeOptionCardPreview.None,
            selected = selected,
            enabled = enabled,
        )
    }
    val activity = OpenCodeSkeletonIllustrationTopic.TimelineActivityCard
    return ComponentCaptureComponent(
        id = "option-card",
        states =
            listOf(
                state(
                    "selected",
                    "Activity Card",
                    "Running steps on one live card that folds into a summary",
                    "Recommended",
                    preview = activity,
                    selected = true,
                ),
                state(
                    "unselected",
                    "Standard",
                    "Tool cards and groups between the agent's messages",
                    "Current",
                    OpenCodeTagTone.Neutral,
                    OpenCodeSkeletonIllustrationTopic.TimelineStandard,
                ),
                state(
                    "long-title",
                    "Activity Card with a longer title",
                    "Running steps on one live card that folds into a summary when the turn ends",
                    "Recommended",
                    preview = activity,
                ),
                state("no-preview", "No preview", "Text-only option"),
                state(
                    "disabled",
                    "Chips",
                    "Tools as chips under the agent's narration",
                    preview = OpenCodeSkeletonIllustrationTopic.TimelineChips,
                    enabled = false,
                ),
                state("selected-disabled", "Selected and disabled", selected = true, enabled = false),
            ),
    )
}

private fun pickerCardCaptures() =
    ComponentCaptureComponent(
        id = "picker-card",
        alignment = Alignment.TopCenter,
        states =
            listOf(
                ComponentCaptureState("local") { PickerCardGalleryLocal() },
                ComponentCaptureState("new-worktree-compact") { PickerCardGalleryNewWorktree() },
                ComponentCaptureState("disabled-row") { PickerCardGalleryNotGit() },
            ),
    )

private fun featureItemCaptures() =
    ComponentCaptureComponent(
        id = "feature-item",
        states =
            listOf(
                ComponentCaptureState("neutral") {
                    OpenCodeFeatureItem(
                        title = "Start a session as a draft",
                        body = "Write first, then pick the project and worktree. The session is created when you send.",
                        icon = OpenCodeIcons.Edit,
                    )
                },
                ComponentCaptureState("title-only") { OpenCodeFeatureItem(title = "Title only", icon = OpenCodeIcons.Star) },
                ComponentCaptureState("success") {
                    OpenCodeFeatureItem(
                        title = "Steadier reconnects",
                        body = "Session Chat picks up where it left off after the network drops.",
                        icon = OpenCodeIcons.Check,
                        tone = OpenCodeFeatureItemTone.Success,
                    )
                },
            ),
    )

private fun sectionHeaderCaptures() =
    ComponentCaptureComponent(
        id = "section-header",
        states =
            listOf(
                ComponentCaptureState("label") { OpenCodeSectionHeader(title = "Server") },
                ComponentCaptureState("plain-with-count") {
                    OpenCodeSectionHeader(
                        title = "Sources/App/Features/SessionChat",
                        variant = OpenCodeSectionHeaderVariant.Plain,
                        trailing = OpenCodeSectionHeaderTrailing.Count(3),
                    )
                },
                ComponentCaptureState("plain-with-action") {
                    OpenCodeSectionHeader(
                        title = "3 waiting",
                        variant = OpenCodeSectionHeaderVariant.Plain,
                        trailing = OpenCodeSectionHeaderTrailing.Action("Clear all") {},
                    )
                },
                ComponentCaptureState("flush") {
                    // As in the gallery: the flush header sits on content its container already insets.
                    Column {
                        OpenCodeSectionHeader(title = "Context breakdown", inset = OpenCodeSectionHeaderInset.Flush)
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .background(openCodeColor("border-weak-base"), RoundedCornerShape(4.dp)),
                        )
                    }
                },
            ),
    )

private fun reorderableListCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        reorder: Boolean = true,
        contextAction: Boolean = true,
        handles: Boolean = false,
        press: CapturePress? = null,
    ) = ComponentCaptureState(id, press = press) {
        val items = reorderableGalleryProjects
        OpenCodeReorderableList(
            items = items,
            key = { it.id },
            onSelect = {},
            canMove = { !it.isAll },
            onMove = if (reorder) ({ _, _ -> }) else null,
            onContextAction = if (contextAction) ({}) else null,
            supportsContextAction = { !it.isAll },
        ) { item ->
            OpenCodeListItem(
                title = item.title,
                subtitle = item.path,
                startDecorator =
                    if (item.isAll) {
                        StartDecorator.IconCircle(OpenCodeIcons.Widgets, null)
                    } else {
                        StartDecorator.Letter(item.title.first(), item.accent)
                    },
                // Row 2 is the one the contract's row states act on.
                modifier = if (item == items[1]) Modifier.testTag(CAPTURE_PRESS_TAG) else Modifier,
                trailing = if (handles) ({ DragHandle(Modifier) }) else null,
            )
        }
    }
    return ComponentCaptureComponent(
        id = "reorderable-list",
        states =
            listOf(
                state("long-press-to-lift"),
                state("drag-handles", handles = true),
                state("pressed-row", press = CapturePress()),
                state("lifted-row", press = CapturePress(gesture = CaptureGesture.LongPressHold)),
                state("swiped-open", press = CapturePress(fromEnd = 24.dp, gesture = CaptureGesture.SwipeOpen)),
                state("reorder-off", reorder = false),
                state("context-action-off", contextAction = false),
            ),
    )
}
