import SwiftUI

/// A sheet that shows a long text whole (the long-dictation prototype, variant A): a head row with
/// the title (`bodyStrong`), a `mono` `text3` meta ("2:31 · 235 words") and Done; the text in `body`
/// `text1`, in paragraphs, scrolling; and an optional foot (a `line` top border, ✕ on the left and
/// Send on the right). "Draft" has the foot; "You said" has none.
struct HexReader: View {
    struct Foot {
        var discardLabel: Text
        var sendTitle: Text
        var sendEnabled = true
        var onDiscard: () -> Void
        var onSend: () -> Void
    }

    let title: Text
    var meta: String?
    /// A `footnote` line under the head, such as "You said"'s "With report.pdf".
    var note: String?
    let paragraphs: [String]
    var foot: Foot?
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(HexColor.line2)
                .frame(width: 36, height: 4)
                .padding(.top, 8)
                .padding(.bottom, 6)
                .accessibilityHidden(true)
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                title
                    .hexFont(.bodyStrong)
                    .foregroundStyle(HexColor.text1)
                    .accessibilityAddTraits(.isHeader)
                if let meta {
                    Text(verbatim: meta)
                        .hexFont(.mono)
                        .foregroundStyle(HexColor.text3)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                HexButton(LocalizedStringResource("common.done", defaultValue: "Done"), action: onDone)
            }
            .padding(.horizontal, 18)
            .padding(.bottom, note == nil ? 10 : 4)
            if let note {
                Text(verbatim: note)
                    .hexFont(.footnote)
                    .foregroundStyle(HexColor.text3)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 18)
                    .padding(.bottom, 10)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(paragraphs.indices, id: \.self) { index in
                        Text(verbatim: paragraphs[index])
                            .hexFont(.body)
                            .foregroundStyle(HexColor.text1)
                            .fixedSize(horizontal: false, vertical: true)
                            .textSelection(.enabled)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.bottom, 20)
            }
            if let foot {
                HStack {
                    HexButton(icon: .x, action: foot.onDiscard)
                        .accessibilityLabel(foot.discardLabel)
                    Spacer()
                    HexButton(text: foot.sendTitle, icon: .send, style: .primary, enabled: foot.sendEnabled, iconAfter: true, action: foot.onSend)
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
                .overlay(alignment: .top) { Rectangle().fill(HexColor.line).frame(height: 1) }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

/// "2:31 · 235 words", or only the words when the length isn't known.
enum ReaderMeta {
    static func text(duration: TimeInterval?, words: Int) -> String {
        let count = words == 1
            ? String(localized: "reader.one_word", defaultValue: "1 word")
            : String(localized: "draft.words", defaultValue: "\(words) words")
        guard let duration, duration > 0 else { return count }
        return "\(DraftClock.format(duration)) · \(count)"
    }

    static func words(_ text: String) -> Int {
        text.split(whereSeparator: \.isWhitespace).count
    }
}
