import SwiftUI

func openCodeNonBlank(_ message: String?, fallback: String) -> String {
    let trimmed = message?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if trimmed.isEmpty { return fallback }
    return message ?? fallback
}

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
    /// Full-page hosts center in the available height. Sheets pass false so the block stays with the content.
    var fillsAvailableSpace: Bool

    init(
        title: String,
        message: String,
        icon: OpenCodeIcon? = nil,
        fillsAvailableSpace: Bool = true,
        primaryAction: OpenCodeEmptyStateAction? = nil,
        secondaryAction: OpenCodeEmptyStateAction? = nil
    ) {
        precondition(!title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        precondition(!message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.title = title
        self.message = message
        self.icon = icon
        self.fillsAvailableSpace = fillsAvailableSpace
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
        .frame(maxWidth: .infinity, maxHeight: fillsAvailableSpace ? .infinity : nil)
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
