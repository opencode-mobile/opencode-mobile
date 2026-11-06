import SwiftUI

/// One segment of `HexProgress`.
enum HexProgressSegment: Equatable, Sendable {
    /// Lit (`text1`).
    case done
    /// Half lit: the left half `text1`, the right half `text4`.
    case inProgress
    /// `text4`.
    case open
}

/// The agent's to-do list in one line (the prototype's `.fx-prog`): one 18 × 3 segment per item,
/// then a label such as "3 of 4 · Run the tests" in `caption`, `text2`, on one line, and "›" in
/// `text3`. Tappable; the label and "›" light to `text1` while pressed.
struct HexProgress: View {
    let segments: [HexProgressSegment]
    /// The label, which may carry formatting (code runs built with `HexInlineCode`).
    let label: Text
    /// VoiceOver's label, the label's words without formatting.
    let accessibilityText: String
    var action: () -> Void = {}

    var body: some View {
        Button(action: action) {
            EmptyView()
        }
        .buttonStyle(PressReporting { pressed in
            HStack(spacing: 10) {
                HStack(spacing: 4) {
                    ForEach(segments.indices, id: \.self) { index in
                        SegmentMark(segment: segments[index])
                    }
                }
                .fixedSize()
                .animation(HexMotion.ease(0.3), value: segments)
                label
                    .hexFont(.caption)
                    .foregroundStyle(pressed ? HexColor.text1 : HexColor.text2)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .hexInlineCode()
                Text(verbatim: "›")
                    .hexFont(.caption)
                    .foregroundStyle(pressed ? HexColor.text1 : HexColor.text3)
                    .fixedSize()
            }
            .animation(HexMotion.ease(HexMotion.press), value: pressed)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        })
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: accessibilityText))
        .accessibilityAddTraits(.isButton)
    }
}

private struct SegmentMark: View {
    let segment: HexProgressSegment

    var body: some View {
        HStack(spacing: 0) {
            switch segment {
            case .done:
                HexColor.text1
            case .inProgress:
                HexColor.text1
                HexColor.text4
            case .open:
                HexColor.text4
            }
        }
        .frame(width: 18, height: 3)
        .clipShape(Capsule())
    }
}

/// A button style that draws its own content from whether it is pressed.
private struct PressReporting<Content: View>: ButtonStyle {
    let content: (Bool) -> Content

    func makeBody(configuration: Configuration) -> some View {
        content(configuration.isPressed)
    }
}
