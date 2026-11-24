#if DEBUG
import SwiftUI

extension ComponentCaptureRegistry {
    static var listsEntries: [ComponentCaptureEntry] {
        [
            keyValueRowEntry, chartsEntry, listGroupEntry, listItemEntry, treeRowEntry, selectableCardEntry,
            optionCardEntry, featureItemEntry, sectionHeaderEntry, reorderableListEntry, runSessionInCardEntry,
        ]
    }
}

private extension ComponentCaptureRegistry {
    static var keyValueRowEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "key-value-row", states: [
            ComponentCaptureState("inline") { OpenCodeKeyValueRow(label: "Outcome", value: "Highlighted") },
            ComponentCaptureState("inline-short") { OpenCodeKeyValueRow(label: "Backend", value: "native") },
            ComponentCaptureState("stacked") { OpenCodeKeyValueRow(label: "Tokens", value: "12.4k", style: .stacked) },
            ComponentCaptureState("stacked-cost") { OpenCodeKeyValueRow(label: "Cost", value: "$0.02", style: .stacked) },
        ])
    }

    static var chartsEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "charts", states: [
            ComponentCaptureState("band-chart") { ChartsGalleryView.bandCard() },
            ComponentCaptureState("status-strip") { ChartsGalleryView.stripCard() },
            ComponentCaptureState(
                "bar-chart",
                note: "The native bar chart has no tone; drawn in its own colour"
            ) { ChartsGalleryView.barCard() },
            ComponentCaptureState("sparkline") {
                OpenCodeSparkline(values: ChartsGalleryView.latencies, tone: .success, showsGaps: true).frame(height: 40)
            },
            ComponentCaptureState("sparkline-gaps-hidden") {
                OpenCodeSparkline(values: ChartsGalleryView.latencies, tone: .warning, showsGaps: false).frame(height: 40)
            },
            ComponentCaptureState("stat-tiles") { ChartsGalleryView.statTiles() },
            ComponentCaptureState("proportion-rows") { ChartsGalleryView.proportionRows() },
            ComponentCaptureState("list-item-footer") { ChartsGalleryView.footerItem() },
        ])
    }

    static var listGroupEntry: ComponentCaptureEntry {
        func group(_ rows: Int) -> some View {
            OpenCodeListGroup(Array(ListGroupGalleryView.files.prefix(rows)), id: \.self) { path in
                OpenCodeListItem(title: .verbatim(path), accessory: .disclosure)
            }
        }
        return ComponentCaptureEntry(componentID: "list-group", states: [
            ComponentCaptureState("three-rows") { group(3) },
            ComponentCaptureState("two-rows") { group(2) },
            ComponentCaptureState("one-row") { group(1) },
            ComponentCaptureState("empty", note: "Renders nothing; the tile is the empty container") { group(0) },
        ])
    }
}

// MARK: - List item

private struct CaptureListItemToggle: View {
    @State private var isOn = true

    var body: some View {
        OpenCodeListItem(
            title: "opencode-mobile",
            subtitle: "Fix composer outline fade",
            style: .card,
            accessory: .toggle(isOn: $isOn, accessibilityLabel: "opencode-mobile")
        )
    }
}

private extension ComponentCaptureRegistry {
    static func listItem(
        title: OpenCodeText = "opencode-mobile",
        subtitle: OpenCodeText? = "Fix composer outline fade",
        caption: OpenCodeText? = "2m",
        status: OpenCodeListItemStatus? = OpenCodeListItemStatus(text: "Ready", tone: .success),
        style: OpenCodeListItemStyle = .plain,
        decorator: StartDecorator = .letter("O", .violet),
        accessory: OpenCodeListItemAccessory? = nil,
        selected: Bool = false,
        enabled: Bool = true
    ) -> some View {
        OpenCodeListItem(
            title: title,
            subtitle: subtitle,
            status: status,
            caption: caption,
            selected: selected,
            enabled: enabled,
            style: style,
            startDecorator: decorator,
            accessory: accessory,
            action: {}
        )
    }

    static var listItemEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "list-item", states: [
            ComponentCaptureState("plain") { listItem() },
            ComponentCaptureState("card") { listItem(style: .card) },
            ComponentCaptureState("selected") { listItem(selected: true) },
            ComponentCaptureState("pressed") { listItem().openCodePressedPreview() },
            ComponentCaptureState("disabled") { listItem(enabled: false) },
            ComponentCaptureState("status-dot") {
                listItem(status: nil, decorator: .letter("O", .violet, statusDot: .interactive))
            },
            ComponentCaptureState("worktree") {
                listItem(decorator: .letter("O", .violet, statusDot: .interactive, worktree: true))
            },
            ComponentCaptureState("icon-chevron") {
                listItem(caption: nil, status: nil, decorator: .icon(.folder), accessory: .disclosure)
            },
            ComponentCaptureState("icon-circle") {
                listItem(title: "All projects", subtitle: "8 projects", caption: nil, status: nil, decorator: .iconCircle(.widgets))
            },
            ComponentCaptureState("toggle") { CaptureListItemToggle() },
            ComponentCaptureState("compact") {
                OpenCodeListItem(
                    variant: .compact,
                    title: "Keep split pane in step with the sidebar",
                    path: OpenCodeListItemPath(primary: "opencode-mobile", secondary: "803-compact-home-rows"),
                    status: OpenCodeListItemStatus(text: "Running", tone: .interactive),
                    caption: "now",
                    startDecorator: .compactLetter("O", .red, statusDot: .interactive, pulsing: true),
                    action: {}
                )
            },
            ComponentCaptureState("compact-emphasized") {
                OpenCodeListItem(
                    variant: .compact,
                    title: "Bump Android to 1.2.0 and iOS to 1.1.0",
                    emphasized: true,
                    path: OpenCodeListItemPath(primary: "opencode-mobile"),
                    status: OpenCodeListItemStatus(text: "Done", tone: .success),
                    caption: "3m ago",
                    startDecorator: .compactLetter("O", .red, statusDot: .success),
                    action: {}
                )
            },
            ComponentCaptureState("wrapped") {
                OpenCodeListItem(
                    title: "Keep the current layout and move the settings into a separate screen",
                    subtitle: """
                    Every word of a long description stays readable, and the selection mark stays beside the \
                    first line of the title however tall the row grows.
                    """,
                    style: .card,
                    textFlow: .wrapped,
                    startDecorator: .icon(.radioButtonUnchecked),
                    action: {}
                )
            },
            ComponentCaptureState("footer") { ChartsGalleryView.footerItem() },
        ])
    }
}

// MARK: - Tree row, cards, headers

private extension ComponentCaptureRegistry {
    static var treeRowEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "tree-row", states: [
            ComponentCaptureState("normal") { OpenCodeTreeRow(title: "ContentView.swift", kind: .file) {} },
            ComponentCaptureState("selected") {
                OpenCodeTreeRow(title: "ContentView.swift", kind: .file, state: .selected) {}
            },
            ComponentCaptureState("loading") {
                OpenCodeTreeRow(title: "Sources", kind: .folder(isExpanded: true), state: .loading) {}
            },
            ComponentCaptureState("dimmed-tag") {
                OpenCodeTreeRow(title: "node_modules", kind: .folder(isExpanded: false), isDimmed: true, tag: "ignored") {}
            },
            ComponentCaptureState("with-subtitle") {
                OpenCodeTreeRow(title: "FilesView.swift", subtitle: "ios/OpenCode Mobile/UI/Files", kind: .file) {}
            },
            ComponentCaptureState("file-type-icon") {
                OpenCodeTreeRow(title: "package.json", kind: .file, icon: .fileType(path: "package.json")) {}
            },
            ComponentCaptureState("depth-2") { OpenCodeTreeRow(title: "ContentView.swift", kind: .file, depth: 2) {} },
        ])
    }

    static var selectableCardEntry: ComponentCaptureEntry {
        func card(selected: Bool) -> some View {
            OpenCodeSelectableCard(title: "Porcelain", blurb: "Warm paper and ink.", selected: selected, action: {}) {
                SelectableCardGalleryView.previewSlot
            }
        }
        return ComponentCaptureEntry(componentID: "selectable-card", states: [
            ComponentCaptureState("unselected") { card(selected: false) },
            ComponentCaptureState("selected") { card(selected: true) },
        ])
    }

    static var optionCardEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "option-card", states: [
            ComponentCaptureState("selected") {
                OpenCodeOptionCard(
                    title: "Activity Card",
                    subtitle: "Running steps on one live card that folds into a summary",
                    tag: "Recommended",
                    tagTone: .accent,
                    preview: .illustration(.timelineActivityCard),
                    selected: true,
                    action: {}
                )
            },
            ComponentCaptureState("unselected") {
                OpenCodeOptionCard(
                    title: "Standard",
                    subtitle: "Tool cards and groups between the agent's messages",
                    tag: "Current",
                    preview: .illustration(.timelineStandard),
                    selected: false,
                    action: {}
                )
            },
            ComponentCaptureState("long-title") {
                OpenCodeOptionCard(
                    title: "Activity Card with a longer title",
                    subtitle: "Running steps on one live card that folds into a summary when the turn ends",
                    tag: "Recommended",
                    tagTone: .accent,
                    preview: .illustration(.timelineActivityCard),
                    selected: false,
                    action: {}
                )
            },
            ComponentCaptureState("no-preview") {
                OpenCodeOptionCard(title: "No preview", subtitle: "Text-only option", selected: false, action: {})
            },
            ComponentCaptureState("disabled") {
                OpenCodeOptionCard(
                    title: "Chips",
                    subtitle: "Tools as chips under the agent's narration",
                    preview: .illustration(.timelineChips),
                    selected: false,
                    enabled: false,
                    action: {}
                )
            },
            ComponentCaptureState("selected-disabled") {
                OpenCodeOptionCard(title: "Selected and disabled", selected: true, enabled: false, action: {})
            },
        ])
    }

    static var featureItemEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "feature-item", states: [
            ComponentCaptureState("neutral") {
                OpenCodeFeatureItem(
                    icon: .edit,
                    title: "Start a session as a draft",
                    body: "Write first, then pick the project and worktree. The session is created when you send."
                )
            },
            ComponentCaptureState("title-only") { OpenCodeFeatureItem(icon: .star, title: "Title only", body: "") },
            ComponentCaptureState("success") {
                OpenCodeFeatureItem(
                    icon: .check,
                    title: "Steadier reconnects",
                    body: "Session Chat picks up where it left off after the network drops.",
                    tone: .success
                )
            },
        ])
    }

    static var sectionHeaderEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "section-header", states: [
            ComponentCaptureState("label") { OpenCodeSectionHeader(title: "Server") },
            ComponentCaptureState("plain-with-count") {
                OpenCodeSectionHeader(title: "Sources/App/Features/SessionChat", variant: .plain, trailing: .count(3))
            },
            ComponentCaptureState("plain-with-action") {
                OpenCodeSectionHeader(title: "3 waiting", variant: .plain, trailing: .action("Clear all", {}))
            },
            ComponentCaptureState("flush") { OpenCodeSectionHeader(title: "Context breakdown", inset: .flush) },
        ])
    }

    static var reorderableListEntry: ComponentCaptureEntry {
        let gesture = "Gesture-driven: the native list draws it only during a live touch"
        return ComponentCaptureEntry(
            componentID: "reorderable-list",
            states: [
                ComponentCaptureState("long-press-to-lift") { ReorderableListGalleryView(chrome: false) },
                ComponentCaptureState("drag-handles") { ReorderableListGalleryView(dragHandles: true, chrome: false) },
                ComponentCaptureState("reorder-off") { ReorderableListGalleryView(reorderEnabled: false, chrome: false) },
                ComponentCaptureState("context-action-off") {
                    ReorderableListGalleryView(contextActionEnabled: false, chrome: false)
                },
            ],
            skipped: [
                ComponentCaptureSkip(id: "pressed-row", reason: gesture),
                ComponentCaptureSkip(id: "lifted-row", reason: gesture),
                ComponentCaptureSkip(id: "swiped-open", reason: gesture),
            ]
        )
    }

    static var runSessionInCardEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "run-session-in-card", states: [
            ComponentCaptureState("new-worktree") { RunSessionInCardGalleryView.newWorktreeCard(compact: false) },
            ComponentCaptureState("keyboard-up") { RunSessionInCardGalleryView.newWorktreeCard(compact: true) },
            ComponentCaptureState("not-a-git-repo") { RunSessionInCardGalleryView.notGitCard(compact: false) },
            ComponentCaptureState("not-a-git-repo-keyboard-up") { RunSessionInCardGalleryView.notGitCard(compact: true) },
        ])
    }
}
#endif
