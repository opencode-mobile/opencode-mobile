import SwiftUI

/// Centered mid-well indeterminate status: neutral ring + title + subtitle.
struct OpenCodeStatusStack: View {
    var title: String
    var subtitle: String
    var accessibilityIdentifier: String?

    init(title: String, subtitle: String, accessibilityIdentifier: String? = nil) {
        precondition(!title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        precondition(!subtitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.title = title
        self.subtitle = subtitle
        self.accessibilityIdentifier = accessibilityIdentifier
    }

    var body: some View {
        VStack(spacing: 14) {
            ProgressView()
                .controlSize(.regular)
                .tint(OpenCodeColors.textBase)
                .scaleEffect(32.0 / 20.0)
                .frame(width: 32, height: 32)
                .accessibilityLabel("\(title). \(subtitle)")
                .modifier(OptionalAccessibilityIdentifier(accessibilityIdentifier))

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
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
