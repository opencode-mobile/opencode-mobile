import SwiftUI

struct FeatureItemGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            OpenCodeSectionHeader(title: .verbatim("Neutral"), inset: .flush)
            OpenCodeFeatureItem(
                icon: .edit,
                title: .verbatim("Start a session as a draft"),
                body: .verbatim("Write first, then pick the project and worktree. The session is created when you send.")
            )
            OpenCodeFeatureItem(
                icon: .star,
                title: .verbatim("Title only"),
                body: .verbatim("")
            )
            OpenCodeSectionHeader(title: .verbatim("Success"), inset: .flush)
            OpenCodeFeatureItem(
                icon: .check,
                title: .verbatim("Steadier reconnects"),
                body: .verbatim("Session Chat picks up where it left off after the network drops."),
                tone: .success
            )
        }
    }
}

#Preview {
    FeatureItemGalleryView()
        .padding()
        .openCodeTheme()
}
