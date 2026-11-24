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
            // Same edge as the Session Composer, so both bottom docks read alike.
            .overlay {
                RoundedRectangle(cornerRadius: openCodeFloatingDockCornerRadius, style: .continuous)
                    .stroke(OpenCodeColors.sessionComposerEdge, lineWidth: 1)
                    .allowsHitTesting(false)
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 4)
    }
}
