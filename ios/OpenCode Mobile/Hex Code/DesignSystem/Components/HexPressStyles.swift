import SwiftUI

/// Small scale on press, as on the prototype's buttons and rows.
struct HexPressStyle: ButtonStyle {
    var scale: CGFloat = 0.98

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .animation(HexMotion.ease(HexMotion.press), value: configuration.isPressed)
    }
}

/// A row that takes the `hover` fill while pressed.
struct HexRowPressStyle: ButtonStyle {
    var radius: CGFloat = 0

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                configuration.isPressed ? HexColor.hover : .clear,
                in: RoundedRectangle(cornerRadius: radius, style: .continuous)
            )
    }
}
