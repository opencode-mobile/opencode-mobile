import SwiftUI

enum OpenCodeReviewChangesPillVariant {
    case latestTurn
}

enum ReviewChangesPillPreviewCase: CaseIterable {
    case singularLight
    case singularDark
    case pluralLight
    case pluralDark
    case disabledLight
    case disabledDark

    var changedFileCount: Int {
        switch self {
        case .singularLight, .singularDark: 1
        case .pluralLight, .pluralDark, .disabledLight, .disabledDark: 4
        }
    }

    var enabled: Bool {
        switch self {
        case .disabledLight, .disabledDark: false
        default: true
        }
    }

    var colorScheme: ColorScheme {
        switch self {
        case .singularDark, .pluralDark, .disabledDark: .dark
        default: .light
        }
    }
}

struct OpenCodeReviewChangesPill: View {
    let variant: OpenCodeReviewChangesPillVariant
    let changedFileCount: Int
    let enabled: Bool
    let action: () -> Void

    init(
        variant: OpenCodeReviewChangesPillVariant,
        changedFileCount: Int,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        precondition(changedFileCount > 0, "Review Changes Pill requires a positive file count")
        self.variant = variant
        self.changedFileCount = changedFileCount
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: Self.iconSpacing) {
                OpenCodeIconImage(icon: icon)
                    .font(.system(size: Self.iconSize, weight: .semibold))
                    .foregroundStyle(enabled ? OpenCodeColors.iconStrongBase : OpenCodeColors.textBase)
                Text(label)
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(enabled ? OpenCodeColors.textStrong : OpenCodeColors.textBase)
            }
            .padding(.horizontal, Self.paddingHorizontal)
            .padding(.vertical, Self.paddingVertical)
            .frame(minHeight: Self.minHeight)
        }
        .buttonStyle(OpenCodeReviewChangesPillButtonStyle(enabled: enabled))
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    private var label: String {
        switch variant {
        case .latestTurn: Self.label(changedFileCount: changedFileCount)
        }
    }

    private var icon: OpenCodeIcon {
        switch variant {
        case .latestTurn: .description
        }
    }

    static func label(changedFileCount: Int) -> String {
        "Review \(changedFileCount) changed \(changedFileCount == 1 ? "file" : "files")"
    }

    private static let minHeight: CGFloat = 36
    private static let paddingHorizontal: CGFloat = 14
    private static let paddingVertical: CGFloat = 8
    private static let iconSize: CGFloat = 16
    private static let iconSpacing: CGFloat = 6
}

private struct OpenCodeReviewChangesPillButtonStyle: ButtonStyle {
    let enabled: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(backgroundColor(isPressed: configuration.isPressed), in: Capsule())
            .overlay(Capsule().strokeBorder(OpenCodeColors.borderWeakerBase, lineWidth: 1))
            .shadow(color: enabled ? Color.black.opacity(0.12) : .clear, radius: 4, y: 2)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }

    private func backgroundColor(isPressed: Bool) -> Color {
        guard enabled else { return OpenCodeColors.surfaceRaisedBase }
        return isPressed ? OpenCodeColors.surfaceRaisedBaseHover : OpenCodeColors.surfaceRaisedBase
    }
}

private struct ReviewChangesPillPreview: View {
    let previewCase: ReviewChangesPillPreviewCase

    var body: some View {
        OpenCodeReviewChangesPill(
            variant: .latestTurn,
            changedFileCount: previewCase.changedFileCount,
            enabled: previewCase.enabled,
            action: {}
        )
        .padding()
        .openCodeTheme()
        .preferredColorScheme(previewCase.colorScheme)
    }
}

#Preview("Singular Light") {
    ReviewChangesPillPreview(previewCase: .singularLight)
}

#Preview("Singular Dark") {
    ReviewChangesPillPreview(previewCase: .singularDark)
}

#Preview("Plural Light") {
    ReviewChangesPillPreview(previewCase: .pluralLight)
}

#Preview("Plural Dark") {
    ReviewChangesPillPreview(previewCase: .pluralDark)
}

#Preview("Disabled Light") {
    ReviewChangesPillPreview(previewCase: .disabledLight)
}

#Preview("Disabled Dark") {
    ReviewChangesPillPreview(previewCase: .disabledDark)
}
