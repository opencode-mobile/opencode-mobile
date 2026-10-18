import SwiftUI

private struct OpenCodeUsableWindowWidthKey: EnvironmentKey {
    static let defaultValue: CGFloat? = nil
}

extension EnvironmentValues {
    /// Window width minus the horizontal safe-area insets, measured once at the scene
    /// root. Nil where no root measured it (previews).
    var openCodeUsableWindowWidth: CGFloat? {
        get { self[OpenCodeUsableWindowWidthKey.self] }
        set { self[OpenCodeUsableWindowWidthKey.self] = newValue }
    }
}

extension View {
    /// Publishes `openCodeUsableWindowWidth` to the scene. Apply once to the window root.
    /// The reader sits inside the safe area, so its width already excludes the
    /// horizontal insets (the iPhone Duo status rail, landscape sensor housing).
    func openCodeWindowMetrics() -> some View {
        GeometryReader { proxy in
            environment(\.openCodeUsableWindowWidth, proxy.size.width)
                .frame(width: proxy.size.width, height: proxy.size.height)
        }
    }
}

/// Whether a full-window screen shows its split layout. Store it on the view
/// (`var splitGate = OpenCodeSplitLayoutGate()`); SwiftUI keeps the size classes
/// and window width it reads current. Keep it non-private: a private stored
/// property hides the view's memberwise initializer from Xcode 26 builds.
struct OpenCodeSplitLayoutGate: DynamicProperty {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.openCodeUsableWindowWidth) private var usableWidth

    var usesSplitLayout: Bool {
        OpenCodeSplitPaneLayout.usesSplitLayout(
            horizontalSizeClass: horizontalSizeClass,
            verticalSizeClass: verticalSizeClass,
            usableWidth: usableWidth
        )
    }
}
