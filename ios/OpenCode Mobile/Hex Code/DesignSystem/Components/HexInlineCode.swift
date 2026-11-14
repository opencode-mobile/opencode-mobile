import SwiftUI

/// Inline code, as Slack draws it but quieter (the prototype's `.md-c`): the system monospace at
/// 0.82 of the text's size, regular weight, `text1` at 88 %, in a faint box that hugs the glyphs,
/// not the whole line: `tint(4.5)` fill, a hairline `tint(7)` edge, radius 0.26 em and 0.26 em
/// padding either side. Boxes on neighbouring lines never touch: each is only as tall as the
/// monospace's ascender to descender, and a run that wraps gets a box on each line.
///
/// Build the text with `HexInlineCode.text(_:size:)` for each code run (a four-per-em space either
/// side makes room for the padding) and draw it with `.textRenderer(HexInlineCode.Renderer())`.
enum HexInlineCode {
    /// The code's size, as a share of the text around it.
    static let scale: CGFloat = 0.82

    /// Marks a run as code; its box is drawn from these, in points.
    struct Box: TextAttribute {
        var padding: CGFloat
        var radius: CGFloat
    }

    /// A code run at `size` × `scale` (the text's size, with Dynamic Type applied), with room for
    /// its box either side.
    static func text(_ code: String, size: CGFloat, scale: CGFloat = HexInlineCode.scale) -> Text {
        let codeSize = size * scale
        let run = Text(verbatim: unbroken(code))
            .font(.system(size: codeSize, weight: .regular, design: .monospaced))
            .tracking(-0.01 * codeSize)
            .foregroundStyle(HexColor.text1.opacity(0.88))
            .customAttribute(Box(padding: 0.26 * codeSize, radius: 0.26 * codeSize))
        // A four-per-em space (0.25 em of the text around it, as Android) either side holds the
        // padding outside the box. A word joiner ties each to the code, so code that starts a line
        // keeps its padding and code stays with the punctuation after it; the line can still break
        // at an ordinary space before the code.
        return Text(verbatim: "\u{2005}\u{2060}") + run + Text(verbatim: "\u{2060}\u{2005}")
    }

    /// Code moves to the next line whole, as on the web, rather than breaking after a `-` or `/`:
    /// a word joiner follows each punctuation mark. Spaces inside it still break, and a run
    /// longer than the line still wraps.
    static func unbroken(_ code: String) -> String {
        var out = ""
        for character in code {
            out.append(character)
            if !character.isLetter, !character.isNumber, !character.isWhitespace { out.append("\u{2060}") }
        }
        return out
    }

    /// Draws each code run's box behind the text, then the text.
    struct Renderer: TextRenderer {
        var fill: Color = HexColor.tint(4.5)
        var edge: Color = HexColor.tint(7)
        var hairline: CGFloat = 1 / 3

        func draw(layout: Text.Layout, in context: inout GraphicsContext) {
            for line in layout {
                for run in line {
                    guard let box = run[Box.self] else { continue }
                    let rect = run.typographicBounds.rect.insetBy(dx: -box.padding, dy: 0)
                    let shape = RoundedRectangle(cornerRadius: box.radius, style: .continuous)
                    context.fill(shape.path(in: rect), with: .color(fill))
                    context.stroke(shape.path(in: rect.insetBy(dx: hairline / 2, dy: hairline / 2)), with: .color(edge), lineWidth: hairline)
                }
            }
            for line in layout {
                context.draw(line)
            }
        }
    }
}

extension View {
    /// Draws inline code boxes for text built with `HexInlineCode.text(_:size:)`.
    func hexInlineCode() -> some View {
        modifier(HexInlineCodeModifier())
    }
}

private struct HexInlineCodeModifier: ViewModifier {
    @Environment(\.displayScale) private var displayScale

    func body(content: Content) -> some View {
        content.textRenderer(HexInlineCode.Renderer(hairline: 1 / max(displayScale, 1)))
    }
}
