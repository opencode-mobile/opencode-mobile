import SwiftUI

/// One take in a draft, as `HexChunk` and `HexChunkRow` show it.
struct HexChunkItem: Identifiable, Equatable {
    enum Kind: Equatable {
        /// Transcribed text: tappable.
        case text
        /// "Listening…", "Transcribing…": faint, not tappable.
        case placeholder
        /// The take couldn't be transcribed: red, tappable (to delete or re-record).
        case failed
    }

    let id: UUID
    let text: String
    var kind: Kind = .text
}

/// Draft text with tappable chunks, as running text. Each chunk has a faint `tint(4.5)` fill;
/// the selected one `tint(15)`, and the others dim. While recording, chunks are quiet and
/// nothing is tappable.
struct HexChunk: View {
    let items: [HexChunkItem]
    var selectedID: UUID?
    /// Off while recording.
    var interactive = true
    var style: HexTextStyle = .draft
    let onTap: (UUID) -> Void

    var body: some View {
        Text(attributed)
            .hexFont(style)
            .tint(HexColor.text1)
            .environment(\.openURL, OpenURLAction { url in
                if let id = url.host().flatMap(UUID.init(uuidString:)) { onTap(id) }
                return .handled
            })
    }

    private var attributed: AttributedString {
        var out = AttributedString()
        for (index, item) in items.enumerated() {
            if index > 0 { out += AttributedString(" ") }
            var span = AttributedString(item.text)
            let selected = selectedID == item.id
            switch item.kind {
            case .placeholder:
                span.foregroundColor = HexColor.text3
            case .text, .failed:
                let tappable = interactive
                if item.kind == .failed {
                    span.foregroundColor = HexColor.red
                } else if !tappable {
                    span.foregroundColor = HexColor.text2
                } else if selectedID != nil, !selected {
                    span.foregroundColor = HexColor.text3
                } else {
                    span.foregroundColor = HexColor.text1
                }
                if tappable {
                    span.link = URL(string: "chunk://\(item.id.uuidString)")
                    if selected {
                        span.backgroundColor = HexColor.tint(15)
                    } else if selectedID == nil {
                        span.backgroundColor = HexColor.tint(4.5)
                    }
                }
            }
            out += span
        }
        return out
    }
}

/// A long draft's chunk row: number, one line of text, and its length. The selected row opens
/// to four lines on a `tint(10)` fill with a `tint(20)` border; others dim.
struct HexChunkRow: View {
    let number: String?
    let text: String
    let duration: String
    var selected = false
    var dimmed = false
    /// The take being recorded: a red dot for the number, medium text.
    var live = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .firstTextBaseline, spacing: 9) {
                Group {
                    if live {
                        HexDot(tone: .red)
                    } else {
                        Text(verbatim: number ?? "")
                    }
                }
                .hexFont(HexTextStyle.mono.weight(.regular))
                .foregroundStyle(HexColor.text3)
                .frame(width: 16, alignment: .trailing)
                Text(verbatim: text)
                    .hexFont(HexTextStyle.row.weight(live ? .medium : .regular))
                    .foregroundStyle(dimmed ? HexColor.text3 : HexColor.text1)
                    .lineLimit(selected ? 4 : (live ? 3 : 1))
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(verbatim: duration)
                    .hexFont(.mono)
                    .foregroundStyle(HexColor.text3)
            }
            .padding(6)
            .background {
                let shape = RoundedRectangle(cornerRadius: HexRadius.s, style: .continuous)
                if selected {
                    shape.fill(HexColor.tint(10))
                        .overlay(shape.strokeBorder(HexColor.tint(20), lineWidth: 1))
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
