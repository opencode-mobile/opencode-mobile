import SwiftUI

/// One status line with a leading mark: a green ✓ (ok), an amber ⚠ (warning), a spinner
/// (waiting) or nothing (hint). Long text wraps beside the mark, which stays on the first line.
struct HexStatusLine: View {
    enum Tone: Equatable {
        case ok
        case warning
        case waiting
        case hint
    }

    let tone: Tone
    let text: Text

    init(_ tone: Tone, _ text: LocalizedStringResource) {
        self.tone = tone
        self.text = Text(text)
    }

    init(_ tone: Tone, verbatim text: String) {
        self.tone = tone
        self.text = Text(verbatim: text)
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 7) {
            mark
                .alignmentGuide(.firstTextBaseline) { $0[.bottom] - 2 }
            text
                .hexFont(HexTextStyle.small.weight(.regular))
                .foregroundStyle(color)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var mark: some View {
        let size: CGFloat = 14
        switch tone {
        case .ok:
            HexIconView(.check, size: size).foregroundStyle(HexColor.green)
        case .warning:
            HexIconView(.alert, size: size).foregroundStyle(HexColor.amber)
        case .waiting:
            HexSpinner(size: size)
        case .hint:
            EmptyView()
        }
    }

    private var color: Color {
        switch tone {
        case .ok: HexColor.green
        case .warning: HexColor.amber
        case .waiting, .hint: HexColor.text3
        }
    }
}
