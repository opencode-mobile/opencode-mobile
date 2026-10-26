import SwiftUI

struct SideQuestionPillGalleryView: View {
    @State private var log = "Tap a pill"

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Variants")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Floating /btw status above the composer. Pending is not interactive; ready opens the answer; failed retries, and its close forgets the failures.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            VStack(spacing: 12) {
                OpenCodeSideQuestionPill(variant: .pending(count: 1))
                OpenCodeSideQuestionPill(variant: .pending(count: 3))
                OpenCodeSideQuestionPill(variant: .answerReady(count: 1), action: { log = "Open answer" })
                OpenCodeSideQuestionPill(
                    variant: .failed(count: 2),
                    action: { log = "Retry" },
                    onDismiss: { log = "Dismiss" }
                )
            }
            .frame(maxWidth: .infinity)

            Text(log)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    SideQuestionPillGalleryView()
        .padding()
        .openCodeTheme()
}
