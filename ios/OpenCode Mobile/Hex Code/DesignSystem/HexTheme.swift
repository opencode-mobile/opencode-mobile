import SwiftUI

extension View {
    /// The root of Hex Code's UI: the chosen colour scheme (nil follows the system), Hex's
    /// background behind everything, and system controls (text cursors, dialogs) in `text1`.
    func hexTheme(_ colorScheme: ColorScheme?) -> some View {
        preferredColorScheme(colorScheme)
            .tint(HexColor.text1)
            .background(HexColor.background.ignoresSafeArea())
    }

    /// A full screen on Hex's background.
    func hexScreenBackground() -> some View {
        background(HexColor.background.ignoresSafeArea())
    }
}
