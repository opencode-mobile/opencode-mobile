#if DEBUG
import SwiftUI

extension ComponentCaptureRegistry {
    static var navigationEntries: [ComponentCaptureEntry] {
        [
            pageIndicatorEntry, bottomFadeEntry, fileTabsEntry, bottomSheetEntry, dialogEntry, modalEntry,
            snackbarEntry, overflowMenuEntry, topBarEntry, actionBarEntry, splitPaneEntry,
        ]
    }
}

/// Lays a tablet-width layout out at `width` and scales it down to the tile, the way the HTML
/// gallery previews tablet-only layouts on a phone.
struct ComponentCaptureScaledToFit<Content: View>: View {
    let width: CGFloat
    let height: CGFloat
    @ViewBuilder let content: () -> Content

    var body: some View {
        GeometryReader { proxy in
            let scale = proxy.size.width / width
            content()
                .frame(width: width, height: height)
                .scaleEffect(scale, anchor: .topLeading)
                .frame(width: proxy.size.width, height: height * scale, alignment: .topLeading)
        }
        .aspectRatio(width / height, contentMode: .fit)
    }
}

// MARK: - Page indicator, bottom fade, file tabs

private extension ComponentCaptureRegistry {
    static var pageIndicatorEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "page-indicator", alignment: .center, states: [
            ComponentCaptureState("1-of-3") { OpenCodePageIndicator(pageCount: 3, currentPage: 0) },
            ComponentCaptureState("2-of-3") { OpenCodePageIndicator(pageCount: 3, currentPage: 1) },
            ComponentCaptureState("3-of-3") { OpenCodePageIndicator(pageCount: 3, currentPage: 2) },
            ComponentCaptureState("4-of-5") { OpenCodePageIndicator(pageCount: 5, currentPage: 3) },
        ])
    }

    static func bottomFade(height: CGFloat, dock: Bool = false) -> some View {
        ZStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Content under the dock")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                Text("The fade covers the last lines without taking taps.")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            OpenCodeBottomFade(height: height)
            if dock {
                OpenCodeFloatingPill(layout: .iconOnly(icon: .arrowDownward, accessibilityLabel: "Scroll to end"), state: .ready)
                    .padding(.bottom, 12)
            }
        }
        .frame(height: 160)
    }

    static var bottomFadeEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "bottom-fade", states: [
            ComponentCaptureState("72-gallery") { bottomFade(height: 72) },
            ComponentCaptureState("48") { bottomFade(height: 48) },
            ComponentCaptureState("120") { bottomFade(height: 120) },
            ComponentCaptureState(
                "under-a-dock",
                note: "A floating pill stands in for the dock above the fade"
            ) { bottomFade(height: 120, dock: true) },
        ])
    }

    static func fileTabs(_ tabs: [OpenCodeFileTab], selected: Int, divider: Bool = true) -> some View {
        OpenCodeFileTabs(
            tabs: tabs,
            selectedTabId: tabs[min(selected, tabs.count - 1)].id,
            showsDivider: divider,
            onSelect: { _ in },
            onClose: { _ in }
        )
    }

    static var fileTabsEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "file-tabs", states: [
            ComponentCaptureState("open-files") { fileTabs(fileTabsGallerySamples, selected: 1) },
            ComponentCaptureState("long-name-selected") { fileTabs(fileTabsGallerySamples, selected: 4) },
            ComponentCaptureState("two-tabs") { fileTabs(Array(fileTabsGallerySamples.prefix(2)), selected: 0) },
            ComponentCaptureState("single-tab-no-divider") {
                fileTabs([OpenCodeFileTab(id: "notes.txt", title: "notes.txt")], selected: 0, divider: false)
            },
        ])
    }
}

// MARK: - Bottom sheet, dialog, modal, snackbar

/// What an overlay sits over in its tile: a plain screen, so the scrim reads against it.
private struct CaptureOverlayBackdrop: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            OpenCodeTopBar(title: "Session", leading: .back(action: {}))
            OpenCodeListItem(title: "opencode-mobile", subtitle: "Fix composer outline fade")
            OpenCodeListItem(title: "playground", subtitle: "Try the new prompt")
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(OpenCodeColors.backgroundBase)
    }
}

private struct CaptureSheetForm: View {
    @State private var url = "http://127.0.0.1:4096/"
    @State private var password = "gallery password"

    var body: some View {
        OpenCodeTextInput(text: $url, hint: "Server URL", kind: .url)
            .openCodeBottomSheetContentInset()
        OpenCodeTextInput(text: $password, hint: "Password", kind: .password)
            .openCodeBottomSheetContentInset()
    }
}

private extension ComponentCaptureRegistry {
    static let overlayNote = "Overlay drawn inside the tile, over a stand-in screen"

    // swiftlint:disable:next function_body_length
    static var bottomSheetEntry: ComponentCaptureEntry {
        func sheet(
            title: OpenCodeText = "Session actions",
            titleState: OpenCodeBottomSheetTitleState = .plain,
            height: OpenCodeBottomSheetHeight = .fitContent,
            keyboard: OpenCodeBottomSheetKeyboard = .dismiss,
            headerBack: Bool = false,
            headerEdit: Bool = false,
            pinnedTop: Bool = false,
            pinnedBottom: Bool = false,
            @ViewBuilder content: @escaping () -> some View
        ) -> some View {
            ZStack {
                CaptureOverlayBackdrop()
                OpenCodeBottomSheetInlinePreview(
                    title: headerBack ? "Anthropic" : title,
                    titleState: titleState,
                    leadingAction: headerBack
                        ? OpenCodeBottomSheetHeaderAction(icon: .arrowBack, accessibilityLabel: "Back to providers", action: {})
                        : nil,
                    trailingAction: headerEdit ? OpenCodeBottomSheetHeaderAction(text: "Edit", action: {}) : nil,
                    height: height,
                    keyboard: keyboard,
                    pinnedTop: {
                        if pinnedTop {
                            OpenCodeBottomSheetEntry(title: "Go up", startDecorator: .icon(.arrowUpward), action: {})
                            OpenCodeBottomSheetEntry(
                                title: "/Users/example/project", startDecorator: .icon(.folderCheck), action: {}
                            )
                        }
                    },
                    sheetContent: content,
                    pinnedBottom: {
                        if pinnedBottom {
                            OpenCodeButton(variant: .fullWidthText(title: "Done"), action: {})
                        }
                    }
                )
            }
        }
        let entries = BottomSheetGalleryView.actionEntries
        let noKeyboard = "Overlay drawn inside the tile; no software keyboard is shown"
        return ComponentCaptureEntry(componentID: "bottom-sheet", states: [
            ComponentCaptureState("fit-content", height: 500, note: overlayNote) { sheet { entries(4, true, false, false, false, false, false, .icon, .none, {}) } },
            ComponentCaptureState("fit-content-over-limit-rests-at-half", height: 500, note: overlayNote) {
                sheet { entries(12, true, false, false, false, false, false, .icon, .none, {}) }
            },
            ComponentCaptureState("half", height: 500, note: overlayNote) {
                sheet(height: .half) { entries(12, true, false, false, false, false, false, .icon, .none, {}) }
            },
            ComponentCaptureState("fit-all-content", height: 500, note: overlayNote) {
                sheet(height: .fitAllContent) { entries(12, true, false, false, false, false, false, .icon, .none, {}) }
            },
            ComponentCaptureState(
                "entry-states",
                height: 500,
                note: "\(overlayNote). The native gallery puts New on the first entry"
            ) {
                sheet { entries(6, true, true, true, true, false, true, .icon, .none, {}) }
            },
            ComponentCaptureState("disabled-entry", height: 500, note: overlayNote) {
                sheet { entries(2, false, false, false, false, false, false, .icon, .none, {}) }
            },
            ComponentCaptureState("decorators-illustration", height: 500, note: overlayNote) {
                sheet { entries(2, true, false, false, false, true, false, .illustration, .icon, {}) }
            },
            ComponentCaptureState("header-back-edit", height: 500, note: overlayNote) {
                sheet(headerBack: true, headerEdit: true) { entries(4, true, false, false, false, false, false, .icon, .none, {}) }
            },
            ComponentCaptureState("running-title", height: 500, note: "\(overlayNote). Elapsed time starts 42 s before the capture") {
                sheet(
                    title: "Background work",
                    titleState: .running(startedAtMs: Int64(Date().timeIntervalSince1970 * 1000) - 42_000)
                ) { entries(2, true, false, false, false, false, false, .icon, .none, {}) }
            },
            ComponentCaptureState("pinned-top-and-bottom", height: 500, note: overlayNote) {
                sheet(pinnedTop: true, pinnedBottom: true) { entries(6, true, false, false, false, false, false, .icon, .none, {}) }
            },
            ComponentCaptureState("keyboard-keep", height: 500, note: noKeyboard) {
                sheet(title: "Add server", keyboard: .keep) { CaptureSheetForm() }
            },
            ComponentCaptureState("keyboard-expand", height: 500, note: noKeyboard) {
                sheet(title: "Choose folder", height: .half, keyboard: .expand) {
                    ForEach(1 ... 20, id: \.self) { index in
                        OpenCodeBottomSheetEntry(
                            title: .verbatim("Folder \(index)"),
                            startDecorator: .icon(.folder),
                            endDecorator: .icon(.chevronRight),
                            action: {}
                        )
                    }
                }
            },
        ])
    }

    static func dialog(
        title: OpenCodeText = "Clear all data?",
        subtitle: OpenCodeText? = "This cannot be undone.",
        primary: OpenCodeText = "Clear",
        destructive: Bool = true,
        enabled: Bool = true,
        field: Bool = false,
        notes: Bool = false
    ) -> some View {
        ZStack {
            CaptureOverlayBackdrop()
            OpenCodeDialogOverlayContent(
                card: OpenCodeDialogCard(
                    title: title,
                    subtitle: subtitle,
                    primaryOption: OpenCodeDialogOption(
                        title: primary, enabled: enabled, destructive: destructive && !field, action: {}
                    ),
                    secondaryOption: OpenCodeDialogOption(title: "Cancel", enabled: enabled, action: {}),
                    textField: field
                        ? OpenCodeDialogTextField(text: .constant("Importing icons"), hint: "Title", enabled: enabled)
                        : nil,
                    notes: notes
                        ? [
                            OpenCodeDialogNote(text: "3 uncommitted changes will be lost.", tone: .warning),
                            OpenCodeDialogNote(
                                text: "1 session uses this worktree. It stays on Home but can't run here again."
                            ),
                        ]
                        : []
                ),
                onDismiss: {}
            )
        }
    }

    static var dialogEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "dialog", states: [
            ComponentCaptureState("confirmation-destructive", height: 390, note: overlayNote) { dialog() },
            ComponentCaptureState("confirmation-neutral", height: 390, note: overlayNote) {
                dialog(title: "Fork session?", subtitle: "Creates a copy from this message.", primary: "Fork", destructive: false)
            },
            ComponentCaptureState("without-subtitle", height: 390, note: overlayNote) { dialog(subtitle: nil) },
            ComponentCaptureState("options-disabled", height: 390, note: overlayNote) { dialog(enabled: false) },
            ComponentCaptureState("form", height: 390, note: overlayNote) {
                dialog(title: "Rename session", subtitle: "Choose a short title for this session.", primary: "Save", field: true)
            },
            ComponentCaptureState("notes", height: 390, note: overlayNote) {
                dialog(
                    title: "Remove worktree?", subtitle: "Deletes the folder on the server.", primary: "Remove anyway",
                    notes: true
                )
            },
        ])
    }

    static var modalEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "modal", states: [
            ComponentCaptureState("promo", height: 470, note: overlayNote) {
                ZStack {
                    CaptureOverlayBackdrop()
                    OpenCodeDialogOverlayContent(
                        card: OpenCodeModalCard(onClose: {}) { ModalGalleryView.promoContent(onDone: {}) },
                        onDismiss: {}
                    )
                }
            },
        ])
    }

    static func snackbar(_ text: String, variant: OpenCodeSnackbarVariant = .default, action: String? = nil) -> some View {
        OpenCodeSnackbarHost(
            message: OpenCodeSnackbarMessage(text: text, variant: variant, actionLabel: action),
            onDismissed: {}
        )
        .fixedSize(horizontal: false, vertical: true)
    }

    static var snackbarEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "snackbar", states: [
            ComponentCaptureState("default") { snackbar("Session compacted.") },
            ComponentCaptureState("error") { snackbar("Couldn't compact session context.", variant: .error) },
            ComponentCaptureState("action") {
                snackbar("/btw failed: “What did we change so far?”", variant: .error, action: "Retry")
            },
            ComponentCaptureState("default-with-action") { snackbar("Session archived.", action: "Undo") },
            ComponentCaptureState("long-message") {
                snackbar("The server closed the connection while this session was still syncing its latest messages.")
            },
        ])
    }
}

// MARK: - Overflow menu, top bar, action bar, split pane

private extension ComponentCaptureRegistry {
    static func topBarMenu(_ entries: [OpenCodeOverflowMenuEntry], open: Bool = true) -> some View {
        OpenCodeTopBarOverflowHost(isPresented: .constant(open), entries: entries) {
            OpenCodeTopBar(
                title: "Session",
                leading: .back(action: {}),
                actions: [.icon(.moreVert, accessibilityLabel: "More options", action: {})],
                showDivider: true
            )
        }
        .frame(maxHeight: .infinity, alignment: .top)
    }

    static var overflowMenuEntry: ComponentCaptureEntry {
        let entries = OverflowMenuGalleryView.entries
        let note = "The menu is the real window overlay, anchored inside the tile"
        return ComponentCaptureEntry(componentID: "overflow-menu", states: [
            ComponentCaptureState("default", height: 420, note: note) { topBarMenu(entries(true, false, true, false, 4, false, false)) },
            ComponentCaptureState("disabled-entries", height: 420, note: note) {
                topBarMenu(entries(false, false, true, false, 4, false, false))
            },
            ComponentCaptureState("selection-rows", height: 420, note: note) {
                topBarMenu(entries(true, true, false, false, 4, false, false))
            },
            ComponentCaptureState("subtitle-and-divider", height: 420, note: note) {
                topBarMenu(entries(true, false, true, true, 3, true, false))
            },
            ComponentCaptureState("text-only", height: 420, note: note) {
                topBarMenu(entries(true, false, false, false, 3, false, true))
            },
            ComponentCaptureState("anchored-to-a-control", height: 420, note: note) {
                OpenCodeAnchoredOverflowHost(isPresented: .constant(true), entries: entries(true, false, false, false, 3, false, false)) {
                    OpenCodeButton(
                        variant: .iconCircle(.add, accessibilityLabel: "Add attachment"), contrast: .low, action: {}
                    )
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            },
            ComponentCaptureState("closed", height: 420) { topBarMenu(entries(true, false, true, false, 4, false, false), open: false) },
        ])
    }

    static func topBarSearchActions(enabled: Bool = true) -> [OpenCodeTopBarAction] {
        [
            .icon(.search, accessibilityLabel: "Search", enabled: enabled, action: {}),
            .icon(.moreVert, accessibilityLabel: "More actions", enabled: enabled, action: {}),
        ]
    }

    static var topBarEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "top-bar", states: [
            ComponentCaptureState("back-actions") {
                OpenCodeTopBar(title: "Session", leading: .back(action: {}), actions: topBarSearchActions())
            },
            ComponentCaptureState("title-only") { OpenCodeTopBar(title: "Component Gallery") },
            ComponentCaptureState("status-subtitle") {
                OpenCodeTopBar(
                    title: "Importing icons explanation",
                    subtitleAccessory: .statusDot(.live),
                    leading: .back(action: {}),
                    actions: topBarSearchActions(),
                    showDivider: true
                )
            },
            ComponentCaptureState("project-status") {
                OpenCodeTopBar(
                    title: "Wire session project chrome",
                    subtitle: "opencode-mobile",
                    subtitleLeading: .letter("O", .blue),
                    subtitleAccessory: .statusDot(.live),
                    leading: .back(action: {}),
                    actions: topBarSearchActions(),
                    showDivider: true
                )
            },
            ComponentCaptureState("disabled-actions") {
                OpenCodeTopBar(title: "Session", leading: .back(action: {}), actions: topBarSearchActions(enabled: false))
            },
            ComponentCaptureState("title-switcher") {
                OpenCodeTopBar(
                    title: "My workstation",
                    subtitleAccessory: .statusDot(.live),
                    actions: topBarSearchActions(),
                    onTitleClick: {}
                )
            },
        ])
    }

    static func actionBar(
        name: String = "opencode-mobile",
        tag: String? = "git",
        path: String = "/Users/example/Projects/opencode-mobile",
        action: OpenCodeText = "Add this folder",
        target: Bool = true,
        enabled: Bool = true,
        compact: Bool = false
    ) -> some View {
        OpenCodeActionBar(
            target: target ? OpenCodeActionBarTarget(icon: .folder, title: name, tag: tag.map { .verbatim($0) }, subtitle: path) : nil,
            actionTitle: action,
            enabled: enabled,
            compact: compact,
            action: {}
        )
    }

    static var actionBarEntry: ComponentCaptureEntry {
        let longName = "a-very-long-folder-name-that-does-not-fit-on-one-line"
        return ComponentCaptureEntry(componentID: "action-bar", states: [
            ComponentCaptureState("enabled") { actionBar() },
            ComponentCaptureState("disabled-reason") {
                actionBar(name: "notes", tag: nil, path: "/Users/example/notes", action: "Already on Home", enabled: false)
            },
            ComponentCaptureState("compact-keyboard-up") { actionBar(compact: true) },
            ComponentCaptureState("no-target") { actionBar(target: false) },
            ComponentCaptureState("long-name-and-path") {
                actionBar(name: longName, path: "/Users/example/Projects/clients/sample/monorepo/packages/\(longName)")
            },
        ])
    }

    static var splitPaneEntry: ComponentCaptureEntry {
        func pane(width: CGFloat, collapsed: Bool = false) -> some View {
            ComponentCaptureScaledToFit(width: 834, height: 360) {
                SplitPaneGalleryView(
                    state: OpenCodeSplitPaneState(leadingWidth: width, isCollapsed: collapsed, followsFold: false),
                    showsCaption: false
                )
            }
        }
        let note = "Laid out at 834 pt tablet width and scaled to the tile, as the HTML gallery does"
        return ComponentCaptureEntry(
            componentID: "split-pane",
            states: [
                ComponentCaptureState("default-360", note: note) { pane(width: 360) },
                ComponentCaptureState("minimum-280", note: note) { pane(width: 280) },
                ComponentCaptureState("maximum-480", note: note) { pane(width: 480) },
                ComponentCaptureState("collapsed", note: note) { pane(width: 360, collapsed: true) },
            ],
            skipped: [
                ComponentCaptureSkip(id: "dragging", reason: "Gesture-driven: the native pane draws it only during a live drag"),
            ]
        )
    }
}
#endif
