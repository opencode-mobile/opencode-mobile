import SwiftUI

/// The status area on home (the prototype's `.fx-body` when not drafting): the summary card or its
/// plain-text fallback, the needs-you hint under it, or "Couldn't open today's session". Centred in
/// the room between the top bar and the talk button.
struct HomeStatusView: View {
    let session: TodaySession
    /// A tile was tapped.
    var onOpenLink: (CardLinks.Destination) -> Void = { _ in }
    /// Next was tapped: its text goes into the draft.
    var onNext: (String) -> Void = { _ in }

    var body: some View {
        Group {
            switch session.setup {
            case .opening:
                // Setup is still finding today's session: only a card saved for it shows.
                summary(hint: nil)
            case let .failed(retrying):
                SetupFailedView(retrying: retrying) { Task { await session.retry() } }
            case .ready:
                summary(hint: session.pendingInput)
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .animation(HexMotion.ease(HexMotion.fade), value: session.summary.summary)
        .animation(HexMotion.ease(HexMotion.fade), value: session.pendingInput)
    }

    @ViewBuilder
    private func summary(hint: PendingItem?) -> some View {
        switch session.summary.summary {
        case let .card(card):
            // Now steps down in size until the whole card (and the hint under it), as drawn, fits
            // between the top bar and the dock; then whole lines go from the end.
            ViewThatFits(in: .vertical) {
                ForEach(NowSize.layouts(for: card.lines), id: \.self) { layout in
                    VStack(alignment: .leading, spacing: 14) {
                        StatusCardView(
                            card: card,
                            layout: layout,
                            labelState: session.pendingInput != nil ? .waiting : session.busySince != nil ? .working : .plain,
                            sentMessage: session.sentMessage,
                            summary: session.summary,
                            computerHost: session.computerHost,
                            todo: TodoProgress(session.todos),
                            runningCount: session.runningCount,
                            onDetails: { session.openDetails() },
                            onOpenLink: onOpenLink,
                            onNext: onNext
                        )
                        hintCard(hint)
                    }
                }
            }
        case let .fallback(text):
            VStack(alignment: .leading, spacing: 14) {
                FallbackCardView(text: text, summary: session.summary)
                hintCard(hint)
            }
        case nil:
            VStack(alignment: .leading, spacing: 14) {
                // The first summary of a session that ran before: only ↻ "updating…".
                if session.summary.isUpdating {
                    HStack {
                        Spacer(minLength: 0)
                        SummaryRefreshButton(summary: session.summary)
                    }
                }
                hintCard(hint)
            }
        }
    }

    @ViewBuilder
    private func hintCard(_ hint: PendingItem?) -> some View {
        if let hint {
            NeedsHintCard(input: hint) { session.openPendingInput() }
        }
    }
}

/// The task, what the agent does now, what comes next, the plan's progress and ↻.
struct StatusCardView: View {
    let card: StatusCard
    /// Now's size and how many of its lines show.
    var layout = NowLayout(size: .display, lineCount: .max)
    /// The small label above Now: the title, or "Still working · …" / "Waiting for you · …".
    var labelState: CardLabelState = .plain
    /// The message in flight: "You: “…”" in place of Next.
    var sentMessage: String?
    let summary: StatusSummaryModel
    var computerHost: String?
    /// The agent's to-do list; nil for no row.
    var todo: TodoProgress?
    /// Subagents and shell commands running, in front of Details.
    var runningCount = 0
    let onDetails: () -> Void
    var onOpenLink: (CardLinks.Destination) -> Void = { _ in }
    var onNext: (String) -> Void = { _ in }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            CardLabel(title: card.title, state: labelState)
            NowText(lines: layout.shown(card.lines), style: layout.size.style, rowLimit: layout.rowLimit)
                .opacity(summary.isUpdating ? 0.35 : 1)
            if let sentMessage {
                // Not tappable: it is what was just sent.
                Text(verbatim: String(localized: "home.summary.you", defaultValue: "You: “\(sentMessage)”"))
                    .hexFont(.body)
                    .foregroundStyle(HexColor.text3)
                    .lineLimit(1)
                    .truncationMode(.tail)
            } else if let next = card.next {
                // Next is in the user's words: a tap puts it into the draft, never sends it.
                Button { onNext(InlineMarkdown.plain(next)) } label: {
                    SummaryText(text: String(localized: "home.summary.next", defaultValue: "Next: \(next)"), style: .body)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(NextPressStyle())
                .opacity(summary.isUpdating ? 0.35 : 1)
            }
            if !card.links.isEmpty {
                HexTileRow {
                    ForEach(card.links, id: \.url) { link in
                        tile(link)
                    }
                }
            }
            if let todo {
                // Opens Details from M4.
                HexProgress(
                    segments: todo.segments.map(\.mark),
                    // Code in the to-do label is a little larger, as the prototype's `.fx-pl .md-c`.
                    label: SummaryText.text(todo.text, style: .caption, scale: scale, codeScale: 0.9),
                    accessibilityText: InlineMarkdown.plain(todo.text),
                    action: onDetails
                )
            }
            // Details and ↻, at least 10 pt apart (the prototype's `.fx-meta`).
            HStack(spacing: 0) {
                DetailsLink(runningCount: runningCount, action: onDetails)
                Spacer(minLength: 10)
                SummaryRefreshButton(summary: summary)
            }
            .frame(minHeight: 30)
        }
        .animation(HexMotion.ease(HexMotion.fade), value: summary.isUpdating)
    }

    @ViewBuilder
    private func tile(_ link: CardLink) -> some View {
        if let destination = CardLinks.destination(for: link, computerHost: computerHost) {
            // The label shows as given, ellipsised at the tile's width.
            HexLinkTile(
                icon: destination.inApp ? .monitor : .globe,
                label: link.label,
                detail: destination.shownHost,
                detailIcon: destination.inApp ? .monitor : .out,
                accessibilityText: String(localized: "home.link.open", defaultValue: "Open \(link.label)")
            ) { onOpenLink(destination) }
        }
    }

    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 100
}

private extension TodoProgress.Segment {
    var mark: HexProgressSegment {
        switch self {
        case .done: .done
        case .inProgress: .inProgress
        case .open: .open
        }
    }
}

/// "Details ›" in `mono`, with a green dot and "2 running · " in front while subagents or shell
/// commands run (the prototype's `.fx-det`). Lights to `text1` while pressed.
private struct DetailsLink: View {
    let runningCount: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if runningCount > 0 {
                    HexDot(tone: .green)
                }
                Text(verbatim: label)
                    .hexFont(.mono)
                    .lineLimit(1)
            }
            .frame(minHeight: 30)
            .contentShape(Rectangle())
        }
        .buttonStyle(LightsWhenPressed())
    }

    private var label: String {
        let details = String(localized: "home.details", defaultValue: "Details ›")
        guard runningCount > 0 else { return details }
        return String(localized: "home.details.running", defaultValue: "\(runningCount) running · ") + details
    }
}

/// What the label above Now says about the session (app plan, "Card label while working").
enum CardLabelState: Equatable, Sendable {
    case plain
    /// The session is busy.
    case working
    /// A question or approval is pending.
    case waiting
}

/// The small label above Now: the card's title; while the session is busy "Still working ·
/// <title>" behind the top bar's green arc; while a question or approval waits "Waiting for you ·
/// <title>" behind the amber dot. The new card's plain title replaces it when the run ends.
struct CardLabel: View {
    let title: String
    let state: CardLabelState

    var body: some View {
        HStack(spacing: 7) {
            switch state {
            case .plain:
                EmptyView()
            case .working:
                HexSpinner(size: 12, color: HexColor.green, period: 1.6)
            case .waiting:
                HexDot(tone: .amber)
            }
            SummaryText(text: Self.text(title: title, state: state), style: HexTextStyle.caption.weight(.medium))
                .foregroundStyle(HexColor.text3)
                .lineLimit(1)
        }
        .frame(height: HomeLabel.height)
        .animation(HexMotion.ease(HexMotion.fade), value: state)
    }

    static func text(title: String, state: CardLabelState) -> String {
        switch state {
        case .plain:
            title
        case .working:
            title.isEmpty
                ? String(localized: "home.summary.working.bare", defaultValue: "Still working")
                : String(localized: "home.summary.working", defaultValue: "Still working · \(title)")
        case .waiting:
            title.isEmpty
                ? String(localized: "home.summary.waiting.bare", defaultValue: "Waiting for you")
                : String(localized: "home.summary.waiting", defaultValue: "Waiting for you · \(title)")
        }
    }
}

/// Next: `text2`, lit to `text1` while pressed.
private struct NextPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(configuration.isPressed ? HexColor.text1 : HexColor.text2)
            .animation(HexMotion.ease(HexMotion.press), value: configuration.isPressed)
    }
}

/// Now's lines (app plan, "Formatting"): plain lines are paragraphs 0.35 em apart; bullets are a
/// list with a hanging "•" in `text3`, a 0.9 em indent, 0.15 em between items and 0.25 em above
/// the list. Each line keeps its formatting.
struct NowText: View {
    let lines: [NowLine]
    let style: HexTextStyle
    /// Rows of text each line may take (the last resort at very large text sizes).
    var rowLimit: Int?

    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 100

    var body: some View {
        let em = style.size * scale / 100
        VStack(alignment: .leading, spacing: 0) {
            ForEach(lines.indices, id: \.self) { index in
                row(lines[index], em: em)
                    .lineLimit(rowLimit)
                    .truncationMode(.tail)
                    .padding(.top, gap(before: index) * em)
            }
        }
        .foregroundStyle(HexColor.text1)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Space above a line, in em.
    private func gap(before index: Int) -> CGFloat {
        let line = lines[index]
        guard index > 0 else { return line.isBullet ? 0.25 : 0 }
        if line.isBullet, lines[index - 1].isBullet { return 0.15 }
        return 0.35
    }

    @ViewBuilder
    private func row(_ line: NowLine, em: CGFloat) -> some View {
        if line.isBullet {
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text(verbatim: "•")
                    .hexFont(style)
                    .foregroundStyle(HexColor.text3)
                    .frame(width: 0.9 * em, alignment: .leading)
                    .accessibilityHidden(true)
                SummaryText(text: line.text, style: style)
            }
        } else {
            SummaryText(text: line.text, style: style)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// `text3`, lit to `text1` while pressed.
private struct LightsWhenPressed: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(configuration.isPressed ? HexColor.text1 : HexColor.text3)
            .animation(HexMotion.ease(HexMotion.press), value: configuration.isPressed)
    }
}

/// The reply as plain text when it didn't match the card shape, with ↻ to try again.
struct FallbackCardView: View {
    let text: String
    let summary: StatusSummaryModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(LocalizedStringResource("home.summary.fallback.title", defaultValue: "Status"))
                .hexFont(HexTextStyle.caption.weight(.medium))
                .foregroundStyle(HexColor.text3)
                .frame(height: HomeLabel.height)
            Text(verbatim: text)
                .hexFont(.body)
                .foregroundStyle(HexColor.text2)
                .lineLimit(8)
                .opacity(summary.isUpdating ? 0.35 : 1)
            HStack(spacing: 0) {
                HexStatusLine(.warning, LocalizedStringResource("home.summary.fallback.note", defaultValue: "Reply didn’t match the card shape."))
                Spacer(minLength: 10)
                SummaryRefreshButton(summary: summary)
            }
            .frame(minHeight: 30)
        }
    }
}

/// ↻ and its label: "now", "3m ago", "updating…" (the arrow turns), or "Couldn't update".
struct SummaryRefreshButton: View {
    let summary: StatusSummaryModel

    var body: some View {
        TimelineView(.periodic(from: .now, by: 15)) { context in
            let label = summary.refreshLabel(at: context.date)
            Button {
                Task { await summary.refresh() }
            } label: {
                HStack(spacing: 5) {
                    RefreshMark(turning: label == .updating)
                    Text(verbatim: label.text)
                        .lineLimit(1)
                }
                .hexFont(.mono)
                .foregroundStyle(label == .failed ? HexColor.amber : HexColor.text3)
                .padding(.leading, 8)
                .frame(minHeight: 30)
                .contentShape(Rectangle())
            }
            .buttonStyle(HexPressStyle())
            .disabled(label == .updating)
        }
    }
}

/// The ↻ arrow, turning while a summary is on its way.
private struct RefreshMark: View {
    let turning: Bool

    var body: some View {
        TimelineView(.animation(paused: !turning)) { context in
            let period = 0.8
            let turn = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period) / period
            HexIconView(.refresh, size: HexIconSize.small)
                .rotationEffect(.degrees(turning ? turn * 360 : 0))
        }
    }
}

/// The amber card under the status when the agent waits for an answer or an approval
/// (the prototype's `.needs-hint`).
struct NeedsHintCard: View {
    let input: PendingItem
    let action: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous)
        Button(action: action) {
            HStack(spacing: 12) {
                HexIconView(.alert, size: HexIconSize.regular)
                    .foregroundStyle(HexColor.amber)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .hexFont(.button)
                        .foregroundStyle(HexColor.text1)
                    Text(verbatim: input.text)
                        .hexFont(.caption)
                        .foregroundStyle(HexColor.text2)
                }
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
                HexIconView(.chevR, size: HexIconSize.small)
                    .foregroundStyle(HexColor.text3)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .background {
                shape.fill(HexColor.panel)
                shape.fill(HexColor.amberSoft)
            }
            .overlay(shape.strokeBorder(HexColor.amberLine, lineWidth: 1))
            .contentShape(shape)
        }
        .buttonStyle(HexPressStyle())
        .accessibilityElement(children: .combine)
    }

    private var title: LocalizedStringResource {
        switch input.kind {
        case .question: LocalizedStringResource("home.needs.input", defaultValue: "Needs your input")
        case .approval: LocalizedStringResource("home.needs.approval", defaultValue: "Approval needed")
        }
    }
}

/// "Couldn't open today's session" with Try again.
struct SetupFailedView: View {
    let retrying: Bool
    let onRetry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(LocalizedStringResource("home.setup.failed", defaultValue: "Couldn’t open today’s session"))
                .hexFont(.display)
                .foregroundStyle(HexColor.text1)
            HexButton(
                LocalizedStringResource("home.setup.retry", defaultValue: "Try again"),
                icon: .refresh,
                size: .regular,
                loading: retrying,
                action: onRetry
            )
        }
    }
}

/// "N waiting · sends after this turn ›" above the talk button while the inbox has queued messages.
struct WaitingLine: View {
    let count: Int
    let action: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: HexRadius.m, style: .continuous)
        Button(action: action) {
            HStack(spacing: 8) {
                HexIconView(.send, size: HexIconSize.small)
                    .foregroundStyle(HexColor.text3)
                Text(verbatim: String(localized: "home.waiting", defaultValue: "\(count) waiting · sends after this turn ›"))
                    .hexFont(HexTextStyle.small.weight(.regular))
                    .foregroundStyle(HexColor.text2)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(HexColor.panel, in: shape)
            .overlay(shape.strokeBorder(HexColor.line, lineWidth: 1))
            .contentShape(shape)
        }
        .buttonStyle(HexPressStyle())
    }
}

/// The small label every home body starts with (card title, "Status", the draft's line): one
/// height, so it sits on the same line in each.
enum HomeLabel {
    static let height: CGFloat = 18
}

/// Summary text with its inline formatting (app plan, "Formatting"): code as `HexInlineCode`
/// (the system monospace at 0.82 of the size in a faint box that hugs the glyphs), `**bold**` in
/// semibold (bold where the text is already semibold, as at `display` and `nowLarge`).
struct SummaryText: View {
    let text: String
    let style: HexTextStyle

    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 100

    var body: some View {
        Self.text(text, style: style, scale: scale)
            .hexFont(style)
            .hexInlineCode()
            // The words only, without the spacing that makes room for code boxes.
            .accessibilityLabel(Text(verbatim: InlineMarkdown.plain(text)))
    }

    /// The runs of `text` at `style`, with Dynamic Type's `scale` (100 at the default size). Draw
    /// it with `.hexInlineCode()` for the code boxes.
    static func text(_ text: String, style: HexTextStyle, scale: CGFloat, codeScale: CGFloat = HexInlineCode.scale) -> Text {
        let size = style.size * scale / 100
        return InlineMarkdown.runs(text).reduce(Text(verbatim: "")) { out, run in
            switch run.style {
            case .plain:
                out + Text(verbatim: run.text)
            case .bold:
                out + Text(verbatim: run.text).fontWeight(style.weight == .semibold ? .bold : .semibold)
            case .code:
                out + HexInlineCode.text(run.text, size: size, scale: codeScale)
            }
        }
    }
}
