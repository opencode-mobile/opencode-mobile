import SwiftUI

enum OpenCodeTagSize {
    /// Standalone label, such as the onboarding "Unofficial" tag.
    case regular
    /// Inline metadata next to a title, such as a folder's "git" tag.
    case small
}

enum OpenCodeTagTone {
    /// Quiet surface label.
    case neutral
    /// Interactive tint, for a label that should stand out, such as "Recommended".
    case accent
    /// Waiting on the user, such as a paused goal.
    case warning
    /// Done, such as a met goal.
    case success
}

/// Static capsule label.
struct OpenCodeTag: View {
    let title: OpenCodeText
    let size: OpenCodeTagSize
    let tone: OpenCodeTagTone

    init(_ title: OpenCodeText, size: OpenCodeTagSize = .regular, tone: OpenCodeTagTone = .neutral) {
        precondition(!title.resolved.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.title = title
        self.size = size
        self.tone = tone
    }

    private var fill: Color {
        switch tone {
        case .neutral: OpenCodeColors.surfaceRaisedBase
        case .accent: OpenCodeColors.interactive.opacity(0.12)
        case .warning: OpenCodeColors.warning.opacity(0.14)
        case .success: OpenCodeColors.success.opacity(0.14)
        }
    }

    private func textColor(neutral: Color) -> Color {
        switch tone {
        case .neutral: neutral
        case .accent: OpenCodeColors.textInteractiveBase
        case .warning: OpenCodeColors.warning
        case .success: OpenCodeColors.success
        }
    }

    var body: some View {
        switch size {
        case .regular:
            Text(openCode: title)
                .font(OpenCodeTypography.caption)
                .fontWeight(.bold)
                .foregroundStyle(textColor(neutral: OpenCodeColors.textStrong))
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(fill, in: Capsule())
        case .small:
            Text(openCode: title)
                .font(OpenCodeTypography.caption)
                .fontWeight(.semibold)
                .foregroundStyle(textColor(neutral: OpenCodeColors.textWeak))
                .lineLimit(1)
                .fixedSize()
                .padding(.horizontal, 6)
                .padding(.vertical, 1)
                .background(fill, in: Capsule())
        }
    }
}

#Preview("Tag") {
    VStack(spacing: 12) {
        OpenCodeTag("Unofficial")
        OpenCodeTag("git", size: .small)
        OpenCodeTag("Recommended", tone: .accent)
        OpenCodeTag("Recommended", size: .small, tone: .accent)
        OpenCodeTag("Paused", size: .small, tone: .warning)
        OpenCodeTag("Met", size: .small, tone: .success)
    }
    .padding()
    .openCodeTheme()
}
