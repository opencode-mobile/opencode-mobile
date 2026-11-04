import SwiftUI

public enum OpenCodeStatusStackStyle {
    /// Neutral ring with a title and subtitle.
    case labeled
    /// Ring only, for inline and overlay loading.
    case spinner
}

/// Centered mid-well indeterminate status: neutral ring + title + subtitle, or a ring alone.
public struct OpenCodeStatusStack: View {
    public var title: OpenCodeText
    public var subtitle: OpenCodeText
    public var style: OpenCodeStatusStackStyle
    public var accessibilityIdentifier: String?

    public init(title: OpenCodeText, subtitle: OpenCodeText, accessibilityIdentifier: String? = nil) {
        precondition(!title.resolved.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        precondition(!subtitle.resolved.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.title = title
        self.subtitle = subtitle
        self.style = .labeled
        self.accessibilityIdentifier = accessibilityIdentifier
    }

    public init(spinner accessibilityLabel: OpenCodeText = "Loading", accessibilityIdentifier: String? = nil) {
        precondition(!accessibilityLabel.resolved.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.title = accessibilityLabel
        self.subtitle = .empty
        self.style = .spinner
        self.accessibilityIdentifier = accessibilityIdentifier
    }

    public var body: some View {
        VStack(spacing: 14) {
            ProgressView()
                .controlSize(.regular)
                .tint(OpenCodeColors.textBase)
                .scaleEffect(32.0 / 20.0)
                .frame(width: 32, height: 32)
                .accessibilityLabel(accessibilityLabel)
                .modifier(OptionalAccessibilityIdentifier(accessibilityIdentifier))

            if style == .labeled {
                Text(openCode: title)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .multilineTextAlignment(.center)
                    .accessibilityHidden(true)

                Text(openCode: subtitle)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeaker)
                    .multilineTextAlignment(.center)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: style == .spinner ? nil : .infinity)
    }

    private var accessibilityLabel: String {
        switch style {
        case .labeled:
            "\(title.resolved). \(subtitle.resolved)"
        case .spinner:
            title.resolved
        }
    }
}

private struct OptionalAccessibilityIdentifier: ViewModifier {
    public let id: String?

    public init(_ id: String?) {
        self.id = id
    }

    public func body(content: Content) -> some View {
        if let id {
            content.accessibilityIdentifier(id)
        } else {
            content
        }
    }
}

#Preview("Status Stack — Home") {
    OpenCodeStatusStack(
        title: "Loading sessions",
        subtitle: "Checking server…"
    )
    .frame(height: 320)
    .openCodeTheme()
}

#Preview("Status Stack — Session") {
    OpenCodeStatusStack(
        title: "Loading messages",
        subtitle: "Fetching timeline…",
        accessibilityIdentifier: "sessionChatLoading"
    )
    .frame(height: 320)
    .openCodeTheme()
}
