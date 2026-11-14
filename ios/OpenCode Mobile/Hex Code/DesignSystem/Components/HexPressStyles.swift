import SwiftUI

/// Small scale on press, as on the prototype's buttons and rows.
/// With Reduce Motion, no scale: the press shows in colour only.
struct HexPressStyle: ButtonStyle {
    var scale: CGFloat = 0.98

    func makeBody(configuration: Configuration) -> some View {
        HexPressScale(scale: scale, pressed: configuration.isPressed) { configuration.label }
    }
}

private struct HexPressScale<Label: View>: View {
    let scale: CGFloat
    let pressed: Bool
    @ViewBuilder let label: Label
    @Environment(\.accessibilityReduceMotion) private var reduced

    var body: some View {
        label
            .scaleEffect(HexMotion.pressScale(scale, pressed: pressed, reduced: reduced))
            .animation(HexMotion.ease(HexMotion.press), value: pressed)
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
