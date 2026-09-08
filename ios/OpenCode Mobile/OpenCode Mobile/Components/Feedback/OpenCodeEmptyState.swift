import SwiftUI

struct OpenCodeEmptyState: View {
    var title: String
    var message: String
    var icon: OpenCodeIcon?

    init(title: String, message: String, icon: OpenCodeIcon? = nil) {
        precondition(!title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        precondition(!message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.title = title
        self.message = message
        self.icon = icon
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
