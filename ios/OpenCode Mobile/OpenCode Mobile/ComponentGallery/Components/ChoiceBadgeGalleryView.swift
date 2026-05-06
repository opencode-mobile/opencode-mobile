import SwiftUI

struct ChoiceBadgeGalleryView: View {
    @State private var selection = "Alpha"

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textPrimary)
            Text("Tap a badge to select it.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textSecondary)

            HStack(spacing: 8) {
                ForEach(["Alpha", "Beta", "Gamma"], id: \.self) { option in
                    OpenCodeChoiceBadge(
                        label: option,
                        selected: selection == option,
                        action: { selection = option }
                    )
                }
            }

            Text("Disabled")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textPrimary)
            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Unavailable", selected: false, enabled: false, action: {})
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ChoiceBadgeGalleryView()
        .padding()
        .openCodeTheme()
}
