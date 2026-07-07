import SwiftUI

struct OverflowMenuGalleryView: View {
    @State private var menuExpanded = false
    @State private var entriesEnabled = true
    @State private var itemCount = 2

    private var entries: [OpenCodeOverflowMenuEntry] {
        var items: [OpenCodeOverflowMenuEntry] = [
            OpenCodeOverflowMenuEntry(
                title: "Session context",
                icon: .architecture,
                enabled: entriesEnabled,
                action: {}
            ),
            OpenCodeOverflowMenuEntry(
                title: "Copy session ID",
                icon: .contentCopy,
                enabled: entriesEnabled,
                action: {}
            ),
        ]

        if itemCount >= 3 {
            items.append(
                OpenCodeOverflowMenuEntry(
                    title: "Settings",
                    icon: .settings,
                    enabled: entriesEnabled,
                    action: {}
                )
            )
        }

        if itemCount >= 4 {
            items.append(
                OpenCodeOverflowMenuEntry(
                    title: "Search",
                    icon: .search,
                    enabled: entriesEnabled,
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
                    "Use the overflow icon in the live top bar preview to open the menu. " +
                        "Switch controls to review enabled and disabled entry states."
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
