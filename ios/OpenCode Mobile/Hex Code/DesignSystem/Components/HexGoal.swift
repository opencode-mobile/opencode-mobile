import SwiftUI

/// The goal line under the home top bar (the prototype's `.goalline`): a 34 pt row with the
/// `target` icon (14 pt), the objective (`callout`, `text1`, one line, ellipsised) and a quiet
/// suffix (`mono`, `text3`). Empty: "Set today's goal", icon and text in `text3`. The icon is
/// `green` while lit (active or done), else `text3`.
struct HexGoalLine: View {
    /// The objective; nil for the empty line.
    let objective: String?
    var suffix: String?
    var lit = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                HexIconView(.target, size: 14)
                    .foregroundStyle(lit && objective != nil ? HexColor.green : HexColor.text3)
                Text(verbatim: objective ?? String(localized: "goal.line.empty", defaultValue: "Set today’s goal"))
                    .hexFont(.callout)
                    .foregroundStyle(objective == nil ? HexColor.text3 : HexColor.text1)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let suffix {
                    Text(verbatim: suffix)
                        .hexFont(.mono)
                        .foregroundStyle(HexColor.text3)
                        .lineLimit(1)
                        .fixedSize()
                }
            }
            // A minimum, and a 44 pt hit area round the 34 pt line.
            .frame(minHeight: 34)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
            .padding(.vertical, -5)
        }
        .buttonStyle(HexPressStyle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

/// The goal sheet's text box (the prototype's `.brief-box`): `background` fill, a `line` border
/// (`line2` while a take is live), radius `l`. A label row (`mono`, `text3`, left and right), then
/// the text (`body`, `text1`), then an optional reason (`caption`, `text2`).
struct HexGoalBox: View {
    enum Content: Equatable {
        /// "No goal yet." in `text3`.
        case empty(String)
        /// The objective, or a take's text.
        case text(String)
        /// "Listening…" with the red caret.
        case listening(String)
        /// "Transcribing…".
        case transcribing(String)
    }

    let label: String
    var status: String?
    let content: Content
    var reason: String?

    private var live: Bool {
        switch content {
        case .listening, .transcribing: true
        default: false
        }
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous)
        VStack(alignment: .leading, spacing: 6) {
            if case .empty = content {} else {
                HStack(spacing: 8) {
                    Text(verbatim: label)
                    Spacer(minLength: 8)
                    if let status {
                        Text(verbatim: status)
                            .lineLimit(1)
                    }
                }
                .hexFont(HexTextStyle.mono.sized(11))
                .foregroundStyle(HexColor.text3)
            }
            text
            if let reason, !reason.isEmpty {
                Text(verbatim: reason)
                    .hexFont(.caption)
                    .foregroundStyle(HexColor.text2)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 2)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(HexColor.background, in: shape)
        .overlay(shape.strokeBorder(live ? HexColor.line2 : HexColor.line, lineWidth: 1))
        .hexAnimation(HexMotion.ease(HexMotion.fade), value: content)
    }

    @ViewBuilder
    private var text: some View {
        switch content {
        case let .empty(text):
            Text(verbatim: text)
                .hexFont(HexTextStyle.body.sized(14))
                .foregroundStyle(HexColor.text3)
        case let .text(text):
            Text(verbatim: text)
                .hexFont(.body)
                .foregroundStyle(HexColor.text1)
                .fixedSize(horizontal: false, vertical: true)
        case let .listening(text):
            HStack(alignment: .center, spacing: 3) {
                Text(verbatim: text)
                    .hexFont(.body)
                    .foregroundStyle(HexColor.text3)
                HexCaret()
            }
        case let .transcribing(text):
            Text(verbatim: text)
                .hexFont(.body)
                .foregroundStyle(HexColor.text3)
        }
    }
}
