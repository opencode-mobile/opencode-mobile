import SwiftUI

struct OpenCodeFilterTab: Identifiable, Hashable {
    let id: String
    let title: String
    let count: Int?

    init(id: String, title: String, count: Int? = nil) {
        self.id = id
        self.title = title
        self.count = count
    }
}

struct OpenCodeFilterTabs: View {
    let tabs: [OpenCodeFilterTab]
    let selectedTabId: String
    let onTabSelected: (String) -> Void

    var body: some View {
        if tabs.isEmpty {
            EmptyView()
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(tabs) { tab in
                        filterTab(tab)
                    }
                }
                .openCodeFocusGroup(.horizontal)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
        }
    }

    private func filterTab(_ tab: OpenCodeFilterTab) -> some View {
        let selected = tab.id == selectedTabId
        return Button {
            onTabSelected(tab.id)
        } label: {
            HStack(spacing: 6) {
                Text(tab.title)
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(selected ? OpenCodeColors.textStrong : OpenCodeColors.textBase)
                    .lineLimit(1)
                if let count = tab.count {
                    Text("\(count)")
                        .font(OpenCodeTypography.caption.weight(.semibold))
                        .foregroundStyle(OpenCodeColors.textBase)
                        .padding(.horizontal, 6)
                        .frame(minWidth: 22, minHeight: 22)
                        .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(minHeight: 36)
            .background(Color.clear, in: Capsule())
            .overlay(
                Capsule()
                    .strokeBorder(
                        selected ? OpenCodeColors.borderWeakBase : Color.clear,
                        lineWidth: 1.5
                    )
            )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityLabel(accessibilityLabel(for: tab))
        .openCodeKeyboardFocusable(.capsule, activate: { onTabSelected(tab.id) })
    }

    private func accessibilityLabel(for tab: OpenCodeFilterTab) -> String {
        if let count = tab.count {
            return "\(tab.title), \(count)"
        }
        return tab.title
    }
}

#if DEBUG
#Preview {
    struct PreviewHost: View {
        @State private var selected = "all"
        var body: some View {
            OpenCodeFilterTabs(
                tabs: [
                    OpenCodeFilterTab(id: "all", title: "All", count: 5),
                    OpenCodeFilterTab(id: "friends", title: "Friends"),
                    OpenCodeFilterTab(id: "unread", title: "Unread", count: 45),
                    OpenCodeFilterTab(id: "news", title: "News", count: 10),
                ],
                selectedTabId: selected,
                onTabSelected: { selected = $0 }
            )
            .openCodeTheme()
        }
    }
    return PreviewHost()
}
#endif
