import SwiftUI

struct ActionBarGalleryView: View {
    @State private var enabled = true
    @State private var compact = false
    @State private var longName = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Toggle("Enabled", isOn: $enabled)
            Toggle("Compact (keyboard up)", isOn: $compact)
            Toggle("Long name", isOn: $longName)

            OpenCodeActionBar(
                target: OpenCodeActionBarTarget(
                    icon: .folder,
                    title: name,
                    tag: "git",
                    subtitle: "/Users/me/Projects/\(name)"
                ),
                actionTitle: enabled ? "Add this folder" : "Already on Home",
                enabled: enabled,
                compact: compact,
                action: {}
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var name: String {
        longName ? "opencode-mobile-357-split-view-chat-lifecycle-cache-android" : "opencode-mobile"
    }
}

#Preview {
    ActionBarGalleryView()
        .padding()
        .openCodeTheme()
}
