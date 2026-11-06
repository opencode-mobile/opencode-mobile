import SwiftUI

/// One line of a `HexCode` block.
struct HexCodeLine: Equatable, Sendable, Hashable {
    enum Kind: Equatable, Sendable, Hashable {
        /// A command, a path, a URL: `text1`.
        case plain
        /// A diff's added line: `green`.
        case added
        /// A diff's removed line: `red`.
        case removed
        /// "… N more lines": `text3`.
        case note
    }

    var text: String
    var kind: Kind = .plain

    init(_ text: String, kind: Kind = .plain) {
        self.text = text
        self.kind = kind
    }
}

/// A command or diff block (the prototype's `.pm-code`): `background` fill, `line` border, radius
/// `m`, padding 10 × 12, type `code`. Lines wrap and are never cut. With `lineLimit`, lines past it
/// fold into "… N more lines".
struct HexCode: View {
    let lines: [HexCodeLine]
    var lineLimit: Int?
    /// "… N more lines" for the folded count.
    var moreText: (Int) -> String = { "… \($0) more lines" }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: HexRadius.m, style: .continuous)
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(shown.enumerated()), id: \.offset) { _, line in
                Text(verbatim: line.text)
                    .hexFont(.code)
                    .foregroundStyle(color(line.kind))
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(HexColor.background, in: shape)
        .overlay(shape.strokeBorder(HexColor.line, lineWidth: 1))
        .textSelection(.enabled)
    }

    private var shown: [HexCodeLine] {
        guard let lineLimit, lines.count > lineLimit else { return lines }
        return Array(lines.prefix(lineLimit)) + [HexCodeLine(moreText(lines.count - lineLimit), kind: .note)]
    }

    private func color(_ kind: HexCodeLine.Kind) -> Color {
        switch kind {
        case .plain: HexColor.text1
        case .added: HexColor.green
        case .removed: HexColor.red
        case .note: HexColor.text3
        }
    }
}
