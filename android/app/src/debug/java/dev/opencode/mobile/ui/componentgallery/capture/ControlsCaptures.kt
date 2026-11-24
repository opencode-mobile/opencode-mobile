package dev.opencode.mobile.ui.componentgallery.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.LocalOpenCodeDarkTheme
import dev.opencode.mobile.theme.ThemeManager
import dev.opencode.mobile.ui.componentgallery.components.composerSuggestionGalleryCommands
import dev.opencode.mobile.ui.componentgallery.components.composerSuggestionGalleryMentions
import dev.opencode.mobile.ui.componentgallery.components.fieldHint
import dev.opencode.mobile.ui.componentgallery.components.floatingPillGalleryLayouts
import dev.opencode.mobile.ui.componentgallery.components.sampleValue
import dev.opencode.mobile.ui.components.CodeColors
import dev.opencode.mobile.ui.components.OpenCodeActionPill
import dev.opencode.mobile.ui.components.OpenCodeActionPillVariant
import dev.opencode.mobile.ui.components.OpenCodeActionTile
import dev.opencode.mobile.ui.components.OpenCodeActionTileBadge
import dev.opencode.mobile.ui.components.OpenCodeActionTileBadgeTone
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonSize
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadgeVariant
import dev.opencode.mobile.ui.components.OpenCodeCodeSnippet
import dev.opencode.mobile.ui.components.OpenCodeCodeSnippetSurface
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionOverlay
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeEmptyStateAction
import dev.opencode.mobile.ui.components.OpenCodeFilterTab
import dev.opencode.mobile.ui.components.OpenCodeFilterTabs
import dev.opencode.mobile.ui.components.OpenCodeFilterTabsStyle
import dev.opencode.mobile.ui.components.OpenCodeFloatingPill
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillLayout
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillState
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeAction
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeTone
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeVariant
import dev.opencode.mobile.ui.components.OpenCodeKeyboardHint
import dev.opencode.mobile.ui.components.OpenCodeKeyboardHintTone
import dev.opencode.mobile.ui.components.OpenCodeMenuChip
import dev.opencode.mobile.ui.components.OpenCodeMenuChipLeading
import dev.opencode.mobile.ui.components.OpenCodeMenuChipVariant
import dev.opencode.mobile.ui.components.OpenCodeServerAddressInput
import dev.opencode.mobile.ui.components.OpenCodeSplitButton
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeStatusStackReveal
import dev.opencode.mobile.ui.components.OpenCodeStatusStackStyle
import dev.opencode.mobile.ui.components.OpenCodeTag
import dev.opencode.mobile.ui.components.OpenCodeTagSize
import dev.opencode.mobile.ui.components.OpenCodeTagTone
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.components.OpenCodeTextSizeSlider
import dev.opencode.mobile.ui.components.OpenCodeTurnStatusPill
import dev.opencode.mobile.ui.components.OpenCodeTurnStatusPillPhase
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.settings.syntaxSnippet

internal fun controlsCaptures(): List<ComponentCaptureComponent> =
    listOf(
        tagCaptures(),
        menuChipCaptures(),
        actionPillCaptures(),
        actionTileCaptures(),
        floatingPillCaptures(),
        composerSuggestionOverlayCaptures(),
        buttonCaptures(),
        splitButtonCaptures(),
        choiceBadgeCaptures(),
        filterTabsCaptures(),
        textSizeSliderCaptures(),
        textInputCaptures(),
        serverAddressInputCaptures(),
        emptyStateCaptures(),
        statusStackCaptures(),
        inlineNoticeCaptures(),
        keyboardHintCaptures(),
        codeSnippetCaptures(),
    )

private const val ANDROID_HAS_TWO_TAG_TONES = "Android tags have Neutral and Accent tones only; Warning and Success are iOS only"

private fun tagCaptures() =
    ComponentCaptureComponent(
        id = "tag",
        states =
            listOf(
                ComponentCaptureState("regular") { OpenCodeTag(title = "Unofficial") },
                ComponentCaptureState("small") { OpenCodeTag(title = "git", size = OpenCodeTagSize.Small) },
                ComponentCaptureState("accent") { OpenCodeTag(title = "Recommended", tone = OpenCodeTagTone.Accent) },
                ComponentCaptureState("small-accent") {
                    OpenCodeTag(title = "Recommended", size = OpenCodeTagSize.Small, tone = OpenCodeTagTone.Accent)
                },
            ),
        skipped = listOf(ComponentCaptureSkip("paused", ANDROID_HAS_TWO_TAG_TONES), ComponentCaptureSkip("met", ANDROID_HAS_TWO_TAG_TONES)),
    )

private fun menuChipCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        title: String,
        leading: OpenCodeMenuChipLeading?,
        selected: Boolean = false,
        enabled: Boolean = true,
        variant: OpenCodeMenuChipVariant = OpenCodeMenuChipVariant.Menu,
    ) = ComponentCaptureState(id) {
        OpenCodeMenuChip(title = title, onClick = {}, leading = leading, selected = selected, enabled = enabled, variant = variant)
    }
    val widgets = OpenCodeMenuChipLeading.Icon(OpenCodeIcons.Widgets)
    val archive = OpenCodeMenuChipLeading.Icon(OpenCodeIcons.Archive)
    return ComponentCaptureComponent(
        id = "menu-chip",
        states =
            listOf(
                state("default", "All projects", widgets),
                state("selected-choice", "acme-invoices", OpenCodeMenuChipLeading.Letter('A', LetterAccent.Teal), selected = true),
                state("no-leading", "No leading", null),
                state("disabled", "Disabled", widgets, enabled = false),
                state("toggle-off", "Include archived · 3", archive, variant = OpenCodeMenuChipVariant.Toggle),
                state("toggle-on", "Include archived · 3", archive, selected = true, variant = OpenCodeMenuChipVariant.Toggle),
            ),
    )
}

private const val EXPAND_PILL_IS_IOS = "Expand Pill exists only on iOS"

private fun actionPillCaptures() =
    ComponentCaptureComponent(
        id = "action-pill",
        alignment = Alignment.Center,
        states =
            listOf(
                ComponentCaptureState("enabled") { OpenCodeActionPill(variant = OpenCodeActionPillVariant.ScrollToEnd, onClick = {}) },
                ComponentCaptureState("pressed", press = CapturePress()) {
                    OpenCodeActionPill(variant = OpenCodeActionPillVariant.ScrollToEnd, onClick = {})
                },
                ComponentCaptureState("disabled") {
                    OpenCodeActionPill(variant = OpenCodeActionPillVariant.ScrollToEnd, onClick = {}, enabled = false)
                },
            ),
        skipped =
            listOf("expand-collapsed", "expand-open", "expand-disabled").map { ComponentCaptureSkip(it, EXPAND_PILL_IS_IOS) },
    )

private fun actionTileCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        badge: OpenCodeActionTileBadge? = null,
        enabled: Boolean = true,
        pressed: Boolean = false,
    ) = ComponentCaptureState(id, press = if (pressed) CapturePress() else null) {
        // One tile of the two-column palette the tile is built for.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val plug = badge != null
            OpenCodeActionTile(
                icon = if (plug) OpenCodeIcons.Plug else OpenCodeIcons.Add,
                label = if (plug) "MCP servers" else "New",
                onClick = {},
                enabled = enabled,
                badge = badge,
                modifier = Modifier.weight(1f).testTag(CAPTURE_PRESS_TAG),
            )
            Spacer(Modifier.weight(1f))
        }
    }
    return ComponentCaptureComponent(
        id = "action-tile",
        states =
            listOf(
                state("enabled"),
                state("pressed", pressed = true),
                state("disabled", enabled = false),
                state("success-dot", OpenCodeActionTileBadge(OpenCodeActionTileBadgeTone.Success)),
                state("warning-dot", OpenCodeActionTileBadge(OpenCodeActionTileBadgeTone.Warning)),
                state("error-count", OpenCodeActionTileBadge(OpenCodeActionTileBadgeTone.Error, count = 2)),
                state("neutral-count-capped", OpenCodeActionTileBadge(OpenCodeActionTileBadgeTone.Neutral, count = 120)),
            ),
    )
}

private fun floatingPillCaptures(): ComponentCaptureComponent {
    val iconLabel = floatingPillGalleryLayouts.first { it.second is OpenCodeFloatingPillLayout.IconLabel }.second
    val closable = floatingPillGalleryLayouts.first { it.second is OpenCodeFloatingPillLayout.Closable }.second

    fun state(
        id: String,
        pillState: OpenCodeFloatingPillState,
        layout: OpenCodeFloatingPillLayout = iconLabel,
        compact: Boolean = false,
    ) = ComponentCaptureState(id) {
        OpenCodeFloatingPill(
            layout = layout,
            state = pillState,
            compact = compact,
            onClick = {},
            onClose = if (layout is OpenCodeFloatingPillLayout.Closable) ({}) else null,
        )
    }
    return ComponentCaptureComponent(
        id = "floating-pill",
        alignment = Alignment.Center,
        states =
            listOf(
                state("live", OpenCodeFloatingPillState.Live),
                state("ready", OpenCodeFloatingPillState.Ready),
                state("attention", OpenCodeFloatingPillState.NeedsAttention),
                state("error", OpenCodeFloatingPillState.Error),
                state("done", OpenCodeFloatingPillState.Done),
                state("compact", OpenCodeFloatingPillState.Live, compact = true),
                state("closable", OpenCodeFloatingPillState.Error, layout = closable),
                ComponentCaptureState("turn-working") { OpenCodeTurnStatusPill(phase = OpenCodeTurnStatusPillPhase.Working("Working")) },
                ComponentCaptureState("turn-done") { OpenCodeTurnStatusPill(phase = OpenCodeTurnStatusPillPhase.Done) },
            ),
        skipped = listOf(ComponentCaptureSkip("summary", "The summary layout exists only on iOS")),
    )
}

private fun composerSuggestionOverlayCaptures() =
    ComponentCaptureComponent(
        id = "composer-suggestion-overlay",
        states =
            listOf(
                ComponentCaptureState("populated-scrolls") {
                    OpenCodeComposerSuggestionOverlay(items = composerSuggestionGalleryCommands, onSelect = {})
                },
                ComponentCaptureState("two-rows") {
                    OpenCodeComposerSuggestionOverlay(items = composerSuggestionGalleryCommands.take(2), onSelect = {})
                },
                ComponentCaptureState("mentions") {
                    OpenCodeComposerSuggestionOverlay(items = composerSuggestionGalleryMentions, onSelect = {})
                },
                ComponentCaptureState("empty") {
                    OpenCodeComposerSuggestionOverlay(items = emptyList(), onSelect = {}, emptyMessage = "No commands")
                },
            ),
    )

private fun buttonCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        variant: OpenCodeButtonVariant = OpenCodeButtonVariant.CompactText("Build"),
        contrast: OpenCodeButtonContrast = OpenCodeButtonContrast.High,
        size: OpenCodeButtonSize = OpenCodeButtonSize.Medium,
        enabled: Boolean = true,
        pressed: Boolean = false,
        focused: Boolean = false,
    ) = ComponentCaptureState(id = id, press = if (pressed) CapturePress() else null) {
        OpenCodeButton(
            variant = variant,
            onClick = {},
            enabled = enabled,
            contrast = contrast,
            size = size,
            modifier = if (focused) rememberKeyboardFocus() else Modifier,
        )
    }
    return ComponentCaptureComponent(
        id = "button",
        alignment = Alignment.Center,
        states =
            listOf(
                state("high"),
                state("low", contrast = OpenCodeButtonContrast.Low),
                state("accent", contrast = OpenCodeButtonContrast.Accent),
                state("destructive", variant = OpenCodeButtonVariant.CompactText("Delete"), contrast = OpenCodeButtonContrast.Destructive),
                state("pressed", pressed = true),
                state("focused", focused = true),
                state("disabled", enabled = false),
                state("accent-disabled", contrast = OpenCodeButtonContrast.Accent, enabled = false),
                state("full-width", variant = OpenCodeButtonVariant.FullWidthText("Run")),
                state("large", size = OpenCodeButtonSize.Large),
                state("link", variant = OpenCodeButtonVariant.Link("Need the server first?")),
                state(
                    "icon-circle",
                    variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ArrowUpward, "Send"),
                    contrast = OpenCodeButtonContrast.Accent,
                ),
                state(
                    "icon-square",
                    variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.ArrowUpward, "Send"),
                    contrast = OpenCodeButtonContrast.Low,
                ),
                state(
                    "glyph",
                    variant = OpenCodeButtonVariant.IconCircleText("/", "Open commands"),
                    contrast = OpenCodeButtonContrast.Low,
                ),
            ),
        skipped = listOf(ComponentCaptureSkip("subtle", "Android has no Subtle contrast; iOS only")),
    )
}

private fun splitButtonCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        contrast: OpenCodeButtonContrast,
        title: String,
        enabled: Boolean = true,
        press: CapturePress? = null,
    ) = ComponentCaptureState(id, press = press) {
        Box(Modifier.testTag(CAPTURE_PRESS_TAG)) {
            OpenCodeSplitButton(
                title = title,
                menuContentDescription = "More options",
                onClick = {},
                onMenu = {},
                enabled = enabled,
                contrast = contrast,
            )
        }
    }
    return ComponentCaptureComponent(
        id = "split-button",
        alignment = Alignment.Center,
        states =
            listOf(
                state("low", OpenCodeButtonContrast.Low, "Deny"),
                state("high", OpenCodeButtonContrast.High, "Run"),
                state("disabled", OpenCodeButtonContrast.Low, "Deny", enabled = false),
                state("high-disabled", OpenCodeButtonContrast.High, "Run", enabled = false),
                state("label-pressed", OpenCodeButtonContrast.Low, "Deny", press = CapturePress(fromStart = 16.dp)),
                state("menu-pressed", OpenCodeButtonContrast.Low, "Deny", press = CapturePress(fromEnd = 12.dp)),
            ),
    )
}

private fun choiceBadgeCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        variant: OpenCodeChoiceBadgeVariant,
        selected: Boolean = false,
        enabled: Boolean = true,
        pressed: Boolean = false,
    ) = ComponentCaptureState(id, press = if (pressed) CapturePress() else null) {
        OpenCodeChoiceBadge(label = "Build", selected = selected, enabled = enabled, onClick = {}, variant = variant)
    }
    val standard = OpenCodeChoiceBadgeVariant.Standard
    val glass = OpenCodeChoiceBadgeVariant.Glass
    return ComponentCaptureComponent(
        id = "choice-badge",
        alignment = Alignment.Center,
        states =
            listOf(
                state("standard", standard),
                state("standard-selected", standard, selected = true),
                state("standard-disabled", standard, enabled = false),
                state("glass", glass),
                state("glass-selected", glass, selected = true),
                state("glass-pressed", glass, pressed = true),
                state("glass-disabled", glass, enabled = false),
            ),
    )
}

private fun filterTabsCaptures(): ComponentCaptureComponent {
    fun scrolling(
        id: String,
        selected: String = "all",
        counts: Boolean = true,
        unseen: Boolean = true,
    ) = ComponentCaptureState(id) {
        OpenCodeFilterTabs(
            tabs =
                listOf(
                    OpenCodeFilterTab(id = "all", title = "All", count = 5.takeIf { counts }),
                    OpenCodeFilterTab(id = "friends", title = "Friends"),
                    OpenCodeFilterTab(id = "unread", title = "Unread", count = 45.takeIf { counts }),
                    OpenCodeFilterTab(id = "news", title = "News", count = 10.takeIf { counts }, unseen = unseen),
                    OpenCodeFilterTab(id = "mobile", title = "opencode-mobile", count = 3.takeIf { counts }),
                ),
            selectedTabId = selected,
            onTabSelected = {},
        )
    }

    fun segmented(
        id: String,
        selected: String = "running",
        counts: Boolean = true,
        unseen: Boolean = true,
    ) = ComponentCaptureState(id) {
        OpenCodeFilterTabs(
            tabs =
                listOf(
                    OpenCodeFilterTab(id = "running", title = "Running", count = 2.takeIf { counts }),
                    OpenCodeFilterTab(id = "done", title = "Done", count = 5.takeIf { counts }, unseen = unseen),
                ),
            selectedTabId = selected,
            onTabSelected = {},
            style = OpenCodeFilterTabsStyle.Segmented,
        )
    }
    return ComponentCaptureComponent(
        id = "filter-tabs",
        states =
            listOf(
                scrolling("scrolling"),
                scrolling("other-tab-selected", selected = "unread"),
                scrolling("no-counts", counts = false, unseen = false),
                segmented("segmented"),
                segmented("segmented-done", selected = "done"),
                segmented("segmented-no-counts", counts = false, unseen = false),
            ),
    )
}

private val TextSizeSteps = listOf("Smallest", "Extra small", "Small", "Default", "Large", "Extra large")

private fun textSizeSliderCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        step: String,
        enabled: Boolean = true,
    ) = ComponentCaptureState(id) {
        OpenCodeTextSizeSlider(
            title = "Size",
            stepNames = TextSizeSteps,
            selectedIndex = TextSizeSteps.indexOf(step),
            defaultIndex = TextSizeSteps.indexOf("Default"),
            onSelectedIndexChange = {},
            enabled = enabled,
        )
    }
    return ComponentCaptureComponent(
        id = "text-size-slider",
        states =
            listOf(
                state("smallest", "Smallest"),
                state("extra-small", "Extra small"),
                state("small", "Small"),
                state("default", "Default"),
                state("large", "Large"),
                state("extra-large", "Extra large"),
                state("disabled", "Extra large", enabled = false),
            ),
    )
}

/**
 * Real keyboard focus, as Tab gives it with a hardware keyboard: switches the window to keyboard
 * input mode so the control is focusable, then focuses it. Touch mode comes back with the tile.
 */
@Composable
private fun rememberKeyboardFocus(): Modifier {
    val focus = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    DisposableEffect(Unit) {
        inputModeManager.requestInputMode(InputMode.Keyboard)
        focus.requestFocus()
        onDispose { inputModeManager.requestInputMode(InputMode.Touch) }
    }
    return Modifier.focusRequester(focus)
}

/** Focuses the field on first composition, as a tap would. */
@Composable
private fun rememberCaptureFocus(): Modifier {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    return Modifier.focusRequester(focus)
}

private fun textInputCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        kind: OpenCodeTextInputKind = OpenCodeTextInputKind.Url,
        value: String = kind.sampleValue,
        errorMessage: String? = null,
        focused: Boolean = false,
        enabled: Boolean = true,
        busy: Boolean = false,
        multiline: Boolean = false,
        maxLength: Int? = null,
    ) = ComponentCaptureState(id) {
        OpenCodeTextInput(
            value = value,
            onValueChange = {},
            hint = kind.fieldHint,
            kind = kind,
            enabled = enabled,
            errorMessage = errorMessage,
            minLines = if (multiline) 4 else 1,
            maxLines = if (multiline) 8 else 1,
            maxLength = maxLength,
            busy = busy,
            modifier = if (focused) rememberCaptureFocus() else Modifier,
        )
    }
    return ComponentCaptureComponent(
        id = "text-input",
        states =
            listOf(
                state("empty", value = ""),
                state("populated"),
                state("focused", value = "", focused = true),
                state("error", errorMessage = "Enter a valid server url"),
                state("disabled", enabled = false),
                state("search-typed", kind = OpenCodeTextInputKind.Search),
                state("search-busy", kind = OpenCodeTextInputKind.Search, busy = true),
                state("multi-line", kind = OpenCodeTextInputKind.Text, multiline = true),
                state(
                    "near-limit",
                    kind = OpenCodeTextInputKind.Text,
                    value = "Summarize the release notes for this ",
                    multiline = true,
                    maxLength = 40,
                ),
            ),
    )
}

private fun serverAddressInputCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        value: String = "http://127.0.0.1:4096/",
        errorMessage: String? = null,
        focused: Boolean = false,
        enabled: Boolean = true,
    ) = ComponentCaptureState(id) {
        OpenCodeServerAddressInput(
            value = value,
            onValueChange = {},
            enabled = enabled,
            errorMessage = errorMessage,
            modifier = if (focused) rememberCaptureFocus() else Modifier,
        )
    }
    return ComponentCaptureComponent(
        id = "server-address-input",
        states =
            listOf(
                state("http"),
                state("https-hostname", value = "https://example.com:8080/"),
                state("empty", value = "http://"),
                state("focused", focused = true),
                state("error", errorMessage = "Enter a valid host and port"),
                state("disabled", enabled = false),
            ),
    )
}

private fun emptyStateCaptures(): ComponentCaptureComponent {
    val noProjects = "No projects yet"
    val noProjectsMessage = "Tap the Add project icon below to choose a folder on your server."
    val retry = OpenCodeEmptyStateAction(title = "Retry", onClick = {})
    return ComponentCaptureComponent(
        id = "empty-state",
        height = 270.dp,
        states =
            listOf(
                ComponentCaptureState("with-icon") {
                    OpenCodeEmptyState(noProjects, noProjectsMessage, Modifier.fillMaxSize(), icon = OpenCodeIcons.CreateNewFolder)
                },
                ComponentCaptureState("no-icon") { OpenCodeEmptyState(noProjects, noProjectsMessage, Modifier.fillMaxSize()) },
                ComponentCaptureState("two-actions") {
                    OpenCodeEmptyState(
                        "Unable to connect",
                        "The server did not answer.",
                        Modifier.fillMaxSize(),
                        primaryAction = retry,
                        secondaryAction = OpenCodeEmptyStateAction(title = "Edit connection", onClick = {}),
                    )
                },
                ComponentCaptureState("one-action") {
                    OpenCodeEmptyState("Unable to connect", "The server did not answer.", Modifier.fillMaxSize(), primaryAction = retry)
                },
                ComponentCaptureState("hugs-content-sheet") {
                    OpenCodeEmptyState(noProjects, noProjectsMessage, icon = OpenCodeIcons.CreateNewFolder)
                },
            ),
    )
}

private fun statusStackCaptures() =
    ComponentCaptureComponent(
        id = "status-stack",
        height = 170.dp,
        states =
            listOf(
                ComponentCaptureState("home-copy") {
                    OpenCodeStatusStack(title = "Loading sessions", subtitle = "Checking server…", modifier = Modifier.fillMaxSize())
                },
                ComponentCaptureState("session-copy") {
                    OpenCodeStatusStack(title = "Loading messages", subtitle = "Fetching timeline…", modifier = Modifier.fillMaxSize())
                },
                ComponentCaptureState("spinner") {
                    OpenCodeStatusStack(title = "Loading", style = OpenCodeStatusStackStyle.Spinner, modifier = Modifier.fillMaxSize())
                },
                ComponentCaptureState("after-300ms-grace", note = "Captured after the 300 ms grace, once the stack has appeared") {
                    OpenCodeStatusStack(
                        title = "Loading sessions",
                        subtitle = "Checking server…",
                        reveal = OpenCodeStatusStackReveal.AfterGrace,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            ),
    )

private fun inlineNoticeCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        message: String,
        tone: OpenCodeInlineNoticeTone,
        variant: OpenCodeInlineNoticeVariant,
        action: Boolean = false,
    ) = ComponentCaptureState(id) {
        OpenCodeInlineNotice(
            message = message,
            tone = tone,
            variant = variant,
            action = if (action) OpenCodeInlineNoticeAction(title = "Retry", onClick = {}) else null,
        )
    }
    val neutral = OpenCodeInlineNoticeTone.Neutral
    val error = OpenCodeInlineNoticeTone.Error
    val plain = OpenCodeInlineNoticeVariant.Plain
    val banner = OpenCodeInlineNoticeVariant.Banner
    return ComponentCaptureComponent(
        id = "inline-notice",
        states =
            listOf(
                state("plain", "Selected model is unavailable.", neutral, plain),
                state("plain-error", "Couldn't prepare diagnostic log for sharing.", error, plain),
                state("banner", "Sessions stay in Recent while Running or Ready.", neutral, banner),
                state("banner-error", "12 entries dropped under writer pressure.", error, banner),
                state("plain-with-action", "Refresh failed: offline", neutral, plain, action = true),
                state("plain-error-with-action", "Refresh failed: offline", error, plain, action = true),
                state("banner-with-action", "Refresh failed: offline", neutral, banner, action = true),
                state("banner-error-with-action", "Storage error: disk full.", error, banner, action = true),
            ),
    )
}

private fun keyboardHintCaptures() =
    ComponentCaptureComponent(
        id = "keyboard-hint",
        states =
            listOf(
                ComponentCaptureState("neutral") { OpenCodeKeyboardHint(text = "Press Tab to answer with the keyboard") },
                ComponentCaptureState("warning") {
                    OpenCodeKeyboardHint(text = "Esc again to interrupt", tone = OpenCodeKeyboardHintTone.Warning)
                },
                ComponentCaptureState("long-text") {
                    OpenCodeKeyboardHint(text = "Press Tab to answer with the keyboard, or Esc to leave this question open")
                },
            ),
    )

private fun codeSnippetCaptures() =
    ComponentCaptureComponent(
        id = "code-snippet",
        states =
            listOf(
                ComponentCaptureState("raised") { OpenCodeCodeSnippet(code = "opencode serve") },
                ComponentCaptureState("base") { OpenCodeCodeSnippet(code = "opencode2 pair", surface = OpenCodeCodeSnippetSurface.Base) },
                ComponentCaptureState("syntax-colored", note = "The Appearance syntax preview sample in the active theme's syntax colors") {
                    val tokens = if (LocalOpenCodeDarkTheme.current) ThemeManager.resolvedDark else ThemeManager.resolvedLight
                    val colors = remember(tokens) { CodeColors.from(tokens.tokens) }
                    OpenCodeCodeSnippet(code = remember(colors) { syntaxSnippet(colors) }, colors = colors)
                },
                ComponentCaptureState("long-wraps") { OpenCodeCodeSnippet(code = "OPENCODE_SERVER_PASSWORD=secret opencode serve") },
            ),
    )
