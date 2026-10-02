import SwiftUI

struct ActionPillGalleryView: View {
    @State private var enabled = true

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Floating pill for contextual overlay actions such as scroll to end.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            OpenCodeChoiceBadge(
                label: "Enabled",
                selected: enabled,
                action: { enabled.toggle() }
            )

            ForEach(Array(OpenCodeActionPillVariant.allCases.enumerated()), id: \.offset) { _, variant in
                HStack {
                    Spacer()
                    OpenCodeActionPill(variant: variant, enabled: enabled, action: {})
                    Spacer()
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ActionPillGalleryView()
        .padding()
        .openCodeTheme()
}
