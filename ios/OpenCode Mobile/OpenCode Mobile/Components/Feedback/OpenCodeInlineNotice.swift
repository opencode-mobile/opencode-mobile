import SwiftUI

enum OpenCodeInlineNoticeTone {
    case neutral
    case error
}

enum OpenCodeInlineNoticeVariant {
    /// One line of type, no fill.
    case plain
    /// Padded tonal fill for a banner.
    case banner
}

struct OpenCodeInlineNoticeAction {
    let title: OpenCodeText
    let action: () -> Void

    init(title: OpenCodeText, action: @escaping () -> Void) {
        precondition(!title.resolved.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.title = title
        self.action = action
    }
}

/// Tonal inline message. An optional trailing action sits on the same row.
struct OpenCodeInlineNotice: View {
    let message: OpenCodeText
    var tone: OpenCodeInlineNoticeTone
    var variant: OpenCodeInlineNoticeVariant
    var action: OpenCodeInlineNoticeAction?

    init(
        message: OpenCodeText,
        tone: OpenCodeInlineNoticeTone = .neutral,
        variant: OpenCodeInlineNoticeVariant = .plain,
        action: OpenCodeInlineNoticeAction? = nil
    ) {
        precondition(!message.resolved.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.message = message
        self.tone = tone
        self.variant = variant
        self.action = action
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Text(openCode: message)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(foreground)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let action {
                OpenCodeButton(
                    variant: .compactText(title: action.title),
                    contrast: .low,
                    action: action.action
                )
            }
        }
        .padding(variant == .banner ? 12 : 0)
        .background {
            if variant == .banner {
                RoundedRectangle(cornerRadius: Self.bannerRadius, style: .continuous)
                    .fill(bannerFill)
            }
        }
        .accessibilityElement(children: .contain)
    }

    private var foreground: Color {
        switch tone {
        case .neutral:
            OpenCodeColors.textBase
        case .error:
            OpenCodeColors.error
        }
    }

    private var bannerFill: Color {
        switch tone {
        case .neutral:
            OpenCodeColors.surfaceRaisedBase
        case .error:
            OpenCodeColors.error.opacity(0.1)
        }
    }

    private static let bannerRadius: CGFloat = 8
}

#Preview("Inline Notice") {
    VStack(alignment: .leading, spacing: 12) {
        OpenCodeInlineNotice(message: "Selected model is unavailable.")
        OpenCodeInlineNotice(message: "Couldn't prepare diagnostic log for sharing.", tone: .error)
        OpenCodeInlineNotice(
            message: "12 entries dropped under writer pressure.",
            tone: .error,
            variant: .banner
        )
        OpenCodeInlineNotice(
            message: "Refresh failed: offline",
            action: OpenCodeInlineNoticeAction(title: "Retry", action: {})
        )
    }
    .padding()
    .openCodeTheme()
}
