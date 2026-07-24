import SwiftUI

private let openCodeFloatingDockCornerRadius: CGFloat = 26

struct OpenCodeFloatingDock<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .padding(.horizontal, 12)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: openCodeFloatingDockCornerRadius, style: .continuous))
            .modifier(OpenCodeFloatingDockChrome())
            .padding(.horizontal, 16)
            .padding(.bottom, 4)
    }
}

private struct OpenCodeFloatingDockChrome: ViewModifier {
    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: openCodeFloatingDockCornerRadius, style: .continuous)
        if #available(iOS 26, *) {
            content
                .background(OpenCodeColors.surfaceRaisedBase.opacity(0.92), in: shape)
                .glassEffect(.regular, in: .rect(cornerRadius: openCodeFloatingDockCornerRadius, style: .continuous))
        } else {
            content.background(.regularMaterial, in: shape)
        }
    }
}
