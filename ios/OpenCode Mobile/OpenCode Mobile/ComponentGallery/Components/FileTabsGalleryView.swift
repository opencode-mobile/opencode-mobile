import SwiftUI

struct FileTabsGalleryView: View {
    @State private var tabs = fileTabsGallerySamples
    @State private var selectedTabId: String? = fileTabsGallerySamples[1].id

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Open files in the tablet viewer, with close buttons.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .padding(.horizontal, 16)

            OpenCodeFileTabs(
                tabs: tabs,
                selectedTabId: selectedTabId,
                onSelect: { selectedTabId = $0 },
                onClose: { id in
                    tabs.removeAll { $0.id == id }
                    if selectedTabId == id { selectedTabId = tabs.last?.id }
                }
            )

            if tabs.isEmpty {
                OpenCodeButton(variant: .compactText(title: "Reopen samples")) {
                    tabs = fileTabsGallerySamples
                    selectedTabId = fileTabsGallerySamples[1].id
                }
                .padding(.horizontal, 16)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(OpenCodeColors.backgroundBase)
    }
}

private let fileTabsGallerySamples: [OpenCodeFileTab] = [
    OpenCodeFileTab(id: "README.md", title: "README.md"),
    OpenCodeFileTab(id: "ios/OpenCode Mobile/UI/Files/FilesView.swift", title: "FilesView.swift"),
    OpenCodeFileTab(id: "ios/OpenCode Mobile/UI/Files/FilesViewModel.swift", title: "FilesViewModel.swift"),
    OpenCodeFileTab(id: "android/app/build.gradle.kts", title: "build.gradle.kts"),
    OpenCodeFileTab(id: "docs/a-very-long-file-name-that-keeps-going.md", title: "a-very-long-file-name-that-keeps-going.md"),
]

#Preview {
    FileTabsGalleryView()
        .openCodeTheme()
}
