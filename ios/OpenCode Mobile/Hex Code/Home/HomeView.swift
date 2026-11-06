import SafariServices
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

    @State private var showsWaiting = false
    /// A page on the computer, open in the in-app browser.
    @State private var browserPage: BrowserPage?
    @Environment(\.openURL) private var openURL

    private var drafting: Bool { home.isRecording || !home.draft.isEmpty }

    var body: some View {
        VStack(spacing: 0) {
            HomeTopBar(session: session, onSettings: onSettings)
            // The draft hangs from a line 26 % down and grows downward as you talk (the prototype's
            // `.fx-sp`); the status card, its fallback and the setup failure are centred.
            HangingLayout(fraction: drafting ? 0.26 : nil) {
                if drafting {
                    DraftView(home: home)
                        .transition(.opacity)
                } else {
                    HomeStatusView(session: session, onOpenLink: open)
                        .transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            if !drafting, session.waitingCount > 0 {
                WaitingLine(count: session.waitingCount) { showsWaiting = true }
                    .padding(.bottom, 10)
                    .transition(.opacity)
            } else {
                Spacer().frame(height: 12)
            }
            dock
                // While held, a quiet hint floats 10 pt above, where the finger can't cover it.
                .overlay(alignment: .top) {
                    HexHoldHint(
                        holding: home.isPressing && home.isRecording && !home.locked,
                        offset: home.dragOffset,
                        arm: home.arm
                    )
                    .fixedSize()
                    .alignmentGuide(.top) { $0[.bottom] + 10 }
                }
                .padding(.bottom, 6)
        }
        .padding(.horizontal, HexSpacing.screenHome)
        .hexScreenBackground()
        .animation(HexMotion.ease(HexMotion.fade), value: drafting)
        .animation(HexMotion.ease(HexMotion.fade), value: session.waitingCount)
        // The microphone gets ready (not started) while home is on screen.
        .onAppear { home.prepareRecorder() }
        #if DEBUG
        .task { await PressProbe.run(home) }
        .task {
            try? await Task.sleep(for: .seconds(2))
            EdgeDeferral.report()
        }
        #endif
        .fullScreenCover(item: $browserPage) { page in
            InAppBrowser(url: page.url).ignoresSafeArea()
        }
        .hexSheet(isPresented: $showsWaiting, detents: [.medium]) {
            WaitingSheet(session: session) { showsWaiting = false }
        }
    }

    /// A tile: a page on the computer opens in the in-app browser, anything else in the system
    /// browser.
    private func open(_ destination: CardLinks.Destination) {
        if destination.inApp {
            browserPage = BrowserPage(url: destination.url)
        } else {
            openURL(destination.url)
        }
    }

    /// The prototype's `.fx-dock`: attach, the talk button filling the rest, stop. Stop is dimmed
    /// unless the agent is working or needs input.
    private var dock: some View {
        HStack(spacing: 8) {
            ZStack {
                if home.dock == .lockedTake {
                    HexIconButton(.x, label: LocalizedStringResource("home.locked.drop", defaultValue: "Drop this take")) {
                        Task { await home.dropLockedTake() }
                    }
                    .transition(.opacity)
                } else {
                    HexIconButton(.clip, label: LocalizedStringResource("home.attach", defaultValue: "Attach"), action: onNotYet)
                        .transition(.opacity)
                }
            }
            TalkButton(home: home, voice: voice)
            ZStack {
                if home.dock == .lockedTake {
                    // While a take is locked the agent's stop steps aside, so ✓ is never confused with it.
                    HexIconButton(.check, label: LocalizedStringResource("home.locked.done", defaultValue: "Done"), tone: .strong) {
                        Task { await home.finishLockedTake() }
                    }
                    .transition(.opacity)
                } else {
                    HexIconButton(
                        .stop,
                        label: LocalizedStringResource("home.stop", defaultValue: "Stop"),
                        tone: .stop,
                        enabled: stopEnabled,
                        action: onNotYet
                    )
                    .transition(.opacity)
                }
            }
        }
        .animation(HexMotion.ease(HexMotion.fade), value: home.dock)
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
            } else {
                switch DraftStatusLine.of(isRecording: false, draft: draft, speech: home.speech.status) {
                case let .speechDownloading(percent):
                    HStack(spacing: 6) {
                        HexSpinner(size: HexIconSize.small)
                        Text(verbatim: String(localized: "home.speech.downloading", defaultValue: "Downloading the speech model · \(percent)%"))
                    }
                case .speechLoading:
                    HStack(spacing: 6) {
                        HexSpinner(size: HexIconSize.small)
                        Text(LocalizedStringResource("home.speech.loading", defaultValue: "Speech model loading…"))
                    }
                case .speechFailed:
                    Text(LocalizedStringResource("home.speech.failed", defaultValue: "Speech model didn’t load"))
                        .foregroundStyle(HexColor.red)
                case .transcribing:
                    HStack(spacing: 6) {
                        HexSpinner(size: HexIconSize.small)
                        Text(LocalizedStringResource("draft.transcribing", defaultValue: "Transcribing on this phone…"))
                    }
                case .recording, .summary:
                    // The draft, then what Send will do (the prototype's mode caption).
                    Text(verbatim: summary + " · ") + modeCaption
                }
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

    /// "Sends now" while the agent is idle; "Queue · sends after this turn" or, in amber, "Steer ·
    /// lands at the next step" while it works.
    private var modeCaption: Text {
        guard home.agentBusy else {
            return Text(LocalizedStringResource("home.mode.idle", defaultValue: "Sends now"))
        }
        switch home.sendMode {
        case .afterTurn:
            return Text(LocalizedStringResource("home.mode.queue", defaultValue: "Queue · sends after this turn"))
        case .now:
            return Text(LocalizedStringResource("home.mode.steer", defaultValue: "Steer · lands at the next step"))
                .foregroundStyle(HexColor.amber)
        }
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
                if home.canRetryTakes {
                    HexButton(LocalizedStringResource("home.take.retry", defaultValue: "Try again"), icon: .refresh) {
                        home.retryFailedTakes()
                    }
                }
                Spacer()
                if home.agentBusy {
                    HexSegmented(
                        [SendMode.afterTurn, .now],
                        selection: Binding(get: { home.sendMode }, set: { home.sendMode = $0 }),
                        size: .small,
                        selectedColor: { $0 == .now ? HexColor.amber : HexColor.text1 }
                    ) { mode in
                        mode == .now
                            ? String(localized: "home.mode.now", defaultValue: "Now")
                            : String(localized: "home.mode.after", defaultValue: "After turn")
                    }
                    .fixedSize()
                }
                HexButton(
                    home.takesPending
                        ? LocalizedStringResource("home.send.transcribing", defaultValue: "Transcribing")
                        : LocalizedStringResource("home.send", defaultValue: "Send"),
                    icon: .send,
                    style: .primary,
                    enabled: home.canSend,
                    iconAfter: true
                ) { home.send() }
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
        case .transcribing, .waiting:
            // A waiting take looks like one being transcribed; the label line says why it waits.
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
/// would not fit below it (a long draft), like the prototype's shrinking `.fx-sp` spacer. With no
/// fraction, the content is centred (from the top when it is taller than the space), and it is
/// offered the whole height, so a card can choose a size that fits.
struct HangingLayout: Layout {
    var fraction: CGFloat?

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let fraction else {
            for subview in subviews {
                let offer = ProposedViewSize(width: bounds.width, height: bounds.height)
                let height = min(subview.sizeThatFits(offer).height, bounds.height)
                let top = max(0, (bounds.height - height) / 2)
                subview.place(at: CGPoint(x: bounds.minX, y: bounds.minY + top), anchor: .topLeading, proposal: offer)
            }
            return
        }
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

/// "Waiting to send": each message queued for after this turn, with Cancel. One delivered or
/// cancelled elsewhere leaves the list (through the inbox events); the sheet closes when it's empty.
struct WaitingSheet: View {
    let session: TodaySession
    let onClose: () -> Void

    @State private var cancelling: Set<String> = []

    var body: some View {
        HexSheet(
            title: Text(LocalizedStringResource("home.waiting.title", defaultValue: "Waiting to send")),
            leading: { EmptyView() },
            trailing: {
                HexButton(LocalizedStringResource("common.close", defaultValue: "Close"), action: onClose)
            }
        ) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(session.waitingMessages) { message in
                        HStack(alignment: .top, spacing: 12) {
                            HexIconView(.send, size: HexIconSize.small)
                                .foregroundStyle(HexColor.text3)
                                .padding(.top, 3)
                            Text(verbatim: message.text)
                                .hexFont(.row)
                                .foregroundStyle(HexColor.text1)
                                .lineLimit(2)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            HexButton(
                                LocalizedStringResource("home.waiting.cancel", defaultValue: "Cancel"),
                                loading: cancelling.contains(message.id)
                            ) {
                                cancel(message.id)
                            }
                        }
                        .padding(.vertical, 12)
                        .transition(.opacity)
                    }
                }
                .animation(HexMotion.ease(HexMotion.fade), value: session.waitingMessages)
            }
        }
        .onChange(of: session.waitingMessages.isEmpty) { _, empty in
            if empty { onClose() }
        }
    }

    private func cancel(_ id: String) {
        cancelling.insert(id)
        Task {
            try? await session.cancelWaiting(id)
            cancelling.remove(id)
        }
    }
}

#if DEBUG
/// `-HexCodePressProbe <n>`: a few seconds after home appears, n press, hold 1 s and release
/// cycles through the talk button's own path, printing the press timings (read them with
/// `devicectl device process launch --console`). Debug builds only; never on a normal launch.
@MainActor
enum PressProbe {
    private static var ran = false

    static func run(_ home: HomeModel) async {
        let count = UserDefaults.standard.integer(forKey: "HexCodePressProbe")
        guard count > 0, !ran else { return }
        ran = true
        try? await Task.sleep(for: .seconds(4))
        for cycle in 1 ... count {
            print("HEXPROBE cycle \(cycle)")
            home.pressBegan()
            try? await Task.sleep(for: .seconds(1))
            await home.pressEnded()
            try? await Task.sleep(for: .seconds(2))
        }
        print("HEXPROBE done")
    }
}
#endif

#if DEBUG
/// Prints the bottom edge deferral UIKit actually sees, following its child view controller chain
/// from the window's root, to check `.defersSystemGestures(on: .bottom)` reaches it.
@MainActor
enum EdgeDeferral {
    static func report() {
        let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene
        guard var controller = scene?.keyWindow?.rootViewController else { return }
        var chain = [String(describing: type(of: controller))]
        while let child = controller.childForScreenEdgesDeferringSystemGestures {
            controller = child
            chain.append(String(describing: type(of: controller)))
        }
        let defers = controller.preferredScreenEdgesDeferringSystemGestures.contains(.bottom)
        print("HEXTOUCH bottom edge deferred: \(defers) via \(chain.joined(separator: " > "))")
    }
}
#endif

/// A page shown in the in-app browser.
struct BrowserPage: Identifiable {
    let url: URL
    var id: URL { url }
}

/// The system's in-app browser (`SFSafariViewController`).
struct InAppBrowser: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
}
