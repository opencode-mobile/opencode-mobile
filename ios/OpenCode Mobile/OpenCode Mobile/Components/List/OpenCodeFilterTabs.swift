import SwiftUI

struct OpenCodeFilterTab: Identifiable, Hashable {
    let id: String
    let title: String
    let count: Int?
    /// Something new arrived since the tab was last viewed: a small accent dot after the label.
    let unseen: Bool

    init(id: String, title: String, count: Int? = nil, unseen: Bool = false) {
        self.id = id
        self.title = title
        self.count = count
        self.unseen = unseen
    }
}

/// How `OpenCodeFilterTabs` lays out its tabs.
enum OpenCodeFilterTabsStyle: Equatable {
    /// Outlined capsules in a row that scrolls sideways, with count badges; the default.
    case scrolling
    /// A segmented control: equal-width segments on a track, filling the width, the selected
    /// one raised. For two or three fixed views of one list, such as Running and Done.
    case segmented
}

struct OpenCodeFilterTabs: View {
    let tabs: [OpenCodeFilterTab]
    let selectedTabId: String
    let style: OpenCodeFilterTabsStyle
    let onTabSelected: (String) -> Void

    init(
        tabs: [OpenCodeFilterTab],
        selectedTabId: String,
        style: OpenCodeFilterTabsStyle = .scrolling,
        onTabSelected: @escaping (String) -> Void
    ) {
        self.tabs = tabs
        self.selectedTabId = selectedTabId
        self.style = style
        self.onTabSelected = onTabSelected
    }

    var body: some View {
        if tabs.isEmpty {
            EmptyView()
        } else if style == .segmented {
            segmented
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(tabs) { tab in
                        filterTab(tab)
                    }
                }
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
                if tab.unseen { unseenDot }
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
    }

    private var segmented: some View {
        HStack(spacing: Self.segmentedTrackPadding) {
            ForEach(tabs) { tab in
                segment(tab)
            }
        }
        .padding(Self.segmentedTrackPadding)
        .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
    }

    private func segment(_ tab: OpenCodeFilterTab) -> some View {
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
                        .font(OpenCodeTypography.button.monospacedDigit())
                        .foregroundStyle(OpenCodeColors.textWeaker)
                }
                if tab.unseen { unseenDot }
            }
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: Self.segmentedMinHeight)
            .background {
                if selected {
                    Capsule()
                        .fill(OpenCodeColors.surfaceBase)
                        .overlay(Capsule().strokeBorder(OpenCodeColors.borderWeakerBase, lineWidth: 1))
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityLabel(filterTabAccessibilityLabel(tab))
    }

    private var unseenDot: some View {
        Circle()
            .fill(OpenCodeColors.interactive)
            .frame(width: Self.unseenDotSize, height: Self.unseenDotSize)
            .accessibilityHidden(true)
    }

    private func accessibilityLabel(for tab: OpenCodeFilterTab) -> String {
        filterTabAccessibilityLabel(tab)
    }

    private static let segmentedTrackPadding: CGFloat = 3
    private static let segmentedMinHeight: CGFloat = 32
    private static let unseenDotSize: CGFloat = 6
}

/// "Done, 3, new": the count and the unseen dot are read out with the title.
func filterTabAccessibilityLabel(_ tab: OpenCodeFilterTab) -> String {
    [tab.title, tab.count.map(String.init), tab.unseen ? "new" : nil]
        .compactMap { $0 }
        .joined(separator: ", ")
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
