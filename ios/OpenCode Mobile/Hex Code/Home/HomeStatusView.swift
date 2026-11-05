import SwiftUI

/// The status area on home (the prototype's `.fx-body` when not drafting): the summary card or its
/// plain-text fallback, the needs-you hint under it, or "Couldn't open today's session". Centred in
/// the room between the top bar and the talk button.
struct HomeStatusView: View {
    let session: TodaySession

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            switch session.setup {
            case .opening:
                EmptyView()
            case let .failed(retrying):
                SetupFailedView(retrying: retrying) { Task { await session.retry() } }
            case .ready:
                switch session.summary.summary {
                case let .card(card):
                    StatusCardView(card: card, summary: session.summary, onDetails: { session.openDetails() })
                case let .fallback(text):
                    FallbackCardView(text: text, summary: session.summary)
                case nil:
                    // The first summary of a session that ran before: only ↻ "updating…".
                    if session.summary.isUpdating {
                        HStack {
                            Spacer(minLength: 0)
                            SummaryRefreshButton(summary: session.summary)
                        }
                    }
                }
                if let input = session.pendingInput {
                    NeedsHintCard(input: input) { session.openPendingInput() }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .animation(HexMotion.ease(HexMotion.fade), value: session.summary.summary)
        .animation(HexMotion.ease(HexMotion.fade), value: session.pendingInput)
    }
}

/// The task, what the agent does now, what comes next, the plan's progress and ↻.
struct StatusCardView: View {
    let card: StatusCard
    let summary: StatusSummaryModel
    let onDetails: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(verbatim: card.title)
                .hexFont(HexTextStyle.caption.weight(.medium))
                .foregroundStyle(HexColor.text3)
                .lineLimit(1)
                .frame(height: HomeLabel.height)
            Text(verbatim: card.now)
                .hexFont(.display)
                .foregroundStyle(HexColor.text1)
                .lineLimit(5)
                .opacity(summary.isUpdating ? 0.35 : 1)
            if let next = card.next {
                Text(verbatim: String(localized: "home.summary.next", defaultValue: "Next: \(next)"))
                    .hexFont(.body)
                    .foregroundStyle(HexColor.text2)
                    .opacity(summary.isUpdating ? 0.35 : 1)
            }
            // One 10 pt gap between the progress and ↻, however wide (the prototype's `.fx-meta`).
            HStack(spacing: 0) {
                if !card.steps.isEmpty {
                    HexProgress(steps: card.steps.map(\.done), label: progressLabel, action: onDetails)
                        .layoutPriority(1)
                }
                Spacer(minLength: 10)
                SummaryRefreshButton(summary: summary)
            }
            .frame(minHeight: 30)
        }
        .animation(HexMotion.ease(HexMotion.fade), value: summary.isUpdating)
    }

    private var progressLabel: String {
        let done = card.doneCount
        let total = card.steps.count
        return String(localized: "home.summary.progress", defaultValue: "\(done)/\(total) · details ›")
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
