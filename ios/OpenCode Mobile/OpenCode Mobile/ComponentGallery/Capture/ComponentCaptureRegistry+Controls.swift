#if DEBUG
import SwiftUI

extension ComponentCaptureRegistry {
    static var controlsEntries: [ComponentCaptureEntry] {
        [
            tagEntry, menuChipEntry, actionPillEntry, actionTileEntry, floatingPillEntry,
            composerSuggestionOverlayEntry, buttonEntry, splitButtonEntry, choiceBadgeEntry,
            filterTabsEntry, textInputEntry, serverAddressInputEntry, emptyStateEntry,
            statusStackEntry, inlineNoticeEntry, keyboardHintEntry, codeSnippetEntry,
            segmentedControlEntry, stepSliderEntry,
        ]
    }
}

// MARK: - Tag, menu chip, pills, tiles

private extension ComponentCaptureRegistry {
    static var tagEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "tag", alignment: .center, states: [
            ComponentCaptureState("regular") { OpenCodeTag("Unofficial") },
            ComponentCaptureState("small") { OpenCodeTag("git", size: .small) },
            ComponentCaptureState("accent") { OpenCodeTag("Recommended", tone: .accent) },
            ComponentCaptureState("small-accent") { OpenCodeTag("Recommended", size: .small, tone: .accent) },
            ComponentCaptureState("paused") { OpenCodeTag("Paused", size: .small, tone: .warning) },
            ComponentCaptureState("met") { OpenCodeTag("Met", size: .small, tone: .success) },
        ])
    }

    static var menuChipEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "menu-chip", alignment: .center, states: [
            ComponentCaptureState("default") {
                OpenCodeMenuChip(title: "All projects", leading: .icon(.widgets), action: {})
            },
            ComponentCaptureState(
                "selected-choice",
                note: "Letter tint comes from letterAccent(for:), as in the app, not the contract's teal"
            ) {
                OpenCodeMenuChip(
                    title: "acme-invoices",
                    leading: .letter("a", letterAccent(for: "acme-invoices")),
                    selected: true,
                    action: {}
                )
            },
            ComponentCaptureState("no-leading") { OpenCodeMenuChip(title: "No leading", action: {}) },
            ComponentCaptureState("disabled") {
                OpenCodeMenuChip(title: "Disabled", leading: .icon(.widgets), enabled: false, action: {})
            },
            ComponentCaptureState("toggle-off") {
                OpenCodeMenuChip(variant: .toggle, title: "Include archived · 3", leading: .icon(.archive), action: {})
            },
            ComponentCaptureState("toggle-on") {
                OpenCodeMenuChip(
                    variant: .toggle, title: "Include archived · 3", leading: .icon(.archive), selected: true, action: {}
                )
            },
        ])
    }

    static func expandPill(expanded: Bool, enabled: Bool = true) -> some View {
        OpenCodeExpandPill(
            title: "3 worktrees",
            isExpanded: expanded,
            enabled: enabled,
            accessibilityLabel: expanded ? "Hide worktrees" : "Show worktrees",
            action: {}
        )
    }

    static var actionPillEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "action-pill", alignment: .center, states: [
            ComponentCaptureState("enabled") { OpenCodeActionPill(variant: .scrollToEnd, action: {}) },
            ComponentCaptureState("pressed") {
                OpenCodeActionPill(variant: .scrollToEnd, action: {}).openCodePressedPreview()
            },
            ComponentCaptureState("disabled") { OpenCodeActionPill(variant: .scrollToEnd, enabled: false, action: {}) },
            ComponentCaptureState("expand-collapsed") { expandPill(expanded: false) },
            ComponentCaptureState("expand-open") { expandPill(expanded: true) },
            ComponentCaptureState("expand-disabled") { expandPill(expanded: false, enabled: false) },
        ])
    }

    static func actionTile(
        _ icon: OpenCodeIcon = .add,
        _ label: OpenCodeText = "New",
        enabled: Bool = true,
        badge: OpenCodeActionTileBadge? = nil
    ) -> some View {
        OpenCodeActionTile(icon: icon, label: label, enabled: enabled, badge: badge, action: {})
    }

    static var actionTileEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "action-tile", states: [
            ComponentCaptureState("enabled") { actionTile() },
            ComponentCaptureState("pressed") { actionTile().openCodePressedPreview() },
            ComponentCaptureState("disabled") { actionTile(enabled: false) },
            ComponentCaptureState("success-dot") { actionTile(.plug, "MCP servers", badge: .init(tone: .success)) },
            ComponentCaptureState("warning-dot") { actionTile(.plug, "MCP servers", badge: .init(tone: .warning)) },
            ComponentCaptureState("error-count") {
                actionTile(.plug, "MCP servers", badge: .init(tone: .error, count: 2))
            },
            ComponentCaptureState("neutral-count-capped") {
                actionTile(.plug, "MCP servers", badge: .init(tone: .neutral, count: 120))
            },
        ])
    }

    static func floatingPill(
        _ layout: OpenCodeFloatingPillLayout = .iconLabel(icon: .callSplit, label: "+128 −14"),
        state: OpenCodeFloatingPillState,
        compact: Bool = false
    ) -> some View {
        OpenCodeFloatingPill(layout: layout, state: state, compact: compact, action: {}, onClose: {})
    }

    static var floatingPillEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "floating-pill", alignment: .center, states: [
            ComponentCaptureState("live") { floatingPill(state: .live) },
            ComponentCaptureState("ready") { floatingPill(state: .ready) },
            ComponentCaptureState("attention") { floatingPill(state: .needsAttention) },
            ComponentCaptureState("error") { floatingPill(state: .error) },
            ComponentCaptureState("done") { floatingPill(state: .done) },
            ComponentCaptureState("compact") { floatingPill(state: .live, compact: true) },
            ComponentCaptureState("closable") { floatingPill(.closable(label: "Request failed"), state: .error) },
            ComponentCaptureState("summary") { floatingPill(.summary(count: 3), state: .needsAttention) },
            ComponentCaptureState("turn-working") { OpenCodeTurnStatusPill(phase: .working(label: "Working")) },
            ComponentCaptureState("turn-done") { OpenCodeTurnStatusPill(phase: .done) },
        ])
    }

    static var composerSuggestionOverlayEntry: ComponentCaptureEntry {
        let scenarios: [(String, ComposerSuggestionOverlayGalleryScenario)] = [
            ("populated-scrolls", .populated), ("two-rows", .short), ("mentions", .mentions), ("empty", .empty),
        ]
        return ComponentCaptureEntry(
            componentID: "composer-suggestion-overlay",
            states: scenarios.map { id, scenario in
                ComponentCaptureState(id) {
                    OpenCodeComposerSuggestionOverlay(items: scenario.items, emptyMessage: "No commands", onSelect: { _ in })
                }
            }
        )
    }
}

// MARK: - Button, split button, choice badge, filter tabs

private extension ComponentCaptureRegistry {
    static func button(
        _ variant: OpenCodeButtonVariant,
        contrast: OpenCodeButtonContrast = .high,
        size: OpenCodeButtonSize = .medium,
        enabled: Bool = true
    ) -> OpenCodeButton {
        OpenCodeButton(variant: variant, size: size, enabled: enabled, contrast: contrast, action: {})
    }

    static var buttonEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "button", alignment: .center, states: [
            ComponentCaptureState("high") { button(.compactText(title: "Build")) },
            ComponentCaptureState("low") { button(.compactText(title: "Build"), contrast: .low) },
            ComponentCaptureState("subtle") { button(.compactText(title: "Build"), contrast: .subtle) },
            ComponentCaptureState("accent") { button(.compactText(title: "Build"), contrast: .accent) },
            ComponentCaptureState("destructive") { button(.compactText(title: "Delete"), contrast: .destructive) },
            ComponentCaptureState("pressed") {
                button(.compactText(title: "Build")).openCodePressedPreview()
            },
            ComponentCaptureState("disabled") { button(.compactText(title: "Build"), enabled: false) },
            ComponentCaptureState("focused", delay: .milliseconds(800), keyboardFocus: true) { button(.compactText(title: "Build")) },
            ComponentCaptureState("accent-disabled") {
                button(.compactText(title: "Build"), contrast: .accent, enabled: false)
            },
            ComponentCaptureState("full-width") { button(.fullWidthText(title: "Run")) },
            ComponentCaptureState("large") { button(.compactText(title: "Build"), size: .large) },
            ComponentCaptureState("link") { button(.link(title: "Need the server first?")) },
            ComponentCaptureState("icon-circle") {
                button(.iconCircle(.arrowUpward, accessibilityLabel: "Send"), contrast: .accent)
            },
            ComponentCaptureState("icon-square") {
                button(.iconSquare(.arrowUpward, accessibilityLabel: "Send"), contrast: .low)
            },
            ComponentCaptureState("glyph") {
                button(.iconCircleText("/", accessibilityLabel: "Open commands"), contrast: .low)
            },
        ])
    }

    static func splitButton(
        _ title: OpenCodeText = "Deny",
        contrast: OpenCodeButtonContrast = .low,
        enabled: Bool = true
    ) -> some View {
        OpenCodeSplitButton(
            title: title,
            menuAccessibilityLabel: "Deny options",
            enabled: enabled,
            contrast: contrast,
            action: {},
            menuAction: {}
        )
    }

    static var splitButtonEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "split-button", alignment: .center, states: [
            ComponentCaptureState("low") { splitButton() },
            ComponentCaptureState("high") { splitButton("Run", contrast: .high) },
            ComponentCaptureState("disabled") { splitButton(enabled: false) },
            ComponentCaptureState("high-disabled") { splitButton("Run", contrast: .high, enabled: false) },
            ComponentCaptureState("label-pressed") { splitButton().openCodePressedPreview(.part("label")) },
            ComponentCaptureState("menu-pressed") { splitButton().openCodePressedPreview(.part("menu")) },
        ])
    }

    static func badge(
        _ variant: OpenCodeChoiceBadgeVariant = .standard,
        selected: Bool = false,
        enabled: Bool = true
    ) -> some View {
        OpenCodeChoiceBadge(label: "Build", selected: selected, enabled: enabled, variant: variant, action: {})
    }

    static var choiceBadgeEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "choice-badge", alignment: .center, states: [
            ComponentCaptureState("standard") { badge() },
            ComponentCaptureState("standard-selected") { badge(selected: true) },
            ComponentCaptureState("standard-disabled") { badge(enabled: false) },
            ComponentCaptureState("glass") { badge(.glass) },
            ComponentCaptureState("glass-selected") { badge(.glass, selected: true) },
            ComponentCaptureState("glass-pressed") { badge(.glass).openCodePressedPreview() },
            ComponentCaptureState("glass-disabled") { badge(.glass, enabled: false) },
        ])
    }

    /// The HTML gallery's scrolling sample; its unseen dot sits on News.
    static func scrollingTabs(selected: String, counts: Bool, unseen: Bool) -> some View {
        let samples: [(id: String, title: String, count: Int?)] = [
            ("all", "All", 5),
            ("friends", "Friends", nil),
            ("unread", "Unread", 45),
            ("news", "News", 10),
            ("mobile", "opencode-mobile", 3),
        ]
        return OpenCodeFilterTabs(
            tabs: samples.map { sample in
                OpenCodeFilterTab(
                    id: sample.id,
                    title: .verbatim(sample.title),
                    count: counts ? sample.count : nil,
                    unseen: unseen && sample.id == "news"
                )
            },
            selectedTabId: selected,
            onTabSelected: { _ in }
        )
    }

    /// The HTML gallery's segmented sample; Done carries the unseen dot.
    static func segmentedTabs(selected: String, counts: Bool, unseen: Bool) -> some View {
        OpenCodeFilterTabs(
            tabs: [
                OpenCodeFilterTab(id: "running", title: "Running", count: counts ? 2 : nil),
                OpenCodeFilterTab(id: "done", title: "Done", count: counts ? 5 : nil, unseen: unseen),
            ],
            selectedTabId: selected,
            style: .segmented,
            onTabSelected: { _ in }
        )
    }

    static var filterTabsEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "filter-tabs", states: [
            ComponentCaptureState("scrolling") { scrollingTabs(selected: "all", counts: true, unseen: true) },
            ComponentCaptureState("other-tab-selected") {
                scrollingTabs(selected: "unread", counts: true, unseen: true)
            },
            ComponentCaptureState("no-counts") { scrollingTabs(selected: "all", counts: false, unseen: false) },
            ComponentCaptureState("segmented") { segmentedTabs(selected: "running", counts: true, unseen: true) },
            ComponentCaptureState("segmented-done") {
                segmentedTabs(selected: "done", counts: true, unseen: true)
            },
            ComponentCaptureState("segmented-no-counts") {
                segmentedTabs(selected: "running", counts: false, unseen: false)
            },
        ])
    }
}

// MARK: - Inputs

/// A text input with its own text binding, so a capture tile can show a fixed value.
private struct CaptureTextInput: View {
    let kind: OpenCodeTextInputKind
    @State var text: String
    var errorMessage: String?
    var enabled = true
    var focused = false
    var multiline = false
    var maxLength: Int?
    var busy = false

    var body: some View {
        OpenCodeTextInput(
            text: $text,
            hint: .verbatim(TextInputGalleryView.hint(kind)),
            kind: kind,
            enabled: enabled,
            errorMessage: .verbatim(ifPresent: errorMessage),
            isFocused: focused ? .constant(true) : nil,
            minLines: multiline ? 4 : 1,
            maxLines: multiline ? 8 : 1,
            maxLength: maxLength,
            busy: busy
        )
    }
}

private struct CaptureServerAddressInput: View {
    @State var value: String
    var errorMessage: OpenCodeText?
    var enabled = true
    var focused = false

    var body: some View {
        OpenCodeServerAddressInput(
            value: $value,
            enabled: enabled,
            errorMessage: errorMessage,
            isFocused: focused ? .constant(true) : nil
        )
    }
}

private extension ComponentCaptureRegistry {
    static var textInputEntry: ComponentCaptureEntry {
        let url = TextInputGalleryView.sampleValue(.url)
        let search = TextInputGalleryView.sampleValue(.search)
        let text = TextInputGalleryView.sampleValue(.text)
        return ComponentCaptureEntry(componentID: "text-input", states: [
            ComponentCaptureState("empty") { CaptureTextInput(kind: .url, text: "") },
            ComponentCaptureState("populated") { CaptureTextInput(kind: .url, text: url) },
            ComponentCaptureState("focused", focused: true) { CaptureTextInput(kind: .url, text: "", focused: true) },
            ComponentCaptureState("error") {
                CaptureTextInput(kind: .url, text: url, errorMessage: TextInputGalleryView.errorMessage(.url))
            },
            ComponentCaptureState("disabled") { CaptureTextInput(kind: .url, text: url, enabled: false) },
            ComponentCaptureState("search-typed") { CaptureTextInput(kind: .search, text: search) },
            ComponentCaptureState("search-busy") { CaptureTextInput(kind: .search, text: search, busy: true) },
            ComponentCaptureState("multi-line") { CaptureTextInput(kind: .text, text: text, multiline: true) },
            ComponentCaptureState("near-limit") {
                CaptureTextInput(
                    kind: .text, text: "Summarize the release notes for this ", multiline: true, maxLength: 40
                )
            },
        ])
    }

    static var serverAddressInputEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "server-address-input", states: [
            ComponentCaptureState("http") { CaptureServerAddressInput(value: "http://127.0.0.1:4096/") },
            ComponentCaptureState("https-hostname") { CaptureServerAddressInput(value: "https://example.com:8080/") },
            ComponentCaptureState("empty") { CaptureServerAddressInput(value: "http://") },
            ComponentCaptureState("focused", focused: true) {
                CaptureServerAddressInput(value: "http://127.0.0.1:4096/", focused: true)
            },
            ComponentCaptureState("error") {
                CaptureServerAddressInput(value: "http://127.0.0.1:4096/", errorMessage: "Enter a valid host and port")
            },
            ComponentCaptureState("disabled") { CaptureServerAddressInput(value: "http://127.0.0.1:4096/", enabled: false) },
        ])
    }
}

// MARK: - Feedback

private extension ComponentCaptureRegistry {
    static var emptyStateEntry: ComponentCaptureEntry {
        let projectsTitle: OpenCodeText = "No projects yet"
        let projectsMessage: OpenCodeText = "Tap the Add project icon below to choose a folder on your server."
        return ComponentCaptureEntry(componentID: "empty-state", states: [
            ComponentCaptureState("with-icon", height: 270) {
                OpenCodeEmptyState(title: projectsTitle, message: projectsMessage, icon: .createNewFolder)
            },
            ComponentCaptureState("no-icon", height: 270) {
                OpenCodeEmptyState(title: projectsTitle, message: projectsMessage)
            },
            ComponentCaptureState("two-actions", height: 270) {
                OpenCodeEmptyState(
                    title: "Unable to connect",
                    message: "The server did not answer.",
                    primaryAction: OpenCodeEmptyStateAction(title: "Retry", action: {}),
                    secondaryAction: OpenCodeEmptyStateAction(title: "Edit connection", action: {})
                )
            },
            ComponentCaptureState("one-action", height: 270) {
                OpenCodeEmptyState(
                    title: "Unable to connect",
                    message: "The server did not answer.",
                    primaryAction: OpenCodeEmptyStateAction(title: "Retry", action: {})
                )
            },
            ComponentCaptureState("hugs-content-sheet", height: 270) {
                OpenCodeEmptyState(
                    title: projectsTitle, message: projectsMessage, icon: .createNewFolder, fillsAvailableSpace: false
                )
            },
        ])
    }

    static var statusStackEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "status-stack", states: [
            ComponentCaptureState("home-copy", height: 170) {
                OpenCodeStatusStack(title: "Loading sessions", subtitle: "Checking server…")
            },
            ComponentCaptureState("session-copy", height: 170) {
                OpenCodeStatusStack(title: "Loading messages", subtitle: "Fetching timeline…")
            },
            ComponentCaptureState("spinner", height: 170) { OpenCodeStatusStack(spinner: "Loading") },
            ComponentCaptureState(
                "after-300ms-grace",
                height: 170,
                note: "Captured after the 300 ms grace, once the stack has appeared",
                delay: .milliseconds(450)
            ) {
                OpenCodeStatusStack(title: "Loading sessions", subtitle: "Checking server…", reveal: .afterGrace)
            },
        ])
    }

    static func notice(
        _ message: OpenCodeText,
        tone: OpenCodeInlineNoticeTone = .neutral,
        variant: OpenCodeInlineNoticeVariant = .plain,
        action: Bool = false
    ) -> some View {
        OpenCodeInlineNotice(
            message: message,
            tone: tone,
            variant: variant,
            action: action ? OpenCodeInlineNoticeAction(title: "Retry", action: {}) : nil
        )
    }

    static var inlineNoticeEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "inline-notice", states: [
            ComponentCaptureState("plain") { notice("Selected model is unavailable.") },
            ComponentCaptureState("plain-error") { notice("Couldn't prepare diagnostic log for sharing.", tone: .error) },
            ComponentCaptureState("banner") { notice("Sessions stay in Recent while Running or Ready.", variant: .banner) },
            ComponentCaptureState("banner-error") {
                notice("12 entries dropped under writer pressure.", tone: .error, variant: .banner)
            },
            ComponentCaptureState("plain-with-action") { notice("Refresh failed: offline", action: true) },
            ComponentCaptureState("plain-error-with-action") {
                notice("Refresh failed: offline", tone: .error, action: true)
            },
            ComponentCaptureState("banner-with-action") {
                notice("Refresh failed: offline", variant: .banner, action: true)
            },
            ComponentCaptureState("banner-error-with-action") {
                notice("Storage error: disk full.", tone: .error, variant: .banner, action: true)
            },
        ])
    }

    static var keyboardHintEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "keyboard-hint", states: [
            ComponentCaptureState("neutral") { OpenCodeKeyboardHint(OpenCodePromptKeyboardHint.text) },
            ComponentCaptureState("warning") { OpenCodeKeyboardHint(OpenCodeInterruptKeyboardHint.text, tone: .warning) },
            ComponentCaptureState("long-text") {
                OpenCodeKeyboardHint("Press Tab to answer with the keyboard, or Esc to leave this question open")
            },
        ])
    }

    static var codeSnippetEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "code-snippet", states: [
            ComponentCaptureState("raised") { OpenCodeCodeSnippet("opencode serve") },
            ComponentCaptureState("base") { OpenCodeCodeSnippet("opencode2 pair", surface: .base) },
            ComponentCaptureState(
                "syntax-colored",
                note: "Uses the app's syntax preview sample (TypeScript), not the gallery's shell sample"
            ) { CaptureSyntaxSnippet() },
            ComponentCaptureState("long-wraps") { OpenCodeCodeSnippet("OPENCODE_SERVER_PASSWORD=secret opencode serve") },
        ])
    }

    static var segmentedControlEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "segmented-control", states: [
            ComponentCaptureState("system") { SegmentedControlGalleryView(mode: .system) },
            ComponentCaptureState("light") { SegmentedControlGalleryView(mode: .light) },
            ComponentCaptureState("dark") { SegmentedControlGalleryView(mode: .dark) },
        ])
    }

    static var stepSliderEntry: ComponentCaptureEntry {
        let steps: [(String, AppTextSize)] = [
            ("smallest", .smallest), ("extra-small", .extraSmall), ("small", .small),
            ("default", .default), ("large", .large), ("extra-large", .extraLarge),
        ]
        return ComponentCaptureEntry(
            componentID: "step-slider",
            states: steps.map { id, size in
                ComponentCaptureState(id) { StepSliderGalleryView(size: size) }
            } + [
                ComponentCaptureState("no-default-mark") { StepSliderGalleryView(size: .default, marksDefault: false) },
            ]
        )
    }
}

/// The Appearance settings syntax card's snippet, in the current theme's matched syntax colours.
private struct CaptureSyntaxSnippet: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let tokens = ThemeManager.shared.syntaxTokensForPreview(preset: .match, isDark: colorScheme == .dark)
        OpenCodeCodeSnippet(syntaxSnippet(tokens), surface: .syntaxPreset(tokens))
    }
}
#endif
