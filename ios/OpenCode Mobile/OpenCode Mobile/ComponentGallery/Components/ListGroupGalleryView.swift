import SwiftUI

struct ListGroupGalleryView: View {
    static let files = ["Sources/App.swift", "README.md", "Tests/AppTests.swift"]

    var body: some View {
        OpenCodeListGroup(Self.files, id: \.self) { path in
            OpenCodeListItem(title: .verbatim(path), accessory: .disclosure)
        }
    }
}

#Preview {
    ListGroupGalleryView().padding().openCodeTheme()
}
