import SwiftUI

struct DropOverlayGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            OpenCodeDropOverlay(
                variant: .accept,
                title: "Drop to attach 3 images",
                subtitle: "Adds them to the message you are writing",
                previews: [.image, .image, .image]
            )
            .frame(height: 320)
            OpenCodeDropOverlay(
                variant: .accept,
                title: "Drop to attach 2 files",
                subtitle: "1 will be skipped · Folders can't be attached.",
                previews: [.image, .text]
            )
            .frame(height: 320)
            OpenCodeDropOverlay(
                variant: .reject,
                title: "Can't attach this",
                subtitle: "Folders can't be attached."
            )
            .frame(height: 320)
        }
    }
}

#Preview {
    DropOverlayGalleryView()
        .padding()
        .openCodeTheme()
}
