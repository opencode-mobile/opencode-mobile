import SwiftUI

private enum OpenCodeFileTabsMetrics {
    static let rowPaddingHorizontal: CGFloat = 8
    static let rowPaddingVertical: CGFloat = 6
    static let spacing: CGFloat = 4
    static let titleLeadingPadding: CGFloat = 10
    static let titleTrailingPadding: CGFloat = 2
    static let titleMaxWidth: CGFloat = 180
    static let closeTarget: CGFloat = 32
    static let closeIconSize: CGFloat = 14
}

/// One open file in `OpenCodeFileTabs`.
struct OpenCodeFileTab: Identifiable, Hashable {
    let id: String
    /// File name shown on the tab.
    let title: String
}

/// Horizontally scrolling strip of open files for the tablet File Viewer: tap a tab to select
/// it, × to close it. The selected tab scrolls into view.
struct OpenCodeFileTabs: View {
    let tabs: [OpenCodeFileTab]
    let selectedTabId: String?
    let showsDivider: Bool
    let onSelect: (String) -> Void
    let onClose: (String) -> Void

    init(
        tabs: [OpenCodeFileTab],
        selectedTabId: String?,
        showsDivider: Bool = true,
        onSelect: @escaping (String) -> Void,
        onClose: @escaping (String) -> Void
    ) {
        self.tabs = tabs
        self.selectedTabId = selectedTabId
        self.showsDivider = showsDivider
        self.onSelect = onSelect
        self.onClose = onClose
    }

    var body: some View {
        if !tabs.isEmpty {
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: OpenCodeFileTabsMetrics.spacing) {
                            ForEach(tabs) { tab in
                                tabItem(tab)
                                    .id(tab.id)
                            }
                        }
                        .padding(.horizontal, OpenCodeFileTabsMetrics.rowPaddingHorizontal)
                        .padding(.vertical, OpenCodeFileTabsMetrics.rowPaddingVertical)
                    }
                    .onAppear {
                        if let selectedTabId { proxy.scrollTo(selectedTabId) }
                    }
                    .onChange(of: selectedTabId) { _, id in
                        guard let id else { return }
                        withAnimation { proxy.scrollTo(id) }
                    }
                }
                if showsDivider {
                    Rectangle()
                        .fill(OpenCodeColors.borderWeakerBase)
                        .frame(height: 1)
                }
            }
        }
    }

    private func tabItem(_ tab: OpenCodeFileTab) -> some View {
        let selected = tab.id == selectedTabId
        let shape = RoundedRectangle(cornerRadius: OpenCodeRadius.small, style: .continuous)
        return HStack(spacing: 0) {
            Button {
                onSelect(tab.id)
            } label: {
                Text(tab.title)
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(selected ? OpenCodeColors.textStrong : OpenCodeColors.textWeak)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .frame(maxWidth: OpenCodeFileTabsMetrics.titleMaxWidth)
                    .fixedSize(horizontal: true, vertical: false)
                    .padding(.leading, OpenCodeFileTabsMetrics.titleLeadingPadding)
                    .padding(.trailing, OpenCodeFileTabsMetrics.titleTrailingPadding)
                    .frame(minHeight: OpenCodeFileTabsMetrics.closeTarget)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(tab.title)
            .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
            .openCodeKeyboardFocusable(.roundedRectangle(cornerRadius: OpenCodeRadius.small), activate: { onSelect(tab.id) })

            Button {
                onClose(tab.id)
            } label: {
                OpenCodeIconImage(icon: .close, size: OpenCodeFileTabsMetrics.closeIconSize)
                    .foregroundStyle(OpenCodeColors.iconBase)
                    .frame(width: OpenCodeFileTabsMetrics.closeTarget, height: OpenCodeFileTabsMetrics.closeTarget)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close \(tab.title)")
            .openCodeKeyboardFocusable(.circle, activate: { onClose(tab.id) })
        }
        .background(selected ? OpenCodeColors.surfaceRaisedBase : Color.clear, in: shape)
        .overlay(shape.strokeBorder(selected ? OpenCodeColors.borderWeakBase : Color.clear, lineWidth: 1))
    }
}

#if DEBUG
#Preview("File Tabs") {
    struct PreviewHost: View {
        @State private var tabs = [
            OpenCodeFileTab(id: "README.md", title: "README.md"),
            OpenCodeFileTab(id: "src/main.swift", title: "main.swift"),
            OpenCodeFileTab(id: "src/ui/Button.swift", title: "Button.swift"),
        ]
        @State private var selected: String? = "src/main.swift"

        var body: some View {
            OpenCodeFileTabs(
                tabs: tabs,
                selectedTabId: selected,
                onSelect: { selected = $0 },
                onClose: { id in
                    tabs.removeAll { $0.id == id }
                    if selected == id { selected = tabs.last?.id }
                }
            )
            .openCodeTheme()
        }
    }
    return PreviewHost()
}
#endif
