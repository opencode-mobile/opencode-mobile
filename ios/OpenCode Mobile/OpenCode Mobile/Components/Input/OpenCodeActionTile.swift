import SwiftUI

/// Semantic tone for the corner badge on an action tile.
enum OpenCodeActionTileBadgeTone: Equatable {
    case success
    case warning
    case error
    case neutral
}

/// Corner status badge for `OpenCodeActionTile`: a dot when `count` is nil,
/// otherwise a small count pill. Tone conveys health, never raw colour.
struct OpenCodeActionTileBadge: Equatable {
    let tone: OpenCodeActionTileBadgeTone
    let count: Int?

    init(tone: OpenCodeActionTileBadgeTone, count: Int? = nil) {
        self.tone = tone
        self.count = count
    }
}

struct OpenCodeActionTile: View {
    let icon: OpenCodeIcon
    let label: String
    let accessibilityLabel: String
    let enabled: Bool
    let badge: OpenCodeActionTileBadge?
    let action: () -> Void

    init(
        icon: OpenCodeIcon,
        label: String,
        accessibilityLabel: String? = nil,
        enabled: Bool = true,
        badge: OpenCodeActionTileBadge? = nil,
        action: @escaping () -> Void
    ) {
        self.icon = icon
        self.label = label
        self.accessibilityLabel = accessibilityLabel ?? label
        self.enabled = enabled
        self.badge = badge
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
            .overlay(alignment: .topTrailing) {
                if let badge {
                    OpenCodeActionTileBadgeView(badge: badge)
                        .padding(Self.badgeInset)
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: Self.cornerRadius))
            .opacity(enabled ? 1 : 0.7)
        }
        .buttonStyle(OpenCodeActionTilePressStyle(enabled: enabled))
        .disabled(!enabled)
        .accessibilityLabel(accessibilityLabel)
        .openCodeKeyboardFocusable(.roundedRectangle(cornerRadius: Self.cornerRadius), enabled: enabled, activate: action)
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
    private static let badgeInset: CGFloat = 12
}

private struct OpenCodeActionTileBadgeView: View {
    let badge: OpenCodeActionTileBadge

    var body: some View {
        if let count = badge.count {
            Text(count > 99 ? "99+" : String(count))
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(OpenCodeColors.surfaceBase)
                .padding(.horizontal, 5)
                .frame(minWidth: Self.countHeight, minHeight: Self.countHeight)
                .background(Capsule().fill(color))
        } else {
            Circle()
                .fill(color)
                .frame(width: Self.dotSize, height: Self.dotSize)
        }
    }

    private var color: Color {
        switch badge.tone {
        case .success: OpenCodeColors.success
        case .warning: OpenCodeColors.warning
        case .error: OpenCodeColors.error
        case .neutral: OpenCodeColors.textWeak
        }
    }

    private static let dotSize: CGFloat = 10
    private static let countHeight: CGFloat = 18
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

#Preview("Badge") {
    HStack(spacing: 10) {
        OpenCodeActionTile(icon: .plug, label: "MCP servers", badge: OpenCodeActionTileBadge(tone: .success), action: {})
        OpenCodeActionTile(icon: .plug, label: "MCP servers", badge: OpenCodeActionTileBadge(tone: .error, count: 2), action: {})
    }
    .frame(width: 350)
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
