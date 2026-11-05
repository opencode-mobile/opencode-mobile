import SwiftUI

/// The prototype's `.fx-prog`: one 18 × 3 segment per step, lit (`text1`) when done and `text4`
/// when not, then a mono label such as "2/4 · details ›". Tappable; the label lights while pressed.
struct HexProgress: View {
    /// One flag per step: true when it is done.
    let steps: [Bool]
    let label: String
    var action: () -> Void = {}

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                HStack(spacing: 4) {
                    ForEach(steps.indices, id: \.self) { index in
                        Capsule()
                            .fill(steps[index] ? HexColor.text1 : HexColor.text4)
                            .frame(width: 18, height: 3)
                    }
                }
                .animation(HexMotion.ease(0.3), value: steps)
                Text(verbatim: label)
                    .hexFont(.mono)
                    .lineLimit(1)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(HexProgressPressStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: label))
        .accessibilityAddTraits(.isButton)
    }
}

/// The label goes from `text3` to `text1` while pressed, like the prototype's hover.
private struct HexProgressPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(configuration.isPressed ? HexColor.text1 : HexColor.text3)
            .animation(HexMotion.ease(HexMotion.press), value: configuration.isPressed)
    }
}
