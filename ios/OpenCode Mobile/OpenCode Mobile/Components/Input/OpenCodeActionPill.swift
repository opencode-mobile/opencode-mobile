import SwiftUI

enum OpenCodeActionPillVariant: CaseIterable {
    case scrollToEnd
    /// A hidden permission dock waiting above the composer.
    case reviewPermission
    /// A collapsed question panel waiting above the composer.
    case answerQuestion
}

struct OpenCodeActionPill: View {
    let variant: OpenCodeActionPillVariant
    let enabled: Bool
    let action: () -> Void

    init(
        variant: OpenCodeActionPillVariant,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.variant = variant
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            labelContent
        }
        .buttonStyle(OpenCodeFloatingPillPressStyle())
        .openCodeFloatingPillChrome(enabled: enabled)
        .disabled(!enabled)
        .accessibilityLabel(accessibilityLabel)
    }

    private var labelContent: some View {
        HStack(spacing: Self.iconSpacing) {
            if let icon {
                OpenCodeIconImage(icon: icon, size: Self.iconSize)
            }
            Text(label)
                .font(OpenCodeTypography.button)
        }
        .foregroundStyle(labelColor)
        .padding(.horizontal, Self.paddingHorizontal)
        .padding(.vertical, Self.paddingVertical)
        .frame(minHeight: Self.minHeight)
        .contentShape(Capsule())
    }

    private var label: String {
        switch variant {
        case .scrollToEnd:
            "Scroll to end"
        case .reviewPermission:
            "Review permission request"
        case .answerQuestion:
            "Answer question"
        }
    }

    private var icon: OpenCodeIcon? {
        switch variant {
        case .scrollToEnd:
            .arrowDownward
        case .reviewPermission, .answerQuestion:
            .arrowUpward
        }
    }

    private var accessibilityLabel: String {
        label
    }

    private var labelColor: Color {
        enabled ? OpenCodeColors.textStrong : OpenCodeColors.textBase
    }

    private static let minHeight: CGFloat = 36
    private static let paddingHorizontal: CGFloat = 14
    private static let paddingVertical: CGFloat = 8
    private static let iconSize: CGFloat = 16
    private static let iconSpacing: CGFloat = 6
}

#Preview("Scroll to end") {
    OpenCodeActionPill(variant: .scrollToEnd, action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Disabled") {
    OpenCodeActionPill(variant: .scrollToEnd, enabled: false, action: {})
        .padding()
        .openCodeTheme()
}

#Preview("Dark") {
    OpenCodeActionPill(variant: .scrollToEnd, action: {})
        .padding()
        .openCodeTheme()
        .preferredColorScheme(.dark)
}
