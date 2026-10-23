import SwiftUI

struct TagGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            OpenCodeTag("Unofficial")
            OpenCodeTag("Beta")
            OpenCodeTag("git", size: .small)
            OpenCodeTag("Recommended", tone: .accent)
            OpenCodeTag("Recommended", size: .small, tone: .accent)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    TagGalleryView()
        .padding()
        .openCodeTheme()
}
