import SwiftUI

struct EmptyStateGalleryView: View {
    @State private var showsIcon = true

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            OpenCodeChoiceBadge(label: "Icon", selected: showsIcon) {
                showsIcon.toggle()
            }
            OpenCodeEmptyState(
                title: "No projects yet",
                message: "Tap the Add project icon below to choose a folder on your server.",
                icon: showsIcon ? .createNewFolder : nil
            )
            .frame(height: 280)
        }
    }
}

#Preview {
    EmptyStateGalleryView()
        .padding()
        .openCodeTheme()
}
