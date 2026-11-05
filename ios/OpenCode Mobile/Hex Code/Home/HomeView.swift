import SwiftUI
import VoiceEngineKit

/// Where first run lands: the agent's state in the top bar, today's session in the middle (the
/// status card, or the draft while you talk), and the talk button.
struct HomeView: View {
    let home: HomeModel
    let session: TodaySession
    let voice: VoiceEngine
    let onSettings: () -> Void
    /// Attach (M3) and Stop (M4) aren't built yet: the "Not in this version yet" notice.
    var onNotYet: () -> Void = {}

    private var drafting: Bool { home.isRecording || !home.draft.isEmpty }

    var body: some View {
        VStack(spacing: 0) {
            HomeTopBar(session: session, onSettings: onSettings)
            // The status card, its fallback, the setup failure and the draft all hang from the same
            // line, 26 % down, so switching between them moves nothing (the prototype's `.fx-sp`).
            HangingLayout(fraction: 0.26) {
                if drafting {
                    DraftView(home: home)
                        .transition(.opacity)
                } else {
                    HomeStatusView(session: session)
                        .transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            if !drafting, session.waitingCount > 0 {
                WaitingLine(count: session.waitingCount) { session.openDetails() }
                    .padding(.bottom, 10)
                    .transition(.opacity)
            } else {
                Spacer().frame(height: 12)
            }
            dock
                .padding(.bottom, 6)
        }
        .padding(.horizontal, HexSpacing.screenHome)
        .hexScreenBackground()
        .animation(HexMotion.ease(HexMotion.fade), value: drafting)
        .animation(HexMotion.ease(HexMotion.fade), value: session.waitingCount)
    }

    /// The prototype's `.fx-dock`: attach, the talk button filling the rest, stop. Stop is dimmed
    /// unless the agent is working or needs input.
    private var dock: some View {
        HStack(spacing: 8) {
            HexIconButton(.clip, label: LocalizedStringResource("home.attach", defaultValue: "Attach"), action: onNotYet)
            TalkButton(home: home, voice: voice)
            HexIconButton(
                .stop,
                label: LocalizedStringResource("home.stop", defaultValue: "Stop"),
                tone: .stop,
                enabled: stopEnabled,
                action: onNotYet
            )
        }
    }

    private var stopEnabled: Bool {
        switch session.agentState {
        case .working?, .needsInput?: true
        default: false
        }
    }
}

/// The agent's state on the left (Reconnecting…, Needs input, Working m:ss or Idle; empty while
/// today's session is set up), ⚙ on the right. The goal button joins in a later milestone.
struct HomeTopBar: View {
    let session: TodaySession
    let onSettings: () -> Void

    var body: some View {
        let state = session.agentState
        TimelineView(.periodic(from: .now, by: 1)) { context in
            HexTopBar(
                state: state?.topBar(at: context.date),
                onTitle: state == .needsInput ? { session.openPendingInput() } : nil
            ) {
                HexRoundButton(.gear, label: LocalizedStringResource("home.settings", defaultValue: "Settings"), action: onSettings)
            }
        }
    }
}

/// The draft: a label, the chunks (as running text, or one row each once it is long), and the actions.
struct DraftView: View {
    let home: HomeModel

    private var draft: DictationDraft { home.draft }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            label
                .frame(height: HomeLabel.height)
            if draft.isLong {
                ChunkListView(home: home)
            } else {
                DraftText(home: home)
            }
            actions
                .opacity(home.isRecording ? 0 : 1)
                .allowsHitTesting(!home.isRecording)
        }
        .animation(HexMotion.ease(HexMotion.fade), value: draft)
    }

    @ViewBuilder
    private var label: some View {
        Group {
            if home.isRecording {
                TimelineView(.periodic(from: .now, by: 1)) { _ in
                    HStack(spacing: 6) {
                        HexDot(tone: .red)
                        Text(verbatim: recordingLabel)
                        if home.locked {
                            HexIconView(.lock, size: HexIconSize.small)
                        }
                    }
                }
            } else if draft.isTranscribing {
                HStack(spacing: 6) {
                    HexSpinner(size: HexIconSize.small)
                    Text(LocalizedStringResource("draft.transcribing", defaultValue: "Transcribing on this phone…"))
                }
            } else {
                Text(verbatim: summary)
            }
        }
        .hexFont(HexTextStyle.caption.weight(.medium))
        .foregroundStyle(HexColor.text3)
        .lineLimit(1)
    }

    private var recordingLabel: String {
        let elapsed = home.takeStartedAt.map { Date().timeIntervalSince($0) } ?? 0
        let clock = DraftClock.format(elapsed)
        if draft.isRerecording, let index = draft.recordingIndex {
            return String(localized: "draft.rerecording", defaultValue: "Re-recording chunk \(index + 1) · \(clock)")
        }
        return String(localized: "draft.recording", defaultValue: "Recording · \(clock)")
    }

    private var summary: String {
        let chunks = draft.chunks.count == 1
            ? String(localized: "draft.one_chunk", defaultValue: "1 chunk")
            : String(localized: "draft.chunks", defaultValue: "\(draft.chunks.count) chunks")
        var parts = [String(localized: "draft.title", defaultValue: "Draft"), chunks, DraftClock.format(draft.totalDuration)]
        if draft.isLong { parts.append(String(localized: "draft.words", defaultValue: "\(draft.wordCount) words")) }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder
    private var actions: some View {
        HStack(spacing: 8) {
            if let index = draft.selectedIndex {
                Text(LocalizedStringResource("draft.chunk_label", defaultValue: "Chunk \(index + 1)"))
                    .hexFont(.caption)
                    .foregroundStyle(HexColor.text2)
                Spacer()
                HexButton(LocalizedStringResource("draft.delete", defaultValue: "Delete"), icon: .trash, style: .danger) {
                    home.deleteSelectedChunk()
                }
                HexButton(LocalizedStringResource("draft.rerecord", defaultValue: "Re-record"), icon: .mic) {
                    home.rerecordSelected()
                }
                HexButton(icon: .x) { home.deselectChunk() }
                    .accessibilityLabel(Text(LocalizedStringResource("draft.deselect", defaultValue: "Deselect")))
            } else {
                HexButton(icon: .x) { home.clearDraft() }
                    .accessibilityLabel(Text(LocalizedStringResource("draft.clear", defaultValue: "Clear the draft")))
                Spacer()
            }
        }
        .frame(minHeight: 30)
    }
}

/// Short drafts read as running text; each chunk is a tappable span.
struct DraftText: View {
    let home: HomeModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HexChunk(
                items: home.draft.chunks.map { Self.item($0) },
                selectedID: home.draft.selectedID,
                interactive: !home.isRecording,
                onTap: { home.toggleChunk($0) }
            )
            if home.isRecording, home.draft.chunks.count == 1, !home.draft.isRerecording {
                Text(LocalizedStringResource("draft.first_take_hint", defaultValue: "Text appears when you pause"))
                    .hexFont(.row)
                    .foregroundStyle(HexColor.text3)
            }
        }
        // Three lines of room, so the actions don't jump as text arrives.
        .frame(maxWidth: .infinity, minHeight: DraftText.minHeight, alignment: .topLeading)
    }

    /// Three lines of the draft type, as in the prototype.
    private static var minHeight: CGFloat {
        let style = HexTextStyle.draft
        return style.size * (style.lineHeight ?? 1.3) * 3 + 8
    }

    static func item(_ chunk: DraftChunk) -> HexChunkItem {
        switch chunk.state {
        case .listening:
            HexChunkItem(id: chunk.id, text: String(localized: "draft.listening", defaultValue: "Listening…"), kind: .placeholder)
        case .transcribing:
            HexChunkItem(id: chunk.id, text: String(localized: "draft.transcribing_chunk", defaultValue: "Transcribing…"), kind: .placeholder)
        case let .text(text):
            HexChunkItem(id: chunk.id, text: text)
        case let .failed(message):
            HexChunkItem(id: chunk.id, text: message, kind: .failed)
        }
    }
}

/// Long drafts: one row per chunk with its length, at most eight rows, earlier and later folded.
struct ChunkListView: View {
    let home: HomeModel

    var body: some View {
        let draft = home.draft
        let range = draft.visibleRange(showEarlier: home.showEarlier)
        let listed = draft.isListening && !draft.isRerecording ? draft.chunks.count - 1 : draft.chunks.count
        VStack(alignment: .leading, spacing: 1) {
            if range.lowerBound > 0 {
                foldRow(
                    String(localized: "draft.earlier", defaultValue: "↑ \(range.lowerBound) earlier · \(DraftClock.format(duration(0 ..< range.lowerBound)))")
                ) { home.showEarlier = true }
            }
            ForEach(Array(range), id: \.self) { index in
                row(index)
            }
            if listed > range.upperBound {
                foldRow(
                    String(localized: "draft.later", defaultValue: "↓ \(listed - range.upperBound) later · \(DraftClock.format(duration(range.upperBound ..< listed)))")
                ) { home.showEarlier = false }
            }
            if draft.isListening, !draft.isRerecording, let last = draft.chunks.last {
                row(draft.chunks.count - 1, live: true, chunk: last)
            }
        }
        .padding(.horizontal, -6)
    }

    private func duration(_ range: Range<Int>) -> TimeInterval {
        home.draft.chunks[range].reduce(0) { $0 + $1.duration }
    }

    private func foldRow(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(verbatim: title)
                .hexFont(.footnote)
                .foregroundStyle(HexColor.text3)
                .frame(maxWidth: .infinity)
                .padding(4)
        }
        .buttonStyle(.plain)
    }

    private func row(_ index: Int, live: Bool = false, chunk: DraftChunk? = nil) -> some View {
        let chunk = chunk ?? home.draft.chunks[index]
        let selected = home.draft.selectedID == chunk.id
        let dimmed = home.isRecording || (home.draft.selectedID != nil && !selected)
        return HexChunkRow(
            number: "\(index + 1)",
            text: DraftText.item(chunk).text,
            duration: live ? "" : DraftClock.format(chunk.duration),
            selected: selected,
            dimmed: dimmed || chunk.text == nil,
            live: live,
            action: { home.toggleChunk(chunk.id) }
        )
        .disabled(home.isRecording || chunk.text == nil && !isFailed(chunk))
    }

    private func isFailed(_ chunk: DraftChunk) -> Bool {
        if case .failed = chunk.state { return true }
        return false
    }
}

enum DraftClock {
    /// 0:07, 1:24
    static func format(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded()))
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

/// Places its content from a line `fraction` of the way down, and higher only when the content
/// would not fit below it (a long draft), like the prototype's shrinking `.fx-sp` spacer.
struct HangingLayout: Layout {
    var fraction: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for subview in subviews {
            let height = min(subview.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil)).height, bounds.height)
            let top = min(bounds.height * fraction, max(0, bounds.height - height))
            subview.place(
                at: CGPoint(x: bounds.minX, y: bounds.minY + top),
                anchor: .topLeading,
                proposal: ProposedViewSize(width: bounds.width, height: bounds.height - top)
            )
        }
    }
}
