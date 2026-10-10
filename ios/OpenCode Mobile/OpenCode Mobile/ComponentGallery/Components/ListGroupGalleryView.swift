import SwiftUI

struct ListGroupGalleryView: View {
    private let files = ["Sources/App.swift", "README.md", "Tests/AppTests.swift"]

    var body: some View {
        OpenCodeListGroup(files, id: \.self) { path in
            OpenCodeListItem(title: path, accessory: .disclosure)
        }
    }
}

#Preview {
    ListGroupGalleryView().padding().openCodeTheme()
}
