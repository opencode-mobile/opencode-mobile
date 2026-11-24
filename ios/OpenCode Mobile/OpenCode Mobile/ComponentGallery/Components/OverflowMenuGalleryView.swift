import SwiftUI

struct OverflowMenuGalleryView: View {
    @State private var menuExpanded = false
    @State private var anchoredExpanded = false
    @State private var entriesEnabled = true
    @State private var selectionRowsEnabled = false
    @State private var showDestructive = false
    @State private var showDivider = false
    @State private var itemCount = 2

    private var entries: [OpenCodeOverflowMenuEntry] {
        Self.entries(
            enabled: entriesEnabled,
            selectionRows: selectionRowsEnabled,
            destructive: showDestructive,
            divider: showDivider,
            count: itemCount
        )
    }

    /// The gallery's menu entries; `disabledReason` adds a disabled entry with a subtitle and
    /// `textOnly` drops every icon.
    static func entries(
        enabled: Bool = true,
        selectionRows: Bool = false,
        destructive: Bool = false,
        divider: Bool = false,
        count: Int = 2,
        disabledReason: Bool = false,
        textOnly: Bool = false
    ) -> [OpenCodeOverflowMenuEntry] {
        func icon(_ icon: OpenCodeIcon?) -> OpenCodeIcon? { textOnly ? nil : icon }
        var items: [OpenCodeOverflowMenuEntry] = [
            OpenCodeOverflowMenuEntry(
                title: "Session context",
                icon: icon(selectionRows ? .check : .architecture),
                enabled: enabled,
                accessibilityLabel: selectionRows ? "Session context, selected" : nil,
                action: {}
            ),
        ]
        if disabledReason {
            items.append(
                OpenCodeOverflowMenuEntry(
                    title: "Move to worktree…",
                    subtitle: "Needs an opencode v2 server",
                    icon: icon(.callSplit),
                    enabled: false,
                    action: {}
                )
            )
        }
        items.append(
            OpenCodeOverflowMenuEntry(
                title: "Copy session ID",
                icon: icon(selectionRows ? nil : .contentCopy),
                enabled: enabled,
                dividerAbove: divider,
                action: {}
            )
        )
        if count >= 3 {
            items.append(OpenCodeOverflowMenuEntry(title: "Settings", icon: icon(.settings), enabled: enabled, action: {}))
        }
        if count >= 4 {
            items.append(OpenCodeOverflowMenuEntry(title: "Search", icon: icon(.search), enabled: enabled, action: {}))
        }
        if destructive {
            items.append(
                OpenCodeOverflowMenuEntry(
                    title: "Delete",
                    icon: icon(.close),
                    enabled: enabled,
                    destructive: true,
                    action: {}
                )
            )
        }
        return items
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 16) {
                Text("Overflow menu")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)

                ControlGroup(title: "State") {
                    OpenCodeChoiceBadge(
                        label: "Entries enabled",
                        selected: entriesEnabled,
                        action: { entriesEnabled.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Selection rows",
                        selected: selectionRowsEnabled,
                        action: { selectionRowsEnabled.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Destructive",
                        selected: showDestructive,
                        action: { showDestructive.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Divider",
                        selected: showDivider,
                        action: { showDivider.toggle() }
                    )
                }

                ControlGroup(title: "Entry count") {
                    HStack(spacing: 8) {
                        ForEach([2, 3, 4], id: \.self) { count in
                            OpenCodeChoiceBadge(
                                label: "\(count)",
                                selected: itemCount == count,
                                action: { itemCount = count }
                            )
                        }
                    }
                }

                Text(
                    "Use the overflow icon in the live top bar preview, or the + button for the anchored " +
                        "menu. Switch controls to review enabled, disabled, and aligned selection states."
                )
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
            }
            .padding(.horizontal, 16)

            OpenCodeTopBarOverflowHost(
                isPresented: $menuExpanded,
                entries: entries
            ) {
                OpenCodeTopBar(
                    title: "Session",
                    leading: .back(action: {}),
                    actions: [
                        .icon(.moreVert, accessibilityLabel: "More options", action: { menuExpanded = true }),
                    ],
                    showDivider: true
                )
            }

            OpenCodeAnchoredOverflowHost(
                isPresented: $anchoredExpanded,
                entries: entries
            ) {
                OpenCodeButton(
                    variant: .iconCircle(.add, accessibilityLabel: "Add attachment"),
                    contrast: .low,
                    action: { anchoredExpanded = true }
                )
            }
            .padding(.horizontal, 16)
        }
    }
}

private struct ControlGroup<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)

            content()
        }
    }
}

#Preview {
    ScrollView {
        OverflowMenuGalleryView()
    }
    .openCodeTheme()
}
