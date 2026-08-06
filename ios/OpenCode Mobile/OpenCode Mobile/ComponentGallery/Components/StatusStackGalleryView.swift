import SwiftUI

struct StatusStackGalleryView: View {
    @State private var usesSessionCopy = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            OpenCodeChoiceBadge(label: "Session copy", selected: usesSessionCopy) {
                usesSessionCopy.toggle()
            }
            OpenCodeStatusStack(
                title: usesSessionCopy ? "Loading messages" : "Loading sessions",
                subtitle: usesSessionCopy ? "Fetching timeline…" : "Checking server…"
            )
            .frame(height: 280)
            .background(OpenCodeColors.backgroundBase)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }
}

#Preview {
    StatusStackGalleryView()
        .padding()
        .openCodeTheme()
}
