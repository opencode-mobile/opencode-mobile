import SwiftUI

struct ServerAddressInputGalleryView: View {
    @State private var value = "http://127.0.0.1:4096/"
    @State private var username = "opencode"
    @State private var enabled = true
    @State private var showsError = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Enabled", selected: enabled) {
                    enabled.toggle()
                }
                OpenCodeChoiceBadge(label: "Error", selected: showsError) {
                    showsError.toggle()
                }
                OpenCodeChoiceBadge(label: "Hostname sample", selected: value.contains("macbook.local")) {
                    value = "https://macbook.local:8080/"
                }
                OpenCodeChoiceBadge(label: "Empty", selected: value == "http://") {
                    value = "http://"
                }
            }
            OpenCodeServerAddressInput(
                value: $value,
                enabled: enabled,
                errorMessage: showsError ? "Enter a valid host and port" : nil
            )
            OpenCodeTextInput(
                text: $username,
                hint: "Username",
                enabled: enabled,
                errorMessage: showsError ? "Required" : nil
            )
        }
    }
}

#Preview {
    ServerAddressInputGalleryView()
        .padding()
        .openCodeTheme()
}
