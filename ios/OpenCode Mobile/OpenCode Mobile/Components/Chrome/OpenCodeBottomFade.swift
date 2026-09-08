import SwiftUI

/// Non-interactive bottom fade into `background-base` for content under a floating dock.
struct OpenCodeBottomFade: View {
    /// Full fade height including any safe-area extension the caller wants covered.
    let height: CGFloat

    var body: some View {
        LinearGradient(
            colors: [
                OpenCodeColors.backgroundBase.opacity(0),
                OpenCodeColors.backgroundBase.opacity(0.7),
                OpenCodeColors.backgroundBase.opacity(0.7),
            ],
            startPoint: .top,
            endPoint: .bottom
        )
        .frame(maxWidth: .infinity)
        .frame(height: max(height, 0))
        .allowsHitTesting(false)
    }

    /// Distance the fade extends above the dock card top.
    static let overhang: CGFloat = 16
}
