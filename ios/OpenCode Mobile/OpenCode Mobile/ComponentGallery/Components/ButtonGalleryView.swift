import SwiftUI

private enum GalleryButtonVariant: String, CaseIterable, Identifiable {
    case fullWidth
    case compact
    case icon

    var id: String { rawValue }

    var label: String {
        switch self {
        case .fullWidth: "Full width"
        case .compact: "Compact"
        case .icon: "Icon"
        }
    }
}

struct ButtonGalleryView: View {
    @State private var selectedVariant: GalleryButtonVariant = .fullWidth
    @State private var enabled = true

    private var variant: OpenCodeButtonVariant {
        switch selectedVariant {
        case .fullWidth:
            .fullWidthText(title: "Run")
        case .compact:
            .compactText(title: "Build")
        case .icon:
            .iconSquare(systemName: "arrow.up", accessibilityLabel: "Send")
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Button")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textPrimary)
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

            OpenCodeButton(variant: variant, enabled: enabled, action: {})
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ButtonGalleryView()
        .padding()
        .openCodeTheme()
}
