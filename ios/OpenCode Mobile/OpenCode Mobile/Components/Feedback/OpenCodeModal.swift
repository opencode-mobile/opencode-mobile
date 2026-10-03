import SwiftUI

private let modalCornerRadius: CGFloat = 24
private let modalMaxWidth: CGFloat = 360
private let modalPadding: CGFloat = 20

/// Centered card with a close button and custom content, for promos and short
/// walkthroughs. Same scrim and presentation as Dialog; scrim tap and close both
/// dismiss through `isPresented`.
struct OpenCodeModal<ModalContent: View>: ViewModifier {
    @Binding var isPresented: Bool
    @ViewBuilder let modalContent: () -> ModalContent

    func body(content: Content) -> some View {
        content.background {
            OpenCodeDialogOverlayHost(
                isPresented: isPresented,
                card: OpenCodeModalCard(onClose: { isPresented = false }, content: modalContent),
                onDismiss: { isPresented = false }
            )
        }
    }
}

private struct OpenCodeModalCard<ModalContent: View>: View {
    let onClose: () -> Void
    @ViewBuilder let content: () -> ModalContent

    var body: some View {
        content()
            .padding(modalPadding)
            .padding(.top, 28)
            .frame(maxWidth: modalMaxWidth)
            .overlay(alignment: .topTrailing) {
                OpenCodeButton(
                    variant: .iconCircle(.close, accessibilityLabel: "Close"),
                    contrast: .low,
                    action: onClose
                )
                .padding(10)
            }
            .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: modalCornerRadius))
            .shadow(color: .black.opacity(0.18), radius: 16, y: 8)
            .accessibilityAddTraits(.isModal)
    }
}

extension View {
    func openCodeModal<ModalContent: View>(
        isPresented: Binding<Bool>,
        @ViewBuilder content: @escaping () -> ModalContent
    ) -> some View {
        modifier(OpenCodeModal(isPresented: isPresented, modalContent: content))
    }
}

#Preview("Modal") {
    Color.clear
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .openCodeModal(isPresented: .constant(true)) {
            VStack(alignment: .leading, spacing: 12) {
                OpenCodeSkeletonIllustration(topic: .chipTimelineGlance)
                    .frame(height: 150)
                    .clipShape(RoundedRectangle(cornerRadius: 20))
                Text("See the work at a glance")
                    .font(OpenCodeTypography.title)
                    .foregroundStyle(OpenCodeColors.textStrong)
                OpenCodeButton(variant: .fullWidthText(title: "Turn on"), action: {})
            }
        }
        .openCodeTheme()
}
