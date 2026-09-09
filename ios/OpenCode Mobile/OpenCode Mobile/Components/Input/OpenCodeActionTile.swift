import SwiftUI

struct OpenCodeActionTile: View {
    let icon: OpenCodeIcon
    let label: String
    let accessibilityLabel: String
    let enabled: Bool
    let action: () -> Void

    init(
        icon: OpenCodeIcon,
        label: String,
        accessibilityLabel: String? = nil,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.icon = icon
        self.label = label
        self.accessibilityLabel = accessibilityLabel ?? label
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: Self.contentGap) {
                OpenCodeIconImage(icon: icon, size: Self.iconSize)
                    .foregroundStyle(iconColor)
                Text(label)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(labelColor)
                    .lineLimit(1)
            }
            .padding(.top, Self.paddingTop)
            .padding(.horizontal, Self.paddingHorizontal)
            .padding(.bottom, Self.paddingBottom)
            .frame(maxWidth: .infinity, minHeight: Self.minHeight, alignment: .topLeading)
            .contentShape(RoundedRectangle(cornerRadius: Self.cornerRadius))
            .opacity(enabled ? 1 : 0.7)
        }
        .buttonStyle(OpenCodeActionTilePressStyle(enabled: enabled))
        .disabled(!enabled)
        .accessibilityLabel(accessibilityLabel)
    }

    private var iconColor: Color {
        enabled ? OpenCodeColors.iconStrongBase : OpenCodeColors.iconBase
    }

    private var labelColor: Color {
        enabled ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker
    }

    private static let cornerRadius: CGFloat = 14
    private static let minHeight: CGFloat = 88
    private static let paddingTop: CGFloat = 18
    private static let paddingHorizontal: CGFloat = 14
    private static let paddingBottom: CGFloat = 16
    private static let iconSize: CGFloat = 24
    private static let contentGap: CGFloat = 12
}

private struct OpenCodeActionTilePressStyle: ButtonStyle {
    let enabled: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                RoundedRectangle(cornerRadius: 14)
                    .fill(
                        enabled && configuration.isPressed
                            ? OpenCodeColors.surfaceRaisedBaseHover
                            : OpenCodeColors.surfaceRaisedBase
                    )
            }
            .scaleEffect(configuration.isPressed && enabled ? 0.97 : 1)
            .animation(.spring(response: 0.26, dampingFraction: 0.62), value: configuration.isPressed)
    }
}

#Preview("Enabled") {
    OpenCodeActionTile(icon: .add, label: "New", action: {})
        .frame(width: 170)
        .padding()
        .openCodeTheme()
}

#Preview("Disabled") {
    OpenCodeActionTile(icon: .add, label: "New", enabled: false, action: {})
        .frame(width: 170)
        .padding()
        .openCodeTheme()
}

#Preview("Dark") {
    OpenCodeActionTile(icon: .add, label: "New", action: {})
        .frame(width: 170)
        .padding()
        .openCodeTheme()
        .preferredColorScheme(.dark)
}
