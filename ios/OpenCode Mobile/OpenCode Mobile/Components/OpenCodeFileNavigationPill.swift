import SwiftUI

enum FileNavigationPillPreviewCase: CaseIterable {
    case firstLight
    case middleDark
    case lastLight
    case zeroDark
    case refreshLight

    private var configuration: (
        additions: Int,
        deletions: Int,
        previousEnabled: Bool,
        nextEnabled: Bool,
        enabled: Bool,
        colorScheme: ColorScheme
    ) {
        switch self {
        case .firstLight: (12, 3, false, true, true, .light)
        case .middleDark: (12, 3, true, true, true, .dark)
        case .lastLight: (1, 2, true, false, true, .light)
        case .zeroDark: (0, 0, true, true, true, .dark)
        case .refreshLight: (12, 0, true, true, false, .light)
        }
    }

    var additions: Int { configuration.additions }
    var deletions: Int { configuration.deletions }
    var previousEnabled: Bool { configuration.previousEnabled }
    var nextEnabled: Bool { configuration.nextEnabled }
    var enabled: Bool { configuration.enabled }
    var colorScheme: ColorScheme { configuration.colorScheme }
}

struct OpenCodeFileNavigationPill: View {
    let additions: Int
    let deletions: Int
    let previousEnabled: Bool
    let nextEnabled: Bool
    let enabled: Bool
    let onPrevious: () -> Void
    let onNext: () -> Void

    init(
        additions: Int,
        deletions: Int,
        previousEnabled: Bool,
        nextEnabled: Bool,
        enabled: Bool = true,
        onPrevious: @escaping () -> Void,
        onNext: @escaping () -> Void
    ) {
        precondition(additions >= 0, "File Navigation Pill requires nonnegative additions")
        precondition(deletions >= 0, "File Navigation Pill requires nonnegative deletions")
        self.additions = additions
        self.deletions = deletions
        self.previousEnabled = previousEnabled
        self.nextEnabled = nextEnabled
        self.enabled = enabled
        self.onPrevious = onPrevious
        self.onNext = onNext
    }

    var body: some View {
        HStack(spacing: 0) {
            navigationButton(
                icon: .chevronRight,
                label: "Previous file",
                available: previousEnabled,
                mirrored: true,
                action: onPrevious
            )

            Spacer(minLength: 0)

            summaryText
                .font(OpenCodeTypography.button)
                .padding(.horizontal, Self.summaryPaddingHorizontal)
                .accessibilityLabel(Self.accessibilityLabel(additions: additions, deletions: deletions))

            Spacer(minLength: 0)

            navigationButton(
                icon: .chevronRight,
                label: "Next file",
                available: nextEnabled,
                mirrored: false,
                action: onNext
            )
        }
        .frame(width: Self.width)
        .frame(minHeight: Self.minHeight)
        .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
        .overlay(Capsule().strokeBorder(OpenCodeColors.borderWeakerBase, lineWidth: 1))
        .clipShape(Capsule())
        .shadow(color: Color.black.opacity(0.12), radius: 4, y: 2)
    }

    static func visibleSummary(additions: Int, deletions: Int) -> String {
        "+\(additions) −\(deletions)"
    }

    static func accessibilityLabel(additions: Int, deletions: Int) -> String {
        "\(additions) \(additions == 1 ? "addition" : "additions"), " +
            "\(deletions) \(deletions == 1 ? "deletion" : "deletions")"
    }

    private var summaryText: Text {
        Text("+\(additions)")
            .foregroundColor(OpenCodeColors.diffAdd) +
            Text(" ") +
            Text("−\(deletions)")
            .foregroundColor(OpenCodeColors.diffDelete)
    }

    private func navigationButton(
        icon: OpenCodeIcon,
        label: String,
        available: Bool,
        mirrored: Bool,
        action: @escaping () -> Void
    ) -> some View {
        let actionEnabled = enabled && available
        return Button(action: action) {
            OpenCodeIconImage(icon: icon)
                .scaleEffect(x: mirrored ? -1 : 1, y: 1)
                .frame(width: Self.iconSize, height: Self.iconSize)
                .foregroundStyle(actionEnabled ? OpenCodeColors.iconStrongBase : OpenCodeColors.iconWeakBase)
                .frame(width: Self.iconButtonSize, height: Self.iconButtonSize)
                .contentShape(Rectangle())
        }
        .buttonStyle(OpenCodeFileNavigationButtonStyle(enabled: actionEnabled))
        .disabled(!actionEnabled)
        .accessibilityLabel(label)
    }

    private static let width: CGFloat = 200
    private static let minHeight: CGFloat = 44
    private static let iconButtonSize: CGFloat = 44
    private static let iconSize: CGFloat = 20
    private static let summaryPaddingHorizontal: CGFloat = 12
}

private struct OpenCodeFileNavigationButtonStyle: ButtonStyle {
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

private struct FileNavigationPillPreview: View {
    let previewCase: FileNavigationPillPreviewCase

    var body: some View {
        OpenCodeFileNavigationPill(
            additions: previewCase.additions,
            deletions: previewCase.deletions,
            previousEnabled: previewCase.previousEnabled,
            nextEnabled: previewCase.nextEnabled,
            enabled: previewCase.enabled,
            onPrevious: {},
            onNext: {}
        )
        .padding()
        .openCodeTheme()
        .preferredColorScheme(previewCase.colorScheme)
    }
}

#Preview("First Light") { FileNavigationPillPreview(previewCase: .firstLight) }
#Preview("Middle Dark") { FileNavigationPillPreview(previewCase: .middleDark) }
#Preview("Last Light") { FileNavigationPillPreview(previewCase: .lastLight) }
#Preview("Zero Dark") { FileNavigationPillPreview(previewCase: .zeroDark) }
#Preview("Refresh Light") { FileNavigationPillPreview(previewCase: .refreshLight) }
