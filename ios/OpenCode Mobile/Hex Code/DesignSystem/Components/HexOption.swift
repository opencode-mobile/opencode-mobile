import SwiftUI

/// A full-width choice card (the prototype's `.opt`): `panel2`, a `line` border, radius `l`, padding
/// 12 × 14, a leading 18 pt mark, the title in `button` and an optional second line in `caption`,
/// `text2`. Selected: a `tint(34)` border on `tint(6)`.
///
/// An option the user speaks (Say your own answer, Say what to do instead) takes a `press`: holding
/// it records, and its second line reads "Listening…" with a red caret, then "Transcribing…", then
/// the text.
struct HexOption: View {
    enum Mark: Equatable {
        /// Single choice: a 1.5 pt `text3` ring; selected, a `text1` ring with an 8 pt dot.
        case radio
        /// Multiselect: a rounded square; selected, filled `text1` with a check in `onSolid`.
        case checkbox
        /// No mark (Say what to do instead).
        case none
    }

    /// A spoken answer on its way.
    enum Live: Equatable {
        /// "Listening…" with a pulsing red caret.
        case listening(String)
        /// "Transcribing…", dimmed.
        case transcribing(String)
    }

    let title: String
    var detail: String?
    var mark: Mark = .radio
    var selected = false
    var enabled = true
    var live: Live?
    /// Hold to speak: touches go here instead of `action`.
    var press: HexPress?
    var action: () -> Void = {}

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous)
        Group {
            if let press {
                card(shape)
                    .overlay(HexHoldSurface(press: enabled ? press : HexPress(began: {}, ended: {})))
                    .accessibilityElement(children: .combine)
                    // VoiceOver can't hold: activate starts the take, activate again finishes it.
                    // VoiceOver stays quiet meanwhile, so its speech isn't in the take.
                    .accessibilityAddTraits([.isButton, .startsMediaSession])
                    .accessibilityHint(Text(LocalizedStringResource("a11y.say.hint", defaultValue: "Double-tap to start speaking, and again to finish")))
                    .accessibilityAction {
                        guard enabled else { return }
                        if case .listening = live { press.ended() } else { press.began() }
                    }
            } else {
                Button(action: action) { card(shape) }
                    .buttonStyle(HexPressStyle(scale: 0.99))
                    .disabled(!enabled)
            }
        }
        .opacity(enabled ? 1 : 0.4)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func card(_ shape: RoundedRectangle) -> some View {
        HStack(alignment: .top, spacing: 12) {
            if mark != .none {
                markView
                    .padding(.top, 1)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: title)
                    .hexFont(.button)
                    .foregroundStyle(HexColor.text1)
                    .fixedSize(horizontal: false, vertical: true)
                second
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(selected ? HexColor.tint(6) : HexColor.panel2, in: shape)
        .overlay(shape.strokeBorder(selected ? HexColor.tint(34) : HexColor.line, lineWidth: 1))
        .contentShape(shape)
        .hexAnimation(HexMotion.ease(HexMotion.press), value: selected)
    }

    @ViewBuilder
    private var second: some View {
        switch live {
        case let .listening(text)?:
            HStack(spacing: 2) {
                Text(verbatim: text)
                HexCaret()
            }
            .hexFont(.caption)
            .foregroundStyle(HexColor.text2)
        case let .transcribing(text)?:
            Text(verbatim: text)
                .hexFont(.caption)
                .foregroundStyle(HexColor.text3)
        case nil:
            if let detail, !detail.isEmpty {
                Text(verbatim: detail)
                    .hexFont(.caption)
                    .foregroundStyle(HexColor.text2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    @ViewBuilder
    private var markView: some View {
        switch mark {
        case .radio:
            Circle()
                .strokeBorder(selected ? HexColor.text1 : HexColor.text3, lineWidth: 1.5)
                .frame(width: 18, height: 18)
                .overlay {
                    if selected {
                        Circle().fill(HexColor.text1).frame(width: 8, height: 8)
                    }
                }
        case .checkbox:
            let box = RoundedRectangle(cornerRadius: HexRadius.xs, style: .continuous)
            ZStack {
                if selected {
                    box.fill(HexColor.text1)
                    HexIconView(.check, size: 12)
                        .foregroundStyle(HexColor.onSolid)
                } else {
                    box.strokeBorder(HexColor.text3, lineWidth: 1.5)
                }
            }
            .frame(width: 18, height: 18)
        case .none:
            EmptyView()
        }
    }
}

/// The red caret after "Listening…", pulsing (`.live-ans`). Still with Reduce Motion.
struct HexCaret: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dimmed = false

    var body: some View {
        Rectangle()
            .fill(HexColor.red)
            .frame(width: 2, height: 13)
            .opacity(dimmed ? 0.2 : 1)
            .onAppear { pulse() }
            // Turning Reduce Motion on while it shows steadies it at once.
            .onChange(of: reduceMotion) { pulse() }
            .accessibilityHidden(true)
    }

    private func pulse() {
        guard !reduceMotion else {
            withAnimation(nil) { dimmed = false }
            return
        }
        withAnimation(.easeInOut(duration: 0.45).repeatForever(autoreverses: true)) { dimmed = true }
    }
}
