import SwiftUI

struct DropOverlayGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Self.acceptImages().frame(height: 320)
            Self.acceptMixed().frame(height: 320)
            Self.reject().frame(height: 320)
        }
    }

    static func acceptImages() -> some View {
        OpenCodeDropOverlay(
            variant: .accept,
            title: "Drop to attach 3 images",
            subtitle: "Adds them to the message you are writing",
            previews: [.image, .image, .image]
        )
    }

    static func acceptMixed() -> some View {
        OpenCodeDropOverlay(
            variant: .accept,
            title: "Drop to attach 2 files",
            subtitle: "1 will be skipped · Folders can't be attached.",
            previews: [.image, .text]
        )
    }

    static func reject() -> some View {
        OpenCodeDropOverlay(
            variant: .reject,
            title: "Can't attach this",
            subtitle: "Folders can't be attached."
        )
    }
}

#Preview {
    DropOverlayGalleryView()
        .padding()
        .openCodeTheme()
}
