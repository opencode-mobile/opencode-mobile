import SwiftUI

/// Small capsule on a row that shows or hides the rows nested under it, such as
/// "3 worktrees ⌄" on a project. It is its own tap target inside the row; the
/// chevron turns over while expanded. Disabled keeps it visible but inert.
struct OpenCodeExpandPill: View {
    let title: String
    let isExpanded: Bool
    let enabled: Bool
    let accessibilityLabel: String
    let action: () -> Void

    init(
        title: String,
        isExpanded: Bool,
        enabled: Bool = true,
        accessibilityLabel: String,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.isExpanded = isExpanded
        self.enabled = enabled
        self.accessibilityLabel = accessibilityLabel
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: Self.iconSpacing) {
                Text(title)
                    .font(OpenCodeTypography.caption.weight(.semibold))
                    .lineLimit(1)
                OpenCodeIconImage(icon: .keyboardArrowDown, size: Self.iconSize)
                    .rotationEffect(.degrees(isExpanded ? 180 : 0))
                    .animation(.easeInOut(duration: 0.2), value: isExpanded)
            }
            .foregroundStyle(enabled ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker)
            .padding(.horizontal, Self.paddingHorizontal)
            .padding(.vertical, Self.paddingVertical)
            .background(OpenCodeColors.textStrong.opacity(enabled ? 0.10 : 0.05), in: Capsule())
            // A taller hit area than the capsule itself.
            .padding(.vertical, Self.hitSlop)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .fixedSize()
        .accessibilityLabel(accessibilityLabel)
        .accessibilityValue(title)
    }

    private static let iconSize: CGFloat = 14
    private static let iconSpacing: CGFloat = 4
    private static let paddingHorizontal: CGFloat = 10
    private static let paddingVertical: CGFloat = 4
    private static let hitSlop: CGFloat = 8
}

#if DEBUG
#Preview("Expand Pill") {
    VStack(spacing: 12) {
        OpenCodeExpandPill(title: "3 worktrees", isExpanded: false, accessibilityLabel: "Show worktrees", action: {})
        OpenCodeExpandPill(title: "3 worktrees", isExpanded: true, accessibilityLabel: "Hide worktrees", action: {})
        OpenCodeExpandPill(
            title: "3 worktrees",
            isExpanded: false,
            enabled: false,
            accessibilityLabel: "Show worktrees",
            action: {}
        )
    }
    .padding()
    .openCodeTheme()
}
#endif
