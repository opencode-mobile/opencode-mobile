import SwiftUI

enum OpenCodeSessionTodosPillVariant {
    case liveProgress
}

enum SessionTodosPillPreviewCase: CaseIterable {
    case midProgressLight
    case midProgressDark
    case nearCompleteLight
    case noPreviewDark
    case disabledLight
    case disabledDark

    private var configuration: (
        completed: Int,
        total: Int,
        preview: String?,
        enabled: Bool,
        colorScheme: ColorScheme
    ) {
        switch self {
        case .midProgressLight: (2, 5, "Implement auth middleware", true, .light)
        case .midProgressDark: (2, 5, "Implement auth middleware", true, .dark)
        case .nearCompleteLight: (4, 5, "Write integration tests", true, .light)
        case .noPreviewDark: (1, 3, nil, true, .dark)
        case .disabledLight: (2, 5, "Implement auth middleware", false, .light)
        case .disabledDark: (0, 2, "Sketch API surface", false, .dark)
        }
    }

    var completedCount: Int { configuration.completed }
    var totalCount: Int { configuration.total }
    var activePreview: String? { configuration.preview }
    var enabled: Bool { configuration.enabled }
    var colorScheme: ColorScheme { configuration.colorScheme }
}

struct OpenCodeSessionTodosPill: View {
    let variant: OpenCodeSessionTodosPillVariant
    let completedCount: Int
    let totalCount: Int
    let activePreview: String?
    let enabled: Bool
    let action: () -> Void

    init(
        variant: OpenCodeSessionTodosPillVariant = .liveProgress,
        completedCount: Int,
        totalCount: Int,
        activePreview: String? = nil,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        precondition(totalCount > 0, "Session Todos Pill requires a positive total count")
        precondition(completedCount >= 0, "Session Todos Pill requires nonnegative completed count")
        precondition(completedCount <= totalCount, "Session Todos Pill completed count cannot exceed total")
        self.variant = variant
        self.completedCount = completedCount
        self.totalCount = totalCount
        self.activePreview = activePreview.flatMap { $0.isEmpty ? nil : $0 }
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: Self.contentSpacing) {
                Circle()
                    .fill(enabled ? OpenCodeColors.textInteractiveBase : OpenCodeColors.textBase)
                    .frame(width: Self.statusDotSize, height: Self.statusDotSize)
                    .overlay {
                        if enabled {
                            Circle()
                                .stroke(OpenCodeColors.textInteractiveBase.opacity(0.25), lineWidth: 3)
                                .frame(width: Self.statusDotSize + 4, height: Self.statusDotSize + 4)
                        }
                    }

                Text("\(completedCount)/\(totalCount)")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(enabled ? OpenCodeColors.textStrong : OpenCodeColors.textBase)
                    .monospacedDigit()

                if let activePreview {
                    Text(activePreview)
                        .font(OpenCodeTypography.button)
                        .foregroundStyle(enabled ? OpenCodeColors.textBase : OpenCodeColors.textBase.opacity(0.7))
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
            .padding(.horizontal, Self.paddingHorizontal)
            .padding(.vertical, Self.paddingVertical)
            .frame(minHeight: Self.minHeight)
            .frame(maxWidth: Self.maxWidth)
        }
        .buttonStyle(OpenCodeSessionTodosPillButtonStyle(enabled: enabled))
        .disabled(!enabled)
        .accessibilityLabel(Self.accessibilityLabel(
            completedCount: completedCount,
            totalCount: totalCount,
            activePreview: activePreview
        ))
    }

    static func progressLabel(completedCount: Int, totalCount: Int) -> String {
        "\(completedCount)/\(totalCount)"
    }

    static func accessibilityLabel(completedCount: Int, totalCount: Int, activePreview: String?) -> String {
        var parts = [
            "Session todos",
            "\(completedCount) of \(totalCount) completed",
        ]
        if let activePreview, !activePreview.isEmpty {
            parts.append(activePreview)
        }
        return parts.joined(separator: ", ")
    }

    private static let minHeight: CGFloat = 36
    private static let paddingHorizontal: CGFloat = 14
    private static let paddingVertical: CGFloat = 8
    private static let statusDotSize: CGFloat = 8
    private static let contentSpacing: CGFloat = 8
    private static let maxWidth: CGFloat = 320
}

private struct OpenCodeSessionTodosPillButtonStyle: ButtonStyle {
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

private struct SessionTodosPillPreview: View {
    let previewCase: SessionTodosPillPreviewCase

    var body: some View {
        OpenCodeSessionTodosPill(
            completedCount: previewCase.completedCount,
            totalCount: previewCase.totalCount,
            activePreview: previewCase.activePreview,
            enabled: previewCase.enabled,
            action: {}
        )
        .padding()
        .openCodeTheme()
        .preferredColorScheme(previewCase.colorScheme)
    }
}

#Preview("Mid Progress Light") {
    SessionTodosPillPreview(previewCase: .midProgressLight)
}

#Preview("Mid Progress Dark") {
    SessionTodosPillPreview(previewCase: .midProgressDark)
}

#Preview("Near Complete Light") {
    SessionTodosPillPreview(previewCase: .nearCompleteLight)
}

#Preview("No Preview Dark") {
    SessionTodosPillPreview(previewCase: .noPreviewDark)
}

#Preview("Disabled Light") {
    SessionTodosPillPreview(previewCase: .disabledLight)
}

#Preview("Disabled Dark") {
    SessionTodosPillPreview(previewCase: .disabledDark)
}
