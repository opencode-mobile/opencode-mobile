import SwiftUI

struct KeyValueRowGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            OpenCodeKeyValueRow(label: "Outcome", value: "Highlighted")
            OpenCodeKeyValueRow(label: "Backend", value: "native")
            OpenCodeKeyValueRow(label: "Tokens", value: "12.4k", style: .stacked)
            OpenCodeKeyValueRow(label: "Cost", value: "$0.02", style: .stacked)
        }
    }
}

#Preview {
    KeyValueRowGalleryView()
        .padding()
        .openCodeTheme()
}
