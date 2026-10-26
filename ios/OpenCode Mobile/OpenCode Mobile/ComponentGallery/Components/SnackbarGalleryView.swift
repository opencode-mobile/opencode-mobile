import SwiftUI

struct SnackbarGalleryView: View {
    @State private var message: OpenCodeSnackbarMessage?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Snackbar")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Fire a one-shot snackbar. It auto-dismisses after a few seconds.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                OpenCodeButton(
                    variant: .compactText(title: "Default"),
                    action: {
                        message = OpenCodeSnackbarMessage(text: "Session compacted.", variant: .default)
                    }
                )
                OpenCodeButton(
                    variant: .compactText(title: "Error"),
                    action: {
                        message = OpenCodeSnackbarMessage(
                            text: "Couldn't compact session context.",
                            variant: .error
                        )
                    }
                )
                OpenCodeButton(
                    variant: .compactText(title: "Action"),
                    action: {
                        message = OpenCodeSnackbarMessage(
                            text: "/btw failed: “What did we change so far?”",
                            variant: .error,
                            actionLabel: "Retry"
                        )
                    }
                )
            }

            ZStack(alignment: .bottom) {
                RoundedRectangle(cornerRadius: 8)
                    .fill(OpenCodeColors.surfaceBase)
                    .frame(maxWidth: .infinity)
                    .frame(height: 160)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
                    )

                OpenCodeSnackbarHost(
                    message: message,
                    onDismissed: { message = nil }
                )
                .frame(height: 160)
            }
        }
    }
}

#Preview {
    SnackbarGalleryView()
        .padding()
        .openCodeTheme()
}
