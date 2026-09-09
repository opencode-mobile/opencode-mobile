import SwiftUI

private enum GalleryButtonVariant: String, CaseIterable, Identifiable {
    case fullWidth
    case compact
    case icon
    case circle

    var id: String { rawValue }

    var label: String {
        switch self {
        case .fullWidth: "Full width"
        case .compact: "Compact"
        case .icon: "Icon"
        case .circle: "Circle"
        }
    }
}

struct ButtonGalleryView: View {
    @State private var selectedVariant: GalleryButtonVariant = .fullWidth
    @State private var selectedSize: OpenCodeButtonSize = .medium
    @State private var contrast: OpenCodeButtonContrast = .high
    @State private var enabled = true

    private var variant: OpenCodeButtonVariant {
        switch selectedVariant {
        case .fullWidth:
            .fullWidthText(title: "Run")
        case .compact:
            .compactText(title: "Build")
        case .icon:
            .iconSquare(.arrowUpward, accessibilityLabel: "Send")
        case .circle:
            .iconCircle(.arrowUpward, accessibilityLabel: "Send")
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Button")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    ForEach(GalleryButtonVariant.allCases) { variant in
                        OpenCodeChoiceBadge(
                            label: variant.label,
                            selected: selectedVariant == variant,
                            action: { selectedVariant = variant }
                        )
                    }
                }
            }

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(
                    label: "Enabled",
                    selected: enabled,
                    action: { enabled.toggle() }
                )
            }

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(
                    label: "High contrast",
                    selected: contrast == .high,
                    action: { contrast = .high }
                )
                OpenCodeChoiceBadge(
                    label: "Low contrast",
                    selected: contrast == .low,
                    action: { contrast = .low }
                )
                OpenCodeChoiceBadge(
                    label: "Accent",
                    selected: contrast == .accent,
                    action: { contrast = .accent }
                )
            }

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(
                    label: "Large",
                    selected: selectedSize == .large,
                    action: { selectedSize = .large }
                )
                OpenCodeChoiceBadge(
                    label: "Medium",
                    selected: selectedSize == .medium,
                    action: { selectedSize = .medium }
                )
            }

            OpenCodeButton(variant: variant, size: selectedSize, enabled: enabled, contrast: contrast, action: {})
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ButtonGalleryView()
        .padding()
        .openCodeTheme()
}
