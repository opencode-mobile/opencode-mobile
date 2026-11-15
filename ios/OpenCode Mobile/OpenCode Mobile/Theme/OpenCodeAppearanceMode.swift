import SwiftUI
import UIKit

extension AppearanceMode {
    /// The window style this mode forces; `.unspecified` follows the phone.
    var userInterfaceStyle: UIUserInterfaceStyle {
        switch self {
        case .system: .unspecified
        case .light: .light
        case .dark: .dark
        }
    }
}

extension View {
    /// Applies the Settings › Appearance mode (#1044) to the window this view is in. The style is
    /// set on the window itself, so sheets, dialogs and window overlays follow it right away.
    func openCodeAppearanceMode() -> some View {
        modifier(OpenCodeAppearanceModeModifier())
    }
}

private struct OpenCodeAppearanceModeModifier: ViewModifier {
    @State private var preferences = AppearancePreferences.shared

    func body(content: Content) -> some View {
        content.background {
            OpenCodeAppearanceModeAnchor(style: preferences.mode.userInterfaceStyle)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }
}

private struct OpenCodeAppearanceModeAnchor: UIViewRepresentable {
    let style: UIUserInterfaceStyle

    func makeUIView(context _: Context) -> AnchorView {
        AnchorView()
    }

    func updateUIView(_ uiView: AnchorView, context _: Context) {
        uiView.style = style
    }

    final class AnchorView: UIView {
        var style: UIUserInterfaceStyle = .unspecified {
            didSet { apply() }
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            apply()
        }

        private func apply() {
            guard let window, window.overrideUserInterfaceStyle != style else { return }
            window.overrideUserInterfaceStyle = style
        }
    }
}
