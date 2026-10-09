import SwiftUI

enum OpenCodeStatusStackStyle {
    /// Neutral ring with a title and subtitle.
    case labeled
    /// Ring only, for inline and overlay loading.
    case spinner
}

/// Centered mid-well indeterminate status: neutral ring + title + subtitle, or a ring alone.
struct OpenCodeStatusStack: View {
    var title: String
    var subtitle: String
    var style: OpenCodeStatusStackStyle
    var accessibilityIdentifier: String?

    init(title: String, subtitle: String, accessibilityIdentifier: String? = nil) {
        precondition(!title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        precondition(!subtitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.title = title
        self.subtitle = subtitle
        self.style = .labeled
        self.accessibilityIdentifier = accessibilityIdentifier
    }

    init(spinner accessibilityLabel: String = "Loading", accessibilityIdentifier: String? = nil) {
        precondition(!accessibilityLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.title = accessibilityLabel
        self.subtitle = ""
        self.style = .spinner
        self.accessibilityIdentifier = accessibilityIdentifier
    }

    var body: some View {
        VStack(spacing: 14) {
            ProgressView()
                .controlSize(.regular)
                .tint(OpenCodeColors.textBase)
                .scaleEffect(32.0 / 20.0)
                .frame(width: 32, height: 32)
                .accessibilityLabel(accessibilityLabel)
                .modifier(OptionalAccessibilityIdentifier(accessibilityIdentifier))

            if style == .labeled {
                Text(title)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .multilineTextAlignment(.center)
                    .accessibilityHidden(true)

                Text(subtitle)
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
            "\(title). \(subtitle)"
        case .spinner:
            title
        }
    }
}

private struct OptionalAccessibilityIdentifier: ViewModifier {
    let id: String?

    init(_ id: String?) {
        self.id = id
    }

    func body(content: Content) -> some View {
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
