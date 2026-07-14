import SwiftUI

struct ReviewChangesPillGalleryView: View {
    @State private var enabled = true
    @State private var changedFileCount = 1

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Review singular, plural, and disabled latest-turn states.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "1 file", selected: changedFileCount == 1) {
                    changedFileCount = 1
                }
                OpenCodeChoiceBadge(label: "4 files", selected: changedFileCount == 4) {
                    changedFileCount = 4
                }
                OpenCodeChoiceBadge(label: "Enabled", selected: enabled) {
                    enabled.toggle()
                }
            }

            HStack {
                Spacer()
                OpenCodeReviewChangesPill(
                    variant: .latestTurn,
                    changedFileCount: changedFileCount,
                    enabled: enabled,
                    action: {}
                )
                Spacer()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ReviewChangesPillGalleryView()
        .padding()
        .openCodeTheme()
}
