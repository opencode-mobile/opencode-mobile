import SwiftUI

struct OpenCodeEmptyStateAction {
    let title: String
    let action: () -> Void
}

struct OpenCodeEmptyState: View {
    var title: String
    var message: String
    var icon: OpenCodeIcon?
    var primaryAction: OpenCodeEmptyStateAction?
    var secondaryAction: OpenCodeEmptyStateAction?

    init(
        title: String,
        message: String,
        icon: OpenCodeIcon? = nil,
        primaryAction: OpenCodeEmptyStateAction? = nil,
        secondaryAction: OpenCodeEmptyStateAction? = nil
    ) {
        precondition(!title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        precondition(!message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.title = title
        self.message = message
        self.icon = icon
        self.primaryAction = primaryAction
        self.secondaryAction = secondaryAction
    }

    var body: some View {
        VStack(spacing: 0) {
            if let icon {
                OpenCodeIconImage(icon: icon, size: 40)
                    .foregroundStyle(OpenCodeColors.textBase)
                Spacer().frame(height: 16)
            }
            Text(title)
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
                .multilineTextAlignment(.center)
            Spacer().frame(height: 12)
            Text(message)
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .multilineTextAlignment(.center)
            if primaryAction != nil || secondaryAction != nil {
                Spacer().frame(height: 16)
                if let primaryAction {
                    OpenCodeButton(variant: .fullWidthText(title: primaryAction.title), action: primaryAction.action)
                }
                if let secondaryAction {
                    Spacer().frame(height: 8)
                    OpenCodeButton(variant: .fullWidthText(title: secondaryAction.title), action: secondaryAction.action)
                }
            }
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview("Empty State") {
    OpenCodeEmptyState(
        title: "No projects yet",
        message: "Tap the Add project icon below to choose a folder on your server.",
        icon: .createNewFolder
    )
    .frame(height: 320)
    .openCodeTheme()
}
