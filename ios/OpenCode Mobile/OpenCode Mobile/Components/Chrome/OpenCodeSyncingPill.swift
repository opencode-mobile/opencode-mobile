import SwiftUI

/// Non-interactive floating status pill for quiet cache→network sync.
struct OpenCodeSyncingPill: View {
    let label: String

    init(label: String = "Syncing…") {
        self.label = label
    }

    var body: some View {
        HStack(spacing: Self.iconSpacing) {
            ProgressView()
                .controlSize(.small)
                .tint(OpenCodeColors.textBase)
            Text(label)
                .font(OpenCodeTypography.button)
        }
        .foregroundStyle(OpenCodeColors.textBase)
        .padding(.horizontal, Self.paddingHorizontal)
        .padding(.vertical, Self.paddingVertical)
        .frame(minHeight: Self.minHeight)
        .openCodeFloatingPillChrome(enabled: true)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityAddTraits(.updatesFrequently)
    }

    private static let minHeight: CGFloat = 36
    private static let paddingHorizontal: CGFloat = 14
    private static let paddingVertical: CGFloat = 8
    private static let iconSpacing: CGFloat = 6
}

#if DEBUG
#Preview("Syncing") {
    OpenCodeSyncingPill()
        .padding()
        .openCodeTheme()
}

#Preview("Dark") {
    OpenCodeSyncingPill()
        .padding()
        .openCodeTheme()
        .preferredColorScheme(.dark)
}
#endif
