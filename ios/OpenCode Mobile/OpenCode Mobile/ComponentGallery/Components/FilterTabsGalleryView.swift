import SwiftUI

struct FilterTabsGalleryView: View {
    @State private var selectedTabId = "all"
    @State private var segmentedTabId = "running"

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Horizontal single-select filters with optional count badges.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .padding(.horizontal, 16)

            OpenCodeFilterTabs(
                tabs: filterTabsGallerySamples,
                selectedTabId: selectedTabId,
                onTabSelected: { selectedTabId = $0 }
            )

            Text("Segmented: equal-width views of one list. A dot marks something new since the tab was last viewed.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .padding(.horizontal, 16)

            OpenCodeFilterTabs(
                tabs: [
                    OpenCodeFilterTab(id: "running", title: "Running", count: 2),
                    OpenCodeFilterTab(id: "done", title: "Done", count: 5, unseen: segmentedTabId != "done"),
                ],
                selectedTabId: segmentedTabId,
                style: .segmented,
                onTabSelected: { segmentedTabId = $0 }
            )
            .padding(.horizontal, 16)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(OpenCodeColors.backgroundBase)
    }
}

private let filterTabsGallerySamples: [OpenCodeFilterTab] = [
    OpenCodeFilterTab(id: "all", title: "All", count: 5),
    OpenCodeFilterTab(id: "friends", title: "Friends"),
    OpenCodeFilterTab(id: "unread", title: "Unread", count: 45),
    OpenCodeFilterTab(id: "news", title: "News", count: 10),
    OpenCodeFilterTab(id: "mobile", title: "opencode-mobile", count: 3),
]

#Preview {
    FilterTabsGalleryView()
        .openCodeTheme()
}
