import SwiftUI

private struct ReorderableGalleryProject: Identifiable, Equatable {
    let id: String
    let title: String
    let path: String
    let accent: LetterAccent
    var isAll: Bool { id == "all" }
}

struct ReorderableListGalleryView: View {
    @State private var reorderEnabled: Bool
    @State private var contextActionEnabled: Bool
    @State private var dragHandles: Bool
    /// Shows the Behavior toggles; captures leave them out and show only the list.
    private let chrome: Bool
    @State private var lastEvent = "Long-press a project row, then drag."
    @State private var items: [ReorderableGalleryProject] = [
        .init(id: "all", title: "All projects", path: "4 projects", accent: .violet),
        .init(id: "/Users/example/Projects/opencode-mobile", title: "opencode-mobile", path: "~/Projects/opencode-mobile", accent: .violet),
        .init(id: "/Users/example/Projects/playground", title: "playground", path: "~/Projects/playground", accent: .teal),
        .init(id: "/Users/example/Projects/website", title: "website", path: "~/Projects/website", accent: .amber),
        .init(id: "/Users/example/Projects/docs", title: "docs", path: "~/Projects/docs", accent: .blue),
    ]

    init(
        reorderEnabled: Bool = true,
        contextActionEnabled: Bool = true,
        dragHandles: Bool = false,
        chrome: Bool = true
    ) {
        _reorderEnabled = State(initialValue: reorderEnabled)
        _contextActionEnabled = State(initialValue: contextActionEnabled)
        _dragHandles = State(initialValue: dragHandles)
        self.chrome = chrome
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if chrome {
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
                        OpenCodeChoiceBadge(label: "Drag handles", selected: dragHandles, action: { dragHandles.toggle() })
                    }
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
                activation: dragHandles ? .handle : .longPress,
                onSelect: { item in lastEvent = "Selected \(item.title)." },
                onContextAction: contextActionEnabled
                    ? { item in lastEvent = "Context action on \(item.title)." }
                    : nil,
                supportsContextAction: { !$0.isAll }
            ) { item in
                OpenCodeListItem(
                    title: .verbatim(item.title),
                    subtitle: .verbatim(item.path),
                    startDecorator: item.isAll
                        ? .iconCircle(.widgets, accessibilityLabel: nil)
                        : .letter(item.title.first ?? "?", item.accent)
                )
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
