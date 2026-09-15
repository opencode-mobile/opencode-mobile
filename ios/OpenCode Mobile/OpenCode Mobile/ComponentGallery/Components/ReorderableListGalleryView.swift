import SwiftUI

private struct ReorderableGalleryProject: Identifiable, Equatable {
    let id: String
    let title: String
    let path: String
    let accent: LetterAccent
    let count: Int
    var isAll: Bool { id == "all" }
}

struct ReorderableListGalleryView: View {
    @State private var reorderEnabled = true
    @State private var contextActionEnabled = true
    @State private var lastEvent = "Long-press a project row, then drag."
    @State private var items: [ReorderableGalleryProject] = [
        .init(id: "all", title: "All projects", path: "4 projects", accent: .violet, count: 12),
        .init(id: "/Users/me/Projects/opencode-mobile", title: "opencode-mobile", path: "~/Projects/opencode-mobile", accent: .violet, count: 6),
        .init(id: "/Users/me/Projects/playground", title: "playground", path: "~/Projects/playground", accent: .teal, count: 3),
        .init(id: "/Users/me/Projects/website", title: "website", path: "~/Projects/website", accent: .amber, count: 2),
        .init(id: "/Users/me/Projects/docs", title: "docs", path: "~/Projects/docs", accent: .blue, count: 1),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Behavior")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(label: "Reorder", selected: reorderEnabled, action: { reorderEnabled.toggle() })
                    OpenCodeChoiceBadge(
                        label: "Context action",
                        selected: contextActionEnabled,
                        action: { contextActionEnabled.toggle() }
                    )
                }
            }

            Text(lastEvent)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)

            OpenCodeReorderableList(
                items: items,
                canMove: { !$0.isAll },
                onMove: reorderEnabled
                    ? { from, to in
                        items = OpenCodeReorderableListMath.move(items, from: from, to: to)
                        lastEvent = "Moved row \(from) to \(to)."
                    }
                    : nil,
                onSelect: { item in lastEvent = "Selected \(item.title)." },
                onContextAction: contextActionEnabled
                    ? { item in lastEvent = "Context action on \(item.title)." }
                    : nil,
                supportsContextAction: { !$0.isAll }
            ) { item in
                OpenCodeListItem(
                    title: item.title,
                    subtitle: item.path,
                    startDecorator: item.isAll
                        ? .iconCircle(.widgets, accessibilityLabel: nil)
                        : .letter(item.title.first ?? "?", item.accent)
                ) {
                    SessionListScopeCountBadge(count: item.count)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ReorderableListGalleryView()
        .padding(16)
        .openCodeTheme()
}
