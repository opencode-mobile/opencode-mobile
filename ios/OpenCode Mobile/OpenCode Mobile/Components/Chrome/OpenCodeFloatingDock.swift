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
            .openCodeFloatingChrome(cornerRadius: openCodeFloatingDockCornerRadius)
            .padding(.horizontal, 16)
            .padding(.bottom, 4)
    }
}
