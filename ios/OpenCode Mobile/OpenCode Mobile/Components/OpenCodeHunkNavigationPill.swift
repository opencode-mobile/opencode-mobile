import SwiftUI

struct OpenCodeHunkNavigationPill: View {
    let previousEnabled: Bool
    let nextEnabled: Bool
    let contextEnabled: Bool
    let contextExpanded: Bool
    let onPrevious: () -> Void
    let onToggleContext: () -> Void
    let onNext: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            actionButton(
                icon: .arrowUpward,
                label: "Previous hunk",
                enabled: previousEnabled,
                action: onPrevious
            )
            actionButton(
                icon: contextExpanded ? .collapseContent : .expandContent,
                label: contextExpanded ? "Collapse context" : "Expand context",
                enabled: contextEnabled,
                action: onToggleContext
            )
            actionButton(
                icon: .arrowDownward,
                label: "Next hunk",
                enabled: nextEnabled,
                action: onNext
            )
        }
        .frame(width: Self.width)
        .frame(minHeight: Self.buttonSize)
        .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
        .overlay(Capsule().strokeBorder(OpenCodeColors.borderWeakerBase, lineWidth: 1))
        .clipShape(Capsule())
        .shadow(color: Color.black.opacity(0.12), radius: 4, y: 2)
    }

    private func actionButton(
        icon: OpenCodeIcon,
        label: String,
        enabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            OpenCodeIconImage(icon: icon)
                .frame(width: Self.iconSize, height: Self.iconSize)
                .foregroundStyle(enabled ? OpenCodeColors.iconStrongBase : OpenCodeColors.iconWeakBase)
                .frame(width: Self.buttonSize, height: Self.buttonSize)
                .contentShape(Rectangle())
        }
        .buttonStyle(OpenCodeHunkNavigationButtonStyle(enabled: enabled))
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    private static let width: CGFloat = 132
    private static let buttonSize: CGFloat = 44
    private static let iconSize: CGFloat = 20
}

private struct OpenCodeHunkNavigationButtonStyle: ButtonStyle {
    let enabled: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                enabled && configuration.isPressed
                    ? OpenCodeColors.surfaceRaisedBaseHover
                    : Color.clear
            )
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

#Preview("Collapsed Light") {
    OpenCodeHunkNavigationPill(
        previousEnabled: false,
        nextEnabled: true,
        contextEnabled: true,
        contextExpanded: false,
        onPrevious: {},
        onToggleContext: {},
        onNext: {}
    )
    .padding()
    .openCodeTheme()
}

#Preview("Expanded Dark") {
    OpenCodeHunkNavigationPill(
        previousEnabled: true,
        nextEnabled: false,
        contextEnabled: true,
        contextExpanded: true,
        onPrevious: {},
        onToggleContext: {},
        onNext: {}
    )
    .padding()
    .openCodeTheme()
    .preferredColorScheme(.dark)
}
