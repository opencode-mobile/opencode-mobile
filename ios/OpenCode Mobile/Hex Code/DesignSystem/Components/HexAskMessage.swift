import SwiftUI

/// The Ask answer under the status (the prototype's `.askc` and `.askc.mini`): `panel2`, a `line2`
/// border, radius `xl`.
/// - Folded: at least 50 pt, the `ask` icon, a line (`row`) and a second line (`caption`, `text3`),
///   and a 32 pt ✕. Asking: "Reading the session…" pulsing in `text3`, not tappable. Answered: the
///   answer on one line; a tap opens it. Failed or empty: the title in `amber` and Try again.
/// - Open: a head ("Asked on the side · 2m", fold and ✕), the question (`row`, `text2`), the whole
///   answer (`body`, `text1`), and a footer ("Only you see this" and Copy).
/// The answer's backticks are drawn as on the status card.
struct HexAskMessage: View {
    enum State: Equatable {
        case asking
        case answered(String)
        /// Failed or empty: the title (amber) and its second line, with Try again.
        case problem(title: String, detail: String)
    }

    let question: String
    let age: String
    let state: State
    var isOpen = false
    var onOpen: () -> Void = {}
    var onFold: () -> Void = {}
    var onClose: () -> Void = {}
    var onCopy: () -> Void = {}
    var onRetry: () -> Void = {}

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: HexRadius.xl, style: .continuous)
        Group {
            if isOpen, case let .answered(answer) = state {
                open(answer)
            } else {
                folded
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(HexColor.panel2, in: shape)
        .overlay(shape.strokeBorder(HexColor.line2, lineWidth: 1))
        .hexAnimation(HexMotion.ease(HexMotion.fade), value: isOpen)
    }

    // MARK: Folded

    private var folded: some View {
        HStack(spacing: 4) {
            Button(action: onOpen) {
                HStack(spacing: 11) {
                    HexIconView(.ask, size: 14)
                        .foregroundStyle(HexColor.text2)
                    VStack(alignment: .leading, spacing: 1) {
                        firstLine
                            .lineLimit(1)
                            .truncationMode(.tail)
                        Text(verbatim: secondLine)
                            .hexFont(.caption)
                            .foregroundStyle(HexColor.text3)
                            .lineLimit(1)
                            .truncationMode(.tail)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.leading, 14)
                .padding(.trailing, 6)
                .padding(.vertical, 10)
                .contentShape(Rectangle())
            }
            .buttonStyle(HexPressStyle())
            .disabled(!isAnswered)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(isAnswered ? .isButton : [])
            if case .problem = state {
                HexButton(LocalizedStringResource("ask.try_again", defaultValue: "Try again"), action: onRetry)
            }
            closeButton
        }
        .padding(.trailing, 6)
        .frame(minHeight: 50)
    }

    @ViewBuilder
    private var firstLine: some View {
        switch state {
        case .asking:
            HexPulsingText(text: String(localized: "ask.reading", defaultValue: "Reading the session…"))
        case let .answered(answer):
            SummaryText(text: answer, style: HexTextStyle.row.weight(.medium))
                .foregroundStyle(HexColor.text1)
        case let .problem(title, _):
            Text(verbatim: title)
                .hexFont(HexTextStyle.row.weight(.medium))
                .foregroundStyle(HexColor.amber)
        }
    }

    private var secondLine: String {
        if case let .problem(_, detail) = state { return detail }
        return "“\(question)” · \(age)"
    }

    private var isAnswered: Bool {
        if case .answered = state { return true }
        return false
    }

    private var closeButton: some View {
        Button(action: onClose) {
            HexIconView(.x, size: HexIconSize.small)
                .foregroundStyle(HexColor.text3)
                .frame(width: 32, height: 32)
                .contentShape(Rectangle())
        }
        .buttonStyle(HexRowPressStyle(radius: HexRadius.s))
        .accessibilityLabel(Text(LocalizedStringResource("ask.close", defaultValue: "Close")))
    }

    // MARK: Open

    private func open(_ answer: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 7) {
                HexIconView(.ask, size: 14)
                    .foregroundStyle(HexColor.text2)
                Text(verbatim: String(localized: "ask.title", defaultValue: "Asked on the side · \(age)"))
                    .hexFont(.caption)
                    .foregroundStyle(HexColor.text2)
                    .lineLimit(1)
                Spacer(minLength: 0)
                HexButton(icon: .chevD, action: onFold)
                    .accessibilityLabel(Text(LocalizedStringResource("ask.fold", defaultValue: "Fold")))
                HexButton(icon: .x, action: onClose)
                    .accessibilityLabel(Text(LocalizedStringResource("ask.close", defaultValue: "Close")))
            }
            .padding(.top, -4)
            .padding(.trailing, -6)
            .padding(.bottom, 6)
            Text(verbatim: "“\(question)”")
                .hexFont(HexTextStyle.row.sized(14))
                .foregroundStyle(HexColor.text2)
                .fixedSize(horizontal: false, vertical: true)
            SummaryText(text: answer, style: HexTextStyle.body.sized(15.5))
                .foregroundStyle(HexColor.text1)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 6)
                .textSelection(.enabled)
            HStack(spacing: 8) {
                Text(LocalizedStringResource("ask.only_you", defaultValue: "Only you see this"))
                    .hexFont(.caption)
                    .foregroundStyle(HexColor.text3)
                Spacer(minLength: 0)
                HexButton(LocalizedStringResource("ask.copy", defaultValue: "Copy"), icon: .copy, action: onCopy)
            }
            .frame(minHeight: 30)
            .padding(.top, 8)
        }
        .padding(.horizontal, 14)
        .padding(.top, 12)
        .padding(.bottom, 10)
    }
}

/// Text in `text3` that pulses while something is on its way ("Reading the session…"). Still with
/// Reduce Motion.
struct HexPulsingText: View {
    let text: String

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { context in
            let phase = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.1) / 1.1
            Text(verbatim: text)
                .hexFont(.row)
                .foregroundStyle(HexColor.text3)
                .opacity(reduceMotion ? 1 : 0.55 + 0.45 * (0.5 + 0.5 * cos(phase * 2 * .pi)))
        }
    }
}
