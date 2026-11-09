import SwiftUI

/// The agent's state in the home top bar: an icon in a fixed 18 pt slot, a label, and an
/// optional timer.
struct HexTopBarState: Equatable {
    enum Tone: Equatable {
        /// Grey ring, `text2` label.
        case idle
        /// Green arc turning, `text1` label.
        case working
        /// Amber ring dot, amber label.
        case needsYou
        /// Amber arc turning fast, amber label.
        case reconnecting
        /// Cold start: the Reconnecting arc in `text3`, `text3` label.
        case connecting
    }

    var tone: Tone
    var label: String
    /// Mono timer after the label, for example `4:12`.
    var timer: String?
    /// What VoiceOver reads, when it differs from the label and timer ("Working, 2 minutes").
    var spoken: String?
}

/// The home top bar's height, for what sits just under it (the stop countdown).
enum HexTopBarMetrics {
    static let homeHeight: CGFloat = HexSize.touch + 2
    /// A page's bar (`.pk-top`): 56 pt plus 4 pt above it.
    static let pageHeight: CGFloat = 60
    /// Where a notice sits on home: 12 pt under the bar.
    static let homeToastTop: CGFloat = homeHeight + 12
    /// Where a notice sits on a page: 12 pt under its bar.
    static let pageToastTop: CGFloat = pageHeight + 12
}

/// Top bar. Two forms:
/// - home (`.tb`): the agent's state on the left, round buttons on the right;
/// - page (`.pk-top`): a round back button, the page title and a line under it with a dot.
struct HexTopBar<Trailing: View>: View {
    private enum Title {
        /// Nil leaves the state slot empty (while today's session is set up).
        case state(HexTopBarState?)
        case page(title: Text, subtitle: String?, dot: HexDot.Tone)
    }

    private let title: Title
    private var onBack: (() -> Void)?
    private var onTitle: (() -> Void)?
    private let trailing: Trailing

    /// Home: the agent's state, or nothing; tapping it runs `onTitle` when set (needs input).
    init(state: HexTopBarState?, onTitle: (() -> Void)? = nil, @ViewBuilder trailing: () -> Trailing) {
        title = .state(state)
        self.onTitle = onTitle
        self.trailing = trailing()
    }

    /// A page: back, title, and a quiet line under it after a dot (the computer's name).
    init(
        _ title: LocalizedStringResource,
        subtitle: String? = nil,
        dot: HexDot.Tone = .green,
        onBack: (() -> Void)?,
        @ViewBuilder trailing: () -> Trailing
    ) {
        self.title = .page(title: Text(title), subtitle: subtitle, dot: dot)
        self.onBack = onBack
        self.trailing = trailing()
    }

    /// A page whose title is not translated, such as the English-only developer pages.
    init(
        verbatim title: String,
        subtitle: String? = nil,
        dot: HexDot.Tone = .green,
        onBack: (() -> Void)?,
        @ViewBuilder trailing: () -> Trailing
    ) {
        self.title = .page(title: Text(verbatim: title), subtitle: subtitle, dot: dot)
        self.onBack = onBack
        self.trailing = trailing()
    }

    var body: some View {
        switch title {
        case let .state(state):
            HStack(spacing: 10) {
                if let state {
                    stateTitle(state)
                }
                Spacer(minLength: 0)
                trailing
            }
            .frame(minHeight: HexSize.touch)
            .padding(.top, HexTopBarMetrics.homeHeight - HexSize.touch)
        case let .page(text, subtitle, dot):
            HStack(spacing: 10) {
                if let onBack {
                    HexRoundButton(.chevL, label: LocalizedStringResource("common.back", defaultValue: "Back"), action: onBack)
                }
                VStack(alignment: .leading, spacing: 2) {
                    text
                        .hexFont(.pageTitle)
                        .foregroundStyle(HexColor.text1)
                        .lineLimit(2)
                        .accessibilityAddTraits(.isHeader)
                    if let subtitle {
                        HStack(spacing: 6) {
                            HexDot(tone: dot)
                            Text(verbatim: subtitle)
                                .hexFont(.caption)
                                .foregroundStyle(HexColor.text2)
                                .lineLimit(2)
                        }
                    }
                }
                Spacer(minLength: 0)
                trailing
            }
            .padding(.horizontal, HexSpacing.screen)
            .frame(minHeight: 56)
            .padding(.top, 4)
        }
    }

    @ViewBuilder
    private func stateTitle(_ state: HexTopBarState) -> some View {
        let content = HStack(spacing: 9) {
            stateIcon(state.tone)
                .frame(width: 18, height: 18)
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(verbatim: state.label)
                    .hexFont(.headline)
                    .foregroundStyle(labelColor(state.tone))
                if let timer = state.timer {
                    Text(verbatim: timer)
                        .hexFont(HexTextStyle.small.weight(.regular).monospaced())
                        .foregroundStyle(HexColor.text3)
                }
            }
            .lineLimit(1)
        }
        .frame(minHeight: 24)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: state.spoken ?? [state.label, state.timer].compactMap { $0 }.joined(separator: ", ")))
        .accessibilityAddTraits(.isHeader)
        if let onTitle {
            Button(action: onTitle) { content }
                .buttonStyle(HexPressStyle())
        } else {
            content
        }
    }

    @ViewBuilder
    private func stateIcon(_ tone: HexTopBarState.Tone) -> some View {
        switch tone {
        case .idle:
            Circle()
                .stroke(HexColor.text3, lineWidth: 1.5)
                .frame(width: 11, height: 11)
        case .working:
            HexSpinner(color: HexColor.green, period: 1.6)
        case .reconnecting:
            HexSpinner(color: HexColor.amber)
        case .connecting:
            HexSpinner(color: HexColor.text3)
        case .needsYou:
            Circle()
                .fill(HexColor.amber)
                .frame(width: 8, height: 8)
                .background(Circle().fill(HexColor.amber.opacity(0.16)).padding(-4))
        }
    }

    private func labelColor(_ tone: HexTopBarState.Tone) -> Color {
        switch tone {
        case .idle: HexColor.text2
        case .working: HexColor.text1
        case .needsYou, .reconnecting: HexColor.amber
        case .connecting: HexColor.text3
        }
    }
}

extension HexTopBar where Trailing == EmptyView {
    init(_ title: LocalizedStringResource, subtitle: String? = nil, dot: HexDot.Tone = .green, onBack: (() -> Void)?) {
        self.init(title, subtitle: subtitle, dot: dot, onBack: onBack) { EmptyView() }
    }

    init(verbatim title: String, subtitle: String? = nil, dot: HexDot.Tone = .green, onBack: (() -> Void)?) {
        self.init(verbatim: title, subtitle: subtitle, dot: dot, onBack: onBack) { EmptyView() }
    }
}
