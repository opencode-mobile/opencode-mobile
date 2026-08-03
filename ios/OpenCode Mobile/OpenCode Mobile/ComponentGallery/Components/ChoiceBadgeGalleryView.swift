import SwiftUI

struct ChoiceBadgeGalleryView: View {
    @State private var selection = "Alpha"
    @State private var glassSelection = "Build"

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Standard")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Tap a badge to select it.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                ForEach(["Alpha", "Beta", "Gamma"], id: \.self) { option in
                    OpenCodeChoiceBadge(
                        label: option,
                        selected: selection == option,
                        action: { selection = option }
                    )
                }
            }

            Text("Glass")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Composer picker chrome (home dock glass language).")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            let glassChips = HStack(spacing: 8) {
                ForEach(["Grok 4.5", "Build", "Default"], id: \.self) { option in
                    OpenCodeChoiceBadge(
                        label: option,
                        selected: glassSelection == option,
                        variant: .glass,
                        action: { glassSelection = option }
                    )
                }
            }
            if #available(iOS 26, *) {
                GlassEffectContainer(spacing: 8) {
                    glassChips
                }
            } else {
                glassChips
            }

            Text("Disabled")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Unavailable", selected: false, enabled: false, action: {})
                OpenCodeChoiceBadge(
                    label: "Glass unavailable",
                    selected: true,
                    enabled: false,
                    variant: .glass,
                    action: {}
                )
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
