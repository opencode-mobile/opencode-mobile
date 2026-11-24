package dev.opencode.mobile.ui.componentgallery.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.componentgallery.components.ActionSheetEntries
import dev.opencode.mobile.ui.componentgallery.components.BottomFadeGallerySample
import dev.opencode.mobile.ui.componentgallery.components.BottomSheetGalleryForm
import dev.opencode.mobile.ui.componentgallery.components.BottomSheetGalleryPinnedTop
import dev.opencode.mobile.ui.componentgallery.components.KeyboardExpandBottomSheet
import dev.opencode.mobile.ui.componentgallery.components.ModalGalleryContent
import dev.opencode.mobile.ui.componentgallery.components.SplitPaneGalleryLeading
import dev.opencode.mobile.ui.componentgallery.components.SplitPaneGalleryTrailing
import dev.opencode.mobile.ui.componentgallery.components.dialogGalleryNotes
import dev.opencode.mobile.ui.componentgallery.components.fileTabsGallerySamples
import dev.opencode.mobile.ui.componentgallery.components.overflowMenuGalleryEntries
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeActionBar
import dev.opencode.mobile.ui.components.OpenCodeActionBarTarget
import dev.opencode.mobile.ui.components.OpenCodeActivityWorkingLabel
import dev.opencode.mobile.ui.components.OpenCodeAnchoredOverflowHost
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeaderAction
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeaderTextAction
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetKeyboard
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeDialogTextField
import dev.opencode.mobile.ui.components.OpenCodeFileTab
import dev.opencode.mobile.ui.components.OpenCodeFileTabs
import dev.opencode.mobile.ui.components.OpenCodeModal
import dev.opencode.mobile.ui.components.OpenCodePageIndicator
import dev.opencode.mobile.ui.components.OpenCodeSessionConnectionStatus
import dev.opencode.mobile.ui.components.OpenCodeSkeletonIllustrationTopic
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.OpenCodeSnackbarVariant
import dev.opencode.mobile.ui.components.OpenCodeSplitPane
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneState
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeTopBarOverflowHost
import dev.opencode.mobile.ui.components.OpenCodeTopBarSubtitleAccessory
import dev.opencode.mobile.ui.components.OpenCodeTopBarSubtitleLeading
import dev.opencode.mobile.ui.components.activityMonoCaption
import dev.opencode.mobile.ui.icons.OpenCodeIcons

internal fun navigationCaptures(): List<ComponentCaptureComponent> =
    listOf(
        pageIndicatorCaptures(),
        bottomFadeCaptures(),
        fileTabsCaptures(),
        bottomSheetCaptures(),
        dialogCaptures(),
        modalCaptures(),
        snackbarCaptures(),
        overflowMenuCaptures(),
        topBarCaptures(),
        actionBarCaptures(),
        splitPaneCaptures(),
    )

/** The default tile height for overlays without an HTML `cellHeight`. */
private val OverlayDefaultHeight = 480.dp

private fun pageIndicatorCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        pages: Int,
        current: Int,
    ) = ComponentCaptureState(id) { OpenCodePageIndicator(pageCount = pages, currentPage = current) }
    return ComponentCaptureComponent(
        id = "page-indicator",
        alignment = Alignment.Center,
        states = listOf(state("1-of-3", 3, 0), state("2-of-3", 3, 1), state("3-of-3", 3, 2), state("4-of-5", 5, 3)),
    )
}

private fun bottomFadeCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        height: Int,
        note: String? = null,
    ) = ComponentCaptureState(id, note = note) { BottomFadeGallerySample(fadeHeight = height.dp) }
    return ComponentCaptureComponent(
        id = "bottom-fade",
        states =
            listOf(
                state("72-gallery", 72),
                state("48", 48),
                state("120", 120),
                state("under-a-dock", 120, note = "The Android gallery has no dock sample; the 120 dp fade without a dock over it"),
            ),
    )
}

private fun fileTabsCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        paths: List<String>,
        selected: String,
        divider: Boolean = true,
    ) = ComponentCaptureState(id) {
        OpenCodeFileTabs(
            tabs = paths.map { OpenCodeFileTab(id = it, title = it.substringAfterLast('/')) },
            selectedTabId = selected,
            onSelect = {},
            onClose = {},
            showDivider = divider,
        )
    }
    val five = fileTabsGallerySamples
    return ComponentCaptureComponent(
        id = "file-tabs",
        states =
            listOf(
                state("open-files", five, five[1]),
                state("long-name-selected", five, five[4]),
                state("two-tabs", five.take(2), five[0]),
                state("single-tab-no-divider", listOf("notes.txt"), "notes.txt", divider = false),
            ),
    )
}

private const val KEYBOARD_DOWN_NOTE = "Captured with the keyboard down"

private fun bottomSheetCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        title: String = "Session actions",
        height: OpenCodeBottomSheetHeight = OpenCodeBottomSheetHeight.FitContent,
        keyboard: OpenCodeBottomSheetKeyboard = OpenCodeBottomSheetKeyboard.Dismiss,
        items: Int = 4,
        enabled: Boolean = true,
        selected: Boolean = false,
        tag: Boolean = false,
        isNew: Boolean = false,
        subtitle: Boolean = false,
        destructive: Boolean = false,
        illustration: Boolean = false,
        headerBack: Boolean = false,
        headerEdit: Boolean = false,
        running: Boolean = false,
        pinned: Boolean = false,
        note: String? = null,
    ) = ComponentCaptureState(id, note = note) {
        OpenCodeBottomSheet(
            visible = true,
            title = if (headerBack) "Anthropic" else title,
            onDismissRequest = {},
            headerLeadingAction =
                if (headerBack) OpenCodeBottomSheetHeaderAction(OpenCodeIcons.ArrowBack, "Back to providers", onClick = {}) else null,
            headerTrailingAction = if (headerEdit) OpenCodeBottomSheetHeaderTextAction(title = "Edit", onClick = {}) else null,
            height = height,
            keyboard = keyboard,
            pinnedTop = { if (pinned) BottomSheetGalleryPinnedTop() },
            pinnedBottom =
                if (pinned) {
                    { OpenCodeButton(variant = OpenCodeButtonVariant.FullWidthText("Add project"), onClick = {}) }
                } else {
                    null
                },
            titleContent = if (running) ({ RunningSheetTitle(title) }) else null,
        ) {
            if (keyboard != OpenCodeBottomSheetKeyboard.Dismiss) {
                BottomSheetGalleryForm(formValue = "http://10.0.2.2:4096/", onFormValueChange = {})
            }
            ActionSheetEntries(
                enabled = enabled,
                selected = selected,
                tag = if (tag) "Recommended" else null,
                isNew = isNew,
                startDecorator =
                    if (illustration) {
                        BottomSheetEntryDecorator.Illustration(OpenCodeSkeletonIllustrationTopic.TimelineActivityCard)
                    } else {
                        BottomSheetEntryDecorator.Icon(OpenCodeIcons.Folder)
                    },
                endDecorator =
                    if (illustration) {
                        BottomSheetEntryDecorator.Icon(
                            OpenCodeIcons.ChevronRight,
                        )
                    } else {
                        BottomSheetEntryDecorator.None
                    },
                itemCount = items,
                onDismiss = {},
                subtitle = if (subtitle) "Opens the project the session runs in" else null,
                destructive = destructive,
            )
        }
    }
    return ComponentCaptureComponent(
        id = "bottom-sheet",
        height = 500.dp,
        overlay = CaptureOverlay.Bottom,
        states =
            listOf(
                state("fit-content"),
                state("fit-content-over-limit-rests-at-half", items = 12),
                state("half", height = OpenCodeBottomSheetHeight.Half, items = 12),
                state("fit-all-content", height = OpenCodeBottomSheetHeight.FitAllContent, items = 12),
                state("entry-states", items = 6, selected = true, tag = true, isNew = true, destructive = true),
                state("disabled-entry", items = 2, enabled = false),
                state("decorators-illustration", items = 2, subtitle = true, illustration = true),
                state("header-back-edit", headerBack = true, headerEdit = true),
                state("running-title", title = "Background work", items = 2, running = true),
                state("pinned-top-and-bottom", items = 6, pinned = true),
                state(
                    "keyboard-keep",
                    title = "Add server",
                    keyboard = OpenCodeBottomSheetKeyboard.Keep,
                    items = 2,
                    note = KEYBOARD_DOWN_NOTE,
                ),
                ComponentCaptureState("keyboard-expand", note = KEYBOARD_DOWN_NOTE) {
                    KeyboardExpandBottomSheet(visible = true, filter = "", onFilterChange = {}, onDismiss = {})
                },
            ),
    )
}

/** A live sheet title, as the activity sheet draws one: shimmering label, elapsed time at the end. */
@Composable
private fun RunningSheetTitle(title: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OpenCodeActivityWorkingLabel(text = title, shimmer = true, style = MaterialTheme.typography.titleMedium)
        Box(modifier = Modifier.weight(1f))
        Text(text = "0:42", style = activityMonoCaption(), color = openCodeColor("text-weak"), maxLines = 1)
    }
}

private fun dialogCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        title: String,
        subtitle: String?,
        primary: String,
        destructive: Boolean = true,
        enabled: Boolean = true,
        field: Boolean = false,
        notes: Boolean = false,
    ) = ComponentCaptureState(id) {
        OpenCodeDialog(
            visible = true,
            title = title,
            subtitle = subtitle,
            primaryOption = OpenCodeDialogOption(title = primary, enabled = enabled, destructive = destructive && !field, onClick = {}),
            secondaryOption = OpenCodeDialogOption(title = "Cancel", enabled = enabled, onClick = {}),
            textField = if (field) OpenCodeDialogTextField(hint = "Title", value = "Importing icons", onValueChange = {}) else null,
            notes = if (notes) dialogGalleryNotes else emptyList(),
            onDismissRequest = {},
        )
    }
    return ComponentCaptureComponent(
        id = "dialog",
        height = 390.dp,
        overlay = CaptureOverlay.Center,
        states =
            listOf(
                state("confirmation-destructive", "Clear all data?", "This cannot be undone.", "Clear"),
                state("confirmation-neutral", "Fork session?", "Creates a copy from this message.", "Fork", destructive = false),
                state("without-subtitle", "Clear all data?", null, "Clear"),
                state("options-disabled", "Clear all data?", "This cannot be undone.", "Clear", enabled = false),
                state("form", "Rename session", "Choose a short title for this session.", "Save", field = true),
                state("notes", "Remove worktree?", "Deletes the folder on the server.", "Remove anyway", notes = true),
            ),
    )
}

private fun modalCaptures() =
    ComponentCaptureComponent(
        id = "modal",
        height = 470.dp,
        overlay = CaptureOverlay.Center,
        states =
            listOf(
                ComponentCaptureState("promo", note = "The Android gallery modal has no body line under the title") {
                    OpenCodeModal(visible = true, onDismissRequest = {}) { ModalGalleryContent(onDismiss = {}) }
                },
            ),
    )

private fun snackbarCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        message: String,
        variant: OpenCodeSnackbarVariant = OpenCodeSnackbarVariant.Default,
        action: String? = null,
    ) = ComponentCaptureState(id) {
        val shown = remember { OpenCodeSnackbarMessage(text = message, variant = variant, actionLabel = action) }
        Box(Modifier.fillMaxSize()) {
            OpenCodeSnackbarHost(message = shown, onDismissed = {}, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
    return ComponentCaptureComponent(
        id = "snackbar",
        height = OverlayDefaultHeight,
        states =
            listOf(
                state("default", "Session compacted."),
                state("error", "Couldn't compact session context.", OpenCodeSnackbarVariant.Error),
                state("action", "/btw failed: “What did we change so far?”", OpenCodeSnackbarVariant.Error, "Retry"),
                state("default-with-action", "Session archived.", action = "Undo"),
                state("long-message", "The server closed the connection while this session was still syncing its latest messages."),
            ),
    )
}

private fun overflowMenuCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        count: Int,
        enabled: Boolean = true,
        selection: Boolean = false,
        destructive: Boolean = false,
        divider: Boolean = false,
        reason: Boolean = false,
        textOnly: Boolean = false,
        anchored: Boolean = false,
        open: Boolean = true,
    ) = ComponentCaptureState(id) {
        val entries =
            overflowMenuGalleryEntries(
                enabled = enabled,
                selectionRows = selection,
                destructive = destructive,
                divider = divider,
                itemCount = count,
                onClick = {},
                reason = reason,
                textOnly = textOnly,
            )
        if (anchored) {
            OpenCodeAnchoredOverflowHost(expanded = open, onDismissRequest = {}, entries = entries) {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Add, "Add attachment"),
                    contrast = OpenCodeButtonContrast.Low,
                    onClick = {},
                )
            }
        } else {
            OpenCodeTopBarOverflowHost(expanded = open, onDismissRequest = {}, entries = entries) {
                OpenCodeTopBar(
                    title = "Session",
                    leading = OpenCodeTopBarLeading.Back(onClick = {}),
                    actions = listOf(OpenCodeTopBarAction.Icon(OpenCodeIcons.MoreVert, "More options", onClick = {})),
                    showDivider = true,
                )
            }
        }
    }
    return ComponentCaptureComponent(
        id = "overflow-menu",
        height = 420.dp,
        overlay = CaptureOverlay.Top,
        states =
            listOf(
                state("default", 4, destructive = true),
                state("disabled-entries", 4, enabled = false, destructive = true),
                state("selection-rows", 4, selection = true),
                state("subtitle-and-divider", 3, destructive = true, divider = true, reason = true),
                state("text-only", 3, textOnly = true),
                state("anchored-to-a-control", 3, anchored = true),
                state("closed", 4, destructive = true, open = false),
            ),
    )
}

private fun topBarCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        title: String,
        subtitle: String? = null,
        letter: Boolean = false,
        live: Boolean = false,
        back: Boolean = true,
        actions: Boolean = true,
        actionsEnabled: Boolean = true,
        divider: Boolean = false,
        switcher: Boolean = false,
        note: String? = null,
    ) = ComponentCaptureState(id, note = note) {
        OpenCodeTopBar(
            title = title,
            subtitle = subtitle,
            subtitleLeading = if (letter) OpenCodeTopBarSubtitleLeading.Letter('O', LetterAccent.Blue) else null,
            subtitleAccessory = if (live) OpenCodeTopBarSubtitleAccessory.StatusDot(OpenCodeSessionConnectionStatus.Live) else null,
            leading = if (back) OpenCodeTopBarLeading.Back(onClick = {}) else OpenCodeTopBarLeading.None,
            actions =
                if (actions) {
                    listOf(
                        OpenCodeTopBarAction.Icon(OpenCodeIcons.Search, "Search", enabled = actionsEnabled, onClick = {}),
                        OpenCodeTopBarAction.Icon(OpenCodeIcons.MoreVert, "More actions", enabled = actionsEnabled, onClick = {}),
                    )
                } else {
                    emptyList()
                },
            showDivider = divider,
            onTitleClick = if (switcher) ({}) else null,
        )
    }
    return ComponentCaptureComponent(
        id = "top-bar",
        states =
            listOf(
                state("back-actions", "Session"),
                state("title-only", "Component Gallery", back = false, actions = false),
                state("status-subtitle", "Importing icons explanation", live = true, divider = true),
                state("project-status", "Wire session project chrome", "opencode-mobile", letter = true, live = true, divider = true),
                state("disabled-actions", "Session", actionsEnabled = false),
                state("title-switcher", "My workstation", live = true, back = false, switcher = true),
            ),
    )
}

private fun actionBarCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        name: String = "opencode-mobile",
        tag: String? = "git",
        path: String = "/Users/example/Projects/opencode-mobile",
        action: String = "Add this folder",
        target: Boolean = true,
        enabled: Boolean = true,
        compact: Boolean = false,
    ) = ComponentCaptureState(id) {
        OpenCodeActionBar(
            actionTitle = action,
            onAction = {},
            target = if (target) OpenCodeActionBarTarget(title = name, subtitle = path, tag = tag) else null,
            enabled = enabled,
            compact = compact,
        )
    }
    val longName = "a-very-long-folder-name-that-does-not-fit-on-one-line"
    return ComponentCaptureComponent(
        id = "action-bar",
        states =
            listOf(
                state("enabled"),
                state(
                    "disabled-reason",
                    name = "notes",
                    tag = null,
                    path = "/Users/example/notes",
                    action = "Already on Home",
                    enabled = false,
                ),
                state("compact-keyboard-up", compact = true),
                state("no-target", target = false),
                state("long-name-and-path", name = longName, path = "/Users/example/Projects/clients/sample/monorepo/packages/$longName"),
            ),
    )
}

private fun splitPaneCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        width: Int = 360,
        collapsed: Boolean = false,
        press: CapturePress? = null,
    ) = ComponentCaptureState(id, press = press, note = "A phone-width tile; the split pane is sized for tablets") {
        OpenCodeSplitPane(
            state = OpenCodeSplitPaneState(leadingWidth = width.dp, isCollapsed = collapsed),
            onStateChange = {},
            modifier = Modifier.fillMaxSize().testTag(CAPTURE_PRESS_TAG),
            leading = { SplitPaneGalleryLeading() },
            trailing = { control -> SplitPaneGalleryTrailing(control) },
        )
    }
    return ComponentCaptureComponent(
        id = "split-pane",
        height = OverlayDefaultHeight,
        states =
            listOf(
                state("default-360"),
                state("minimum-280", width = 280),
                state("maximum-480", width = 480),
                state("dragging", press = CapturePress(fromStart = 360.dp)),
                state("collapsed", collapsed = true),
            ),
    )
}
