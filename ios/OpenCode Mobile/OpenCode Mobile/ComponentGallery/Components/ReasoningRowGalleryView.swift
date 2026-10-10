import SwiftUI

struct ReasoningRowGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            OpenCodeReasoningRow(state: .working)
            OpenCodeReasoningRow(state: .expanded, durationLabel: "1.2s", action: {})
            OpenCodeReasoningRow(state: .expanded, action: {})
            OpenCodeReasoningRow(state: .idle)
            OpenCodeReasoningRow(state: .collapsed)
        }
    }
}

#Preview {
    ReasoningRowGalleryView()
        .openCodeTheme()
}
