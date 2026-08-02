import SwiftUI

/// Shared floating glass / material chrome used by Floating Dock, Session
/// Composer, and composer-anchored suggestion overlays. Callers own insets,
/// outlines, and content padding.
struct OpenCodeFloatingChrome: ViewModifier {
    var cornerRadius: CGFloat

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if #available(iOS 26, *) {
            content
                .background(OpenCodeColors.surfaceRaisedBase.opacity(0.92), in: shape)
                .glassEffect(.regular, in: .rect(cornerRadius: cornerRadius, style: .continuous))
        } else {
            content.background(.regularMaterial, in: shape)
        }
    }
}

extension View {
    func openCodeFloatingChrome(cornerRadius: CGFloat) -> some View {
        modifier(OpenCodeFloatingChrome(cornerRadius: cornerRadius))
    }
}
