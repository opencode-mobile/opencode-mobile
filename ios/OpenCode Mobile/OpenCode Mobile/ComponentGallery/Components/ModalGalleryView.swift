import SwiftUI

struct ModalGalleryView: View {
    @State private var isPresented = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Modal")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Centered card with a close button and custom content. Scrim tap and close dismiss.")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
            OpenCodeButton(
                variant: .fullWidthText(title: "Show modal"),
                action: { isPresented = true }
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .openCodeModal(isPresented: $isPresented) {
            VStack(alignment: .leading, spacing: 12) {
                OpenCodeSkeletonIllustration(topic: .chipTimelineGlance)
                    .frame(maxWidth: .infinity)
                    .frame(height: 150)
                    .background(OpenCodeColors.surfaceRaisedBase)
                    .clipShape(RoundedRectangle(cornerRadius: 20))
                Text("See the work at a glance")
                    .font(OpenCodeTypography.title)
                    .foregroundStyle(OpenCodeColors.textStrong)
                Text("Tool calls show as chips under the sentence that explains them.")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
                OpenCodeButton(
                    variant: .fullWidthText(title: "Got it"),
                    action: { isPresented = false }
                )
            }
        }
    }
}

#Preview {
    ModalGalleryView()
        .padding()
        .openCodeTheme()
}
