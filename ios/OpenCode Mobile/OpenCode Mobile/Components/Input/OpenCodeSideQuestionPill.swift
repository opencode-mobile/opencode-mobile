import SwiftUI

/// Where `/btw` side questions stand, shown above the composer.
enum OpenCodeSideQuestionPillVariant: Equatable {
    /// Requests still running. Not interactive; more than one shows `×N`.
    case pending(count: Int)
    /// Answers waiting to be read; tapping opens the oldest.
    case answerReady(count: Int)
    /// Requests that failed; tapping asks them again and the trailing close forgets them.
    case failed(count: Int)
}

/// Floating status pill for `/btw` side questions, sharing the floating pill chrome.
struct OpenCodeSideQuestionPill: View {
    let variant: OpenCodeSideQuestionPillVariant
    let action: () -> Void
    let onDismiss: (() -> Void)?

    init(
        variant: OpenCodeSideQuestionPillVariant,
        action: @escaping () -> Void = {},
        onDismiss: (() -> Void)? = nil
    ) {
        self.variant = variant
        self.action = action
        self.onDismiss = onDismiss
    }

    var body: some View {
        switch variant {
        case .pending:
            content
                .openCodeFloatingPillChrome(enabled: true)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(accessibilityLabel)
                .accessibilityAddTraits(.updatesFrequently)
        case .answerReady, .failed:
            HStack(spacing: 0) {
                Button(action: action) {
                    content
                }
                .buttonStyle(OpenCodeFloatingPillPressStyle())
                .accessibilityLabel(accessibilityLabel)
                if case .failed = variant, let onDismiss {
                    Button(action: onDismiss) {
                        OpenCodeIconImage(icon: .close, size: Self.iconSize)
                            .foregroundStyle(OpenCodeColors.iconBase)
                            .frame(width: Self.minHeight, height: Self.minHeight)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(OpenCodeFloatingPillPressStyle())
                    .padding(.leading, -Self.paddingHorizontal / 2)
                    .accessibilityLabel("Dismiss failed side questions")
                }
            }
            .openCodeFloatingPillChrome(enabled: true)
        }
    }

    private var content: some View {
        HStack(spacing: Self.iconSpacing) {
            switch variant {
            case .pending:
                ProgressView()
                    .controlSize(.small)
                    .tint(OpenCodeColors.textBase)
            case .answerReady:
                OpenCodeIconImage(icon: .check, size: Self.iconSize)
            case .failed:
                EmptyView()
            }
            Text(label)
                .font(OpenCodeTypography.button)
                .lineLimit(1)
        }
        .foregroundStyle(labelColor)
        .padding(.horizontal, Self.paddingHorizontal)
        .padding(.vertical, Self.paddingVertical)
        .frame(minHeight: Self.minHeight)
        .contentShape(Capsule())
    }

    private var label: String {
        switch variant {
        case let .pending(count):
            "/btw" + Self.countSuffix(count)
        case let .answerReady(count):
            "/btw answer ready" + Self.countSuffix(count)
        case let .failed(count):
            "/btw failed" + Self.countSuffix(count) + " · Retry"
        }
    }

    private var accessibilityLabel: String {
        switch variant {
        case let .pending(count):
            count > 1 ? "\(count) side questions running" : "Side question running"
        case let .answerReady(count):
            count > 1 ? "\(count) side question answers ready. Open" : "Side question answer ready. Open"
        case let .failed(count):
            count > 1 ? "\(count) side questions failed. Retry" : "Side question failed. Retry"
        }
    }

    private var labelColor: Color {
        switch variant {
        case .pending: OpenCodeColors.textBase
        case .answerReady: OpenCodeColors.textStrong
        case .failed: OpenCodeColors.error
        }
    }

    private static func countSuffix(_ count: Int) -> String {
        count > 1 ? " ×\(count)" : ""
    }

    private static let minHeight: CGFloat = 36
    private static let paddingHorizontal: CGFloat = 14
    private static let paddingVertical: CGFloat = 8
    private static let iconSize: CGFloat = 16
    private static let iconSpacing: CGFloat = 6
}

#if DEBUG
#Preview("Variants") {
    VStack(spacing: 12) {
        OpenCodeSideQuestionPill(variant: .pending(count: 1))
        OpenCodeSideQuestionPill(variant: .pending(count: 3))
        OpenCodeSideQuestionPill(variant: .answerReady(count: 1))
        OpenCodeSideQuestionPill(variant: .failed(count: 2), onDismiss: {})
    }
    .padding()
    .openCodeTheme()
}

#Preview("Dark") {
    VStack(spacing: 12) {
        OpenCodeSideQuestionPill(variant: .pending(count: 2))
        OpenCodeSideQuestionPill(variant: .answerReady(count: 2))
        OpenCodeSideQuestionPill(variant: .failed(count: 1), onDismiss: {})
    }
    .padding()
    .openCodeTheme()
    .preferredColorScheme(.dark)
}
#endif
