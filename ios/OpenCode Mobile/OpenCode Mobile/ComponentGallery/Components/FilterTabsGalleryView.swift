import SwiftUI

struct FilterTabsGalleryView: View {
    @State private var selectedTabId = "all"

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
