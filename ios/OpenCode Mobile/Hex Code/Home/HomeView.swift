import SwiftUI
import VoiceEngineKit

/// Where first run lands: the agent's state in the top bar, today's session in the middle (the
/// status card, or the draft while you talk), and the talk button.
struct HomeView: View {
    let home: HomeModel
    let session: TodaySession
    let voice: VoiceEngine
    /// Artifacts on this computer: the card's artifact tile, the one-time hint and the browser's
    /// "Turn on…". Nil for the sample sessions.
    var artifacts: ArtifactsModel?
    /// The in-app browser for an artifact.
    var artifactBrowser: (CardArtifact) -> BrowserModel? = { _ in nil }
    let onSettings: () -> Void
    /// Signed out's Pair again.
    var onPairAgain: () -> Void = {}

    @State private var showsAttach = false
    /// The stop button's 3 s hold.
    @State private var stopHold: StopHoldModel?
    /// Where the Attach sheet goes once it has closed.
    @State private var attachRoute: AttachRoute?
    @State private var showsPhotos = false
    @State private var showsCamera = false
    @State private var showsFiles = false
    /// A page on the computer, open in the in-app browser.
    @State private var browser: BrowserModel?
    /// The draft is a long single take, shown as a 7-line window (its block is centred like any draft).
    @State private var draftWindowed = false
    /// "You said": the sent message whole.
    @State private var youSaid: SentMessage?
    /// VoiceOver's activate on a lit stop: "Stop the agent?" first, which keeps the hold's safety.
    @State private var confirmsStop = false
    @AccessibilityFocusState private var talkFocused: Bool
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.openURL) private var openURL

    private var drafting: Bool { home.isRecording || !home.draft.isEmpty }

    var body: some View {
        VStack(spacing: 0) {
            HomeTopBar(session: session, onSettings: onSettings)
            // Today's goal, under the top bar; hidden while you talk, with a draft, and while an
            // Ask message shows. With no line (before today's session is open, or no goal while the
            // agent works) its height stays, so nothing moves when it fills in.
            if !drafting, session.ask.shown == nil {
                Group {
                    if let line = session.goalLine {
                        HexGoalLine(
                            objective: session.goal?.objective,
                            suffix: line.suffix,
                            lit: line.iconLit
                        ) { session.openGoalSheet() }
                    } else {
                        HexGoalLine(objective: nil) {}
                            .hidden()
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                }
                .padding(.top, 2)
                .transition(.opacity)
            }
            // The status card, the setup failure and every draft block (label, text or chunk rows,
            // ✕ / Send) are centred as one unit between the top bar and the dock; a long single
            // take shows as a 7-line window.
            CentredLayout {
                if drafting {
                    DraftView(home: home, windowed: $draftWindowed)
                        .transition(.opacity)
                } else {
                    HomeStatusView(
                        session: session,
                        artifacts: artifacts,
                        onOpenArtifact: openArtifact,
                        onOpenLink: open,
                        onNext: { home.addSuggestion($0) },
                        onPairAgain: onPairAgain,
                        onReadSent: { youSaid = home.sentReader(for: $0, attachments: session.sentAttachments) }
                    )
                    .transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            if !drafting, session.ask.shown != nil {
                // Between the status card and the waiting line; the card never changes.
                AskMessageView(ask: session.ask, toasts: home.toasts)
                    .padding(.bottom, 10)
                    .transition(.opacity)
            }
            if !drafting {
                // Waiting messages open Details, where they can be cancelled. With none (or before
                // they are read) its height stays, so nothing moves when it fills in.
                Group {
                    if session.waitingCount > 0 {
                        WaitingLine(count: session.waitingCount, idle: session.agentState == .idle) { session.openDetails() }
                            .transition(.opacity)
                    } else {
                        WaitingLine(count: 1) {}
                            .hidden()
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                }
                .padding(.bottom, 10)
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
        // While stop is held: the countdown card under the top bar, and the red screen edge.
        .overlay(alignment: .top) {
            if let since = stopHold?.heldSince {
                HexStopWarning(
                    since: since,
                    title: { String(localized: "stop.warning", defaultValue: "Stopping the agent in \($0)") },
                    detail: String(localized: "stop.warning.sub", defaultValue: "Let go to keep it running")
                )
                .padding(.horizontal, 12)
                .padding(.top, HexTopBarMetrics.homeHeight + 4)
                .transition(HexMotion.transition(.offset(y: 4).combined(with: .opacity), reduced: reduceMotion))
            }
        }
        .background {
            if stopHold?.heldSince != nil {
                HexStopEdge()
                    .transition(.opacity)
            }
        }
        .hexScreenBackground()
        // Magic Tap (two-finger double tap) starts or finishes a hands-free take, wherever focus is.
        .accessibilityAction(.magicTap) { Task { await home.toggleHandsFreeTake() } }
        .alert(
            Text(LocalizedStringResource("stop.confirm.title", defaultValue: "Stop the agent?")),
            isPresented: $confirmsStop
        ) {
            Button(role: .destructive) {
                Task { await session.stop() }
            } label: {
                Text(LocalizedStringResource("stop.confirm.stop", defaultValue: "Stop"))
            }
            Button(role: .cancel) {} label: {
                Text(LocalizedStringResource("stop.confirm.keep", defaultValue: "Keep running"))
            }
        } message: {
            Text(LocalizedStringResource("stop.confirm.body", defaultValue: "It stops now, with its subagents. Background shells keep running."))
        }
        // VoiceOver lands on the talk button first, after the top bar.
        .task {
            guard voiceOver else { return }
            try? await Task.sleep(for: .milliseconds(600))
            talkFocused = true
        }
        .hexAnimation(HexMotion.ease(HexMotion.fade), value: drafting)
        .hexAnimation(HexMotion.ease(HexMotion.fade), value: session.waitingCount)
        .hexAnimation(HexMotion.ease(HexMotion.fade), value: session.goalLine)
        .hexAnimation(HexMotion.ease(HexMotion.fade), value: session.ask.shown?.id)
        // The draft's ⋯ menu opens above its button; a tap anywhere else closes it.
        .overlayPreferenceValue(DraftMenuAnchor.self) { anchor in
            if home.draftMenuOpen, drafting, let anchor {
                GeometryReader { proxy in
                    let button = proxy[anchor]
                    ZStack(alignment: .topLeading) {
                        Color.clear
                            .contentShape(Rectangle())
                            .onTapGesture { home.draftMenuOpen = false }
                        // Right-aligned with the draft's actions, as the prototype's `.dmenu`.
                        DraftMenu(home: home)
                            // VoiceOver stays in the menu; the two-finger scrub closes it.
                            .accessibilityAddTraits(.isModal)
                            .accessibilityAction(.escape) { home.draftMenuOpen = false }
                            .frame(width: min(320, proxy.size.width - 2 * HexSpacing.screenHome))
                            .fixedSize(horizontal: false, vertical: true)
                            .alignmentGuide(.leading) { $0[.trailing] - (proxy.size.width - HexSpacing.screenHome) }
                            .alignmentGuide(.top) { $0[.bottom] - (button.minY - 8) }
                    }
                    .frame(width: proxy.size.width, height: proxy.size.height, alignment: .topLeading)
                }
                .transition(.opacity)
            }
        }
        .onChange(of: drafting) { _, now in if !now { home.draftMenuOpen = false } }
        .onChange(of: home.isRecording) { _, now in if now { home.draftMenuOpen = false } }
        .animation(.easeOut(duration: 0.18), value: stopHold?.heldSince)
        // The microphone gets ready (not started) while home is on screen.
        .onAppear { home.prepareRecorder() }
        .task(id: ObjectIdentifier(session)) {
            stopHold = StopHoldModel(
                toasts: home.toasts,
                canStop: { [session] in session.canStop },
                stop: { [session] in await session.stop() }
            )
        }
        #if DEBUG
        .task { await PressProbe.run(home) }
        .task { await LockedProbe.run(home) }
        // `-HexCodeDemoOpenArtifact YES`: a sample card's artifact opens once, when its tile shows.
        .onChange(of: session.summary.summary?.asCard.artifact, initial: true) { _, artifact in
            guard let artifact, DemoArtifactLaunch.take() else { return }
            openArtifact(artifact)
        }
        .task {
            try? await Task.sleep(for: .seconds(2))
            EdgeDeferral.report()
        }
        #endif
        .fullScreenCover(item: browserPresented) { token in
            BrowserScreen(model: token.model, onDone: { closeBrowser() })
        }
        .hexSheet(isPresented: Binding(get: { home.showsDraftReader }, set: { home.showsDraftReader = $0 })) {
            HexReader(
                title: Text(LocalizedStringResource("reader.draft", defaultValue: "Draft")),
                meta: ReaderMeta.text(duration: home.draft.totalDuration, words: home.draft.wordCount),
                paragraphs: home.draft.paragraphs,
                foot: HexReader.Foot(
                    discardLabel: Text(LocalizedStringResource("draft.clear", defaultValue: "Clear the draft")),
                    sendTitle: Text(LocalizedStringResource("home.send", defaultValue: "Send")),
                    sendEnabled: home.canSend,
                    onDiscard: {
                        home.showsDraftReader = false
                        home.clearDraft()
                    },
                    onSend: {
                        home.showsDraftReader = false
                        home.send()
                    }
                ),
                onDone: { home.showsDraftReader = false }
            )
        }
        .hexSheet(isPresented: Binding(get: { youSaid != nil }, set: { if !$0 { youSaid = nil } })) {
            if let youSaid {
                HexReader(
                    title: Text(LocalizedStringResource("reader.sent", defaultValue: "You said")),
                    meta: youSaid.meta,
                    note: youSaid.withLine,
                    paragraphs: youSaid.paragraphs,
                    onDone: { self.youSaid = nil }
                )
            }
        }
        .onChange(of: drafting) { _, now in if !now { home.showsDraftReader = false } }
        .hexFittedSheet(item: needsPresented) { item in
            NeedsSheet(item: item, session: session, home: home)
        }
        .hexSheet(isPresented: detailsPresented, onDismiss: { session.detailsDismissed() }) {
            DetailsSheet(session: session, home: home) { session.showsDetails = false }
        }
        .hexFittedSheet(item: goalSheetPresented) { _ in
            GoalSheet(session: session, home: home)
        }
        // A sheet with its own notices is up: they show on it, not under it.
        .onChange(of: session.presentedNeed != nil || session.showsDetails || session.showsGoalSheet, initial: true) { _, up in
            home.toasts.inSheet = up
        }
        .hexSheet(isPresented: $showsAttach, detents: [.height(AttachSheet.height)], onDismiss: openAttachRoute) {
            AttachSheet(
                filesAvailable: home.canAttachFiles,
                onPick: { route in
                    attachRoute = route
                    showsAttach = false
                },
                onClose: { showsAttach = false }
            )
        }
        .attachPhotosPicker(isPresented: $showsPhotos, limit: home.draft.remainingAttachments) { home.attach($0) }
        .attachFilesPicker(isPresented: $showsFiles) { home.attach(files: $0) }
        .fullScreenCover(isPresented: $showsCamera) {
            CameraPicker(onPhoto: { home.attach([PickedImage(photo: $0)]) }, onClose: { showsCamera = false })
                .ignoresSafeArea()
        }
    }

    private var browserPresented: Binding<BrowserToken?> {
        Binding(get: { browser.map { BrowserToken(model: $0) } }, set: { if $0 == nil { browser = nil } })
    }

    /// With Reduce Motion the browser shows and goes without animation.
    private func closeBrowser() {
        var transaction = Transaction()
        transaction.disablesAnimations = reduceMotion
        withTransaction(transaction) { browser = nil }
    }

    private var needsPresented: Binding<PendingItem?> {
        Binding(get: { session.presentedNeed }, set: { if $0 == nil { session.hideNeed() } })
    }

    private var detailsPresented: Binding<Bool> {
        Binding(get: { session.showsDetails }, set: { session.showsDetails = $0 })
    }

    private var goalSheetPresented: Binding<GoalSheetToken?> {
        Binding(get: { session.showsGoalSheet ? GoalSheetToken() : nil }, set: { if $0 == nil { session.showsGoalSheet = false } })
    }

    /// The paperclip: the Attach sheet, or the limit's notice with 4 images.
    private func attach() {
        if home.attachTapped() { showsAttach = true }
    }

    /// The system picker opens once the sheet has gone.
    private func openAttachRoute() {
        switch attachRoute {
        case .photos?: showsPhotos = true
        case .camera?: showsCamera = true
        case .files?: showsFiles = true
        case nil: break
        }
        attachRoute = nil
    }

    /// A tile: a page on the computer opens in the in-app browser, anything else in the system
    /// browser.
    private func open(_ destination: CardLinks.Destination) {
        if destination.inApp {
            var transaction = Transaction()
            transaction.disablesAnimations = reduceMotion
            withTransaction(transaction) {
                browser = BrowserModel(destination: destination, serverName: session.serverName ?? destination.shownHost)
            }
        } else {
            openURL(destination.url)
        }
    }

    /// The artifact tile: the in-app browser, every file loaded by the app. A `401` closes it and
    /// home shows Signed out; the `403` error's "Turn on…" opens the enable sheet.
    private func openArtifact(_ artifact: CardArtifact) {
        guard let model = artifactBrowser(artifact) else { return }
        model.onSignedOut = { [session] in
            closeBrowser()
            Task { await session.open() }
        }
        model.onTurnOn = { [artifacts] in
            closeBrowser()
            Task {
                // The sheet opens once the browser has gone.
                try? await Task.sleep(for: .milliseconds(450))
                await artifacts?.refresh()
                artifacts?.open()
            }
        }
        var transaction = Transaction()
        transaction.disablesAnimations = reduceMotion
        withTransaction(transaction) { browser = model }
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
                    HexIconButton(.clip, label: LocalizedStringResource("home.attach", defaultValue: "Attach"), action: attach)
                        .transition(.opacity)
                }
            }
            TalkButton(home: home, voice: voice)
                .accessibilityFocused($talkFocused)
            ZStack {
                if home.dock == .lockedTake {
                    // While a take is locked the agent's stop steps aside, so ✓ is never confused with it.
                    HexIconButton(.check, label: LocalizedStringResource("home.locked.done", defaultValue: "Done"), tone: .strong) {
                        Task { await home.finishLockedTake() }
                    }
                    .transition(.opacity)
                } else {
                    // Held for 3 s; dimmed (and a hold does nothing) unless the agent works or waits.
                    HexIconButton(
                        .stop,
                        label: LocalizedStringResource("home.stop", defaultValue: "Stop"),
                        tone: .stop,
                        enabled: session.canStop,
                        heldSince: stopHold?.heldSince,
                        press: HexPress(
                            began: { stopHold?.pressBegan() },
                            moved: { stopHold?.moved(outside: $0) },
                            ended: { stopHold?.pressEnded() }
                        )
                    )
                    // VoiceOver can't hold: activate asks first, then stops as the 3 s hold does.
                    .accessibilityLabel(session.canStop
                        ? Text(LocalizedStringResource("home.stop", defaultValue: "Stop"))
                        : Text(LocalizedStringResource("stop.dimmed", defaultValue: "Stop, nothing running")))
                    .accessibilityAction {
                        if session.canStop { confirmsStop = true }
                    }
                    .accessibilityShowsLargeContentViewer {
                        HexIconView(.stop, size: HexIconSize.regular)
                        Text(LocalizedStringResource("home.stop", defaultValue: "Stop"))
                    }
                    .transition(.opacity)
                }
            }
        }
        .hexAnimation(HexMotion.ease(HexMotion.fade), value: home.dock)
        // The dock grows with the text size up to xxxLarge, then stays; the Large Content Viewer
        // shows its buttons above that.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }
}

/// The agent's state on the left (Reconnecting…, Needs input, Working m:ss or Idle; empty while
/// today's session is set up), then ◎ (today's goal, `green` while it's active) and ⚙.
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
                HexRoundButton(
                    .target,
                    label: LocalizedStringResource("goal.button", defaultValue: "Today’s goal"),
                    on: session.goal?.isActive == true
                ) { session.openGoalSheet() }
                HexRoundButton(.gear, label: LocalizedStringResource("home.settings", defaultValue: "Settings"), action: onSettings)
            }
        }
        // VoiceOver hears it become Needs input or Reconnecting.
        .onChange(of: state?.announcement) { _, spoken in
            if let spoken { AccessibilityNotification.Announcement(spoken).post() }
        }
    }
}

/// The draft: a label, the chunks (as running text, or one row each once it is long), and the actions.
struct DraftView: View {
    let home: HomeModel
    /// Set while the draft shows as a 7-line window (a single take longer than 7 lines at 21).
    @Binding var windowed: Bool

    init(home: HomeModel, windowed: Binding<Bool> = .constant(false)) {
        self.home = home
        _windowed = windowed
    }

    private var draft: DictationDraft { home.draft }
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    /// The single take's text as drawn, for measuring it against the window.
    private var singleText: String? {
        guard draft.chunks.count == 1, let chunk = draft.chunks.first else { return nil }
        return chunk.text ?? chunk.partial
    }

    var body: some View {
        content
            .background(alignment: .topLeading) {
                if let singleText {
                    DraftWindowProbe(text: singleText) { windowed = $0 }
                }
            }
            .onChange(of: singleText == nil) { _, none in if none { windowed = false } }
            .onDisappear { windowed = false }
    }

    @ViewBuilder
    private var content: some View {
        Group {
            if DraftLayout.of(chunkCount: draft.chunks.count, fitsAtSmallest: true) == .rows
                || (voiceOver && !draft.chunks.isEmpty) {
                // With VoiceOver each chunk is a row with its position, text, Delete and Re-record.
                layout { ChunkListView(home: home) }
            } else if windowed, singleText != nil {
                layout { DraftWindow(home: home) }
            } else {
                // Running text, stepping down in size until the draft (label, text and actions),
                // as drawn, fits between its line and the dock; then rows, or a scrolling chunk.
                ViewThatFits(in: .vertical) {
                    ForEach(DraftSize.candidates(forCharacters: draft.shownCharacters), id: \.self) { size in
                        layout { DraftText(home: home, style: size.style) }
                    }
                    switch DraftLayout.whenTooLong(chunkCount: draft.chunks.count) {
                    case .rows:
                        layout { ChunkListView(home: home) }
                    default:
                        layout { DraftWindow(home: home) }
                    }
                }
            }
        }
        .hexAnimation(HexMotion.ease(HexMotion.fade), value: draft)
    }

    private func layout(@ViewBuilder _ content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            label
                .frame(minHeight: HomeLabel.height)
            if !draft.images.isEmpty || !draft.files.isEmpty {
                DraftImagesRow(home: home)
            }
            content()
            actions
                .opacity(home.isRecording ? 0 : 1)
                .allowsHitTesting(!home.isRecording)
        }
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
        // Images alone: only "Draft".
        guard !draft.chunks.isEmpty else { return String(localized: "draft.title", defaultValue: "Draft") }
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
                // Ask on the side and Set as goal.
                HexButton(icon: .more) { home.draftMenuOpen.toggle() }
                    .accessibilityLabel(Text(LocalizedStringResource("draft.more", defaultValue: "More")))
                    .anchorPreference(key: DraftMenuAnchor.self, value: .bounds) { $0 }
                HexButton(
                    home.takesPending
                        ? LocalizedStringResource("home.send.transcribing", defaultValue: "Transcribing")
                        : home.imagesPending
                        ? LocalizedStringResource("home.send.preparing", defaultValue: "Preparing")
                        : home.filesPending
                        ? LocalizedStringResource("home.send.uploading", defaultValue: "Uploading")
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
    var style: HexTextStyle = .draft
    /// Three lines of room by default, so the actions don't jump as text arrives.
    var minHeight: CGFloat = DraftText.minHeight

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HexChunk(
                items: home.draft.chunks.map { Self.item($0) },
                selectedID: home.draft.selectedID,
                interactive: !home.isRecording,
                style: style,
                onTap: { home.toggleChunk($0) }
            )
            .fixedSize(horizontal: false, vertical: true)
            if home.isRecording, home.draft.chunks.count == 1, !home.draft.isRerecording, home.draft.chunks[0].partial == nil {
                Text(LocalizedStringResource("draft.first_take_hint", defaultValue: "Text appears when you pause"))
                    .hexFont(.row)
                    .foregroundStyle(HexColor.text3)
            }
        }
        .frame(maxWidth: .infinity, minHeight: minHeight, alignment: .topLeading)
    }

    /// Three lines of the draft type, as in the prototype.
    static var minHeight: CGFloat {
        let style = HexTextStyle.draft
        return style.size * (style.lineHeight ?? 1.3) * 3 + 8
    }

    static func item(_ chunk: DraftChunk) -> HexChunkItem {
        switch chunk.state {
        case .listening:
            let listening = String(localized: "draft.listening", defaultValue: "Listening…")
            // A locked take's text so far, from the parts done while it records.
            if let partial = chunk.partial {
                return HexChunkItem(id: chunk.id, text: partial, trailing: listening)
            }
            return HexChunkItem(id: chunk.id, text: listening, kind: .placeholder)
        case .transcribing, .waiting:
            // A waiting take looks like one being transcribed; the label line says why it waits.
            let transcribing = String(localized: "draft.transcribing_chunk", defaultValue: "Transcribing…")
            if let partial = chunk.partial {
                return HexChunkItem(id: chunk.id, text: partial, trailing: transcribing)
            }
            return HexChunkItem(id: chunk.id, text: transcribing, kind: .placeholder)
        case let .text(text):
            return HexChunkItem(id: chunk.id, text: text)
        case let .failed(message):
            return HexChunkItem(id: chunk.id, text: message, kind: .failed)
        }
    }
}

extension DraftSize {
    var style: HexTextStyle {
        switch self {
        case .regular: .draft
        case .medium: .nowMedium
        case .small: .nowSmall
        case .fit: .nowFit
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
            text: DraftText.item(chunk).plainText,
            duration: live ? "" : DraftClock.format(chunk.duration),
            selected: selected,
            dimmed: dimmed || chunk.text == nil,
            live: live,
            action: { home.toggleChunk(chunk.id) }
        )
        .disabled(home.isRecording || chunk.text == nil && !isFailed(chunk))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(LocalizedStringResource("a11y.chunk", defaultValue: "Chunk \(index + 1) of \(home.draft.chunks.count)")))
        .accessibilityValue(Text(verbatim: DraftText.item(chunk).plainText))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
        .accessibilityActions {
            if !home.isRecording, chunk.text != nil || isFailed(chunk) {
                Button { home.deleteChunk(chunk.id) } label: {
                    Text(LocalizedStringResource("a11y.chunk.delete", defaultValue: "Delete"))
                }
                Button { home.rerecordChunk(chunk.id) } label: {
                    Text(LocalizedStringResource("draft.rerecord", defaultValue: "Re-record"))
                }
            }
        }
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

/// Centres its content in the room it has, as one unit, and offers it the whole height so a card
/// or draft can choose a size that fits; content taller than the room starts at the top (app plan,
/// "Layout" and "Long drafts").
struct CentredLayout: Layout {
    /// The content's top within a room of `room` points.
    static func top(contentHeight: CGFloat, room: CGFloat) -> CGFloat {
        max(0, (room - contentHeight) / 2)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let offer = ProposedViewSize(width: bounds.width, height: bounds.height)
        for subview in subviews {
            let height = min(subview.sizeThatFits(offer).height, bounds.height)
            let top = Self.top(contentHeight: height, room: bounds.height)
            subview.place(at: CGPoint(x: bounds.minX, y: bounds.minY + top), anchor: .topLeading, proposal: offer)
        }
    }
}

/// The draft's attachments (app plan, "Attach" › Thumbnails on the draft, and "Attach files
/// (M7)" › Draft row): 40 pt image thumbnails, then file tiles, each with ✕; a spinner while an
/// image is prepared, a ring while a file uploads; then one caption. Images are dimmed, with a
/// caption that says so, when today's model can't see them (files still go).
struct DraftImagesRow: View {
    let home: HomeModel

    var body: some View {
        let images = home.draft.images
        let files = home.draft.files
        let canSee = home.imageInput.canSee
        let percent = home.draft.uploadPercent
        HStack(spacing: 8) {
            ForEach(images) { image in
                HexThumbnail(
                    image: image.prepared?.thumbnail,
                    dimmed: !canSee,
                    onRemove: { home.removeImage(image.id) }
                )
                .transition(.opacity)
            }
            ForEach(files) { file in
                HexThumbnail(
                    file: .init(ext: file.extensionLabel, isVideo: file.kind == .video),
                    name: file.name,
                    progress: file.isUploading ? file.progress : nil,
                    onRemove: { home.removeFile(file.id) }
                )
                .transition(.opacity)
            }
            Text(verbatim: canSee || images.isEmpty || percent != nil
                ? AttachmentText.caption(images: images.count, files: files, uploadPercent: percent)
                : String(localized: "home.attach.no_vision", defaultValue: "\(home.imageInput.modelName) can’t see images · they won’t be sent"))
                .hexFont(.caption)
                .foregroundStyle(HexColor.text3)
                .lineLimit(2)
                // One file's name is cut in the middle, keeping its extension.
                .truncationMode(files.count == 1 && images.isEmpty ? .middle : .tail)
                // VoiceOver reads "Uploading · 45%" as "Uploading, 45 percent".
                .padding(.leading, 4)
        }
        // Room for the ✕ over the top corner.
        .padding(.top, 5)
        .hexAnimation(HexMotion.ease(HexMotion.fade), value: images)
        .hexAnimation(HexMotion.ease(HexMotion.fade), value: files.map(\.id))
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
/// `-HexCodeLockedProbe <n>` with `-HexCodeTakeAudio <wav>`: once the speech model is ready, n locked takes of
/// the clip, each finished (✓) as soon as the clip has played, printing when each part's text came and the wait
/// from release to the take's text (read them with `devicectl device process launch --console`). The draft is
/// cleared between takes. Debug builds only.
@MainActor
enum LockedProbe {
    private static var ran = false

    static func run(_ home: HomeModel) async {
        let count = UserDefaults.standard.integer(forKey: "HexCodeLockedProbe")
        guard count > 0, !ran, let capture = home.probeRecorder as? TakeCapture else { return }
        ran = true
        guard await home.speech.waitUntilReady() else {
            print("HEXPROBE locked: the speech model didn't load")
            return
        }
        try? await Task.sleep(for: .seconds(4))
        for cycle in 1 ... count {
            print("HEXPROBE locked take \(cycle)")
            await home.toggleHandsFreeTake()
            await capture.clipPlayed()
            await home.finishLockedTake()
            for _ in 0 ..< 600 where !home.draft.isComplete {
                try? await Task.sleep(for: .milliseconds(50))
            }
            print("HEXPROBE text: \(home.draft.text)")
            try? await Task.sleep(for: .seconds(4))
            if cycle < count { home.clearDraft() }
            try? await Task.sleep(for: .seconds(1))
        }
        print("HEXPROBE done")
    }
}

/// `-HexCodeDemoOpenArtifact YES` (`TodaySessionDemo`): the sample artifact opens once per launch.
@MainActor
enum DemoArtifactLaunch {
    private static var opened = false

    /// True the first time, with the launch argument and a sample session.
    static func take() -> Bool {
        guard !opened, TodaySessionDemo.opensArtifactAtLaunch else { return false }
        opened = true
        return true
    }
}

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

/// The in-app browser is open (a cover needs an item).
struct BrowserToken: Identifiable {
    let model: BrowserModel
    var id: ObjectIdentifier { ObjectIdentifier(model) }
}

/// Where the draft's ⋯ is, so its menu opens above it.
struct DraftMenuAnchor: PreferenceKey {
    static let defaultValue: Anchor<CGRect>? = nil

    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = value ?? nextValue()
    }
}

/// The draft's ⋯ menu (app plan, "Set as goal from a draft"): Ask on the side, and Set (or
/// Replace) today's goal. Both wait for every chunk, as Send does.
struct DraftMenu: View {
    let home: HomeModel

    var body: some View {
        let enabled = home.canUseMenuItems
        HexMenu(items: [
            HexMenuItem(
                id: "ask",
                icon: .ask,
                iconColor: HexColor.text2,
                title: String(localized: "draft.menu.ask", defaultValue: "Ask on the side"),
                detail: String(localized: "draft.menu.ask.sub", defaultValue: "Answered from the session, not sent to the agent"),
                enabled: enabled
            ) { home.askDraft() },
            HexMenuItem(
                id: "goal",
                icon: .target,
                iconColor: HexColor.green,
                title: home.hasGoal
                    ? String(localized: "draft.menu.goal.replace", defaultValue: "Replace today’s goal")
                    : String(localized: "draft.menu.goal", defaultValue: "Set as today’s goal"),
                detail: String(localized: "draft.menu.goal.sub", defaultValue: "The agent keeps going until it’s done"),
                enabled: enabled
            ) { home.setDraftAsGoal() },
        ])
    }
}

/// The goal sheet is open (a sheet needs an item).
struct GoalSheetToken: Identifiable {
    var id: Int { 0 }
}

/// The Ask message on home, with its age kept current.
struct AskMessageView: View {
    let ask: AskModel
    let toasts: HexToaster

    var body: some View {
        if let entry = ask.shown {
            TimelineView(.periodic(from: .now, by: 15)) { context in
                HexAskMessage(
                    question: entry.question,
                    age: AskAge.text(since: entry.askedAt, at: context.date),
                    state: Self.state(entry),
                    isOpen: ask.isOpen,
                    onOpen: { ask.expand() },
                    onFold: { ask.fold() },
                    onClose: { ask.close() },
                    onCopy: copy,
                    onRetry: { ask.retry() }
                )
            }
        }
    }

    static func state(_ entry: AskEntry) -> HexAskMessage.State {
        switch entry.state {
        case .asking:
            .asking
        case let .answered(answer):
            .answered(answer)
        case .empty:
            .problem(
                title: String(localized: "ask.empty", defaultValue: "No answer"),
                detail: String(localized: "ask.empty.sub", defaultValue: "Ask it another way, or send it as a message.")
            )
        case .failed:
            .problem(
                title: String(localized: "ask.failed", defaultValue: "Couldn’t answer"),
                detail: String(localized: "ask.failed.sub", defaultValue: "Try again.")
            )
        }
    }

    /// Copy: the answer goes to the clipboard, with "Answer copied" (iOS doesn't confirm copies).
    private func copy() {
        guard let answer = ask.answerToCopy else { return }
        UIPasteboard.general.string = answer
        toasts.show(HexToastItem(tone: .steer, title: String(localized: "ask.copied", defaultValue: "Answer copied"), duration: 1.6))
    }
}

/// Measures a single take at 21 (`nowMedium`) against 7 lines at the width it has (app plan, "Long
/// drafts"): longer, and the draft shows as the 7-line window.
private struct DraftWindowProbe: View {
    let text: String
    let onChange: (Bool) -> Void

    @State private var textHeight: CGFloat = 0
    @State private var windowHeight: CGFloat = .infinity

    var body: some View {
        ZStack(alignment: .topLeading) {
            Text(verbatim: text)
                .hexFont(.nowMedium)
                .fixedSize(horizontal: false, vertical: true)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { textHeight = $0; report() }
            Text(verbatim: Array(repeating: "Ag", count: DraftWindow.lines).joined(separator: "\n"))
                .hexFont(.nowMedium)
                .fixedSize(horizontal: false, vertical: true)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { windowHeight = $0; report() }
        }
        .hidden()
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }

    private func report() {
        onChange(textHeight > windowHeight + 1)
    }
}

/// A long single take (app plan, "Long drafts"): a 7-line window at 21 resting on the newest words.
/// It scrolls back; its top edge fades, except once scrolled to the start. Under it, "Read all ·
/// N words" opens the reader.
struct DraftWindow: View {
    static let lines = 7

    let home: HomeModel

    @State private var atStart = false
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 100

    private var lineHeight: CGFloat {
        let style = HexTextStyle.nowMedium
        return style.size * (style.lineHeight ?? 1.28) * scale / 100
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ScrollView(.vertical, showsIndicators: false) {
                DraftText(home: home, style: .nowMedium, minHeight: 0)
            }
            .defaultScrollAnchor(.bottom)
            .defaultScrollAnchor(.bottom, for: .sizeChanges)
            .frame(height: lineHeight * CGFloat(Self.lines))
            .onScrollGeometryChange(for: Bool.self) { geometry in
                geometry.contentOffset.y <= -geometry.contentInsets.top + 1
            } action: { _, start in
                atStart = start
            }
            .mask {
                if atStart {
                    Rectangle()
                } else {
                    VStack(spacing: 0) {
                        LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom)
                            .frame(height: lineHeight * 2)
                        Rectangle()
                    }
                }
            }
            if !home.isRecording {
                Button { home.showsDraftReader = true } label: {
                    HStack(spacing: 6) {
                        HexIconView(.chevU, size: HexIconSize.small)
                        Text(verbatim: String(localized: "draft.read_all", defaultValue: "Read all · \(home.draft.wordCount) words"))
                            .hexFont(.footnote)
                    }
                    .foregroundStyle(HexColor.text3)
                    .frame(minHeight: HexSize.touch)
                    .contentShape(Rectangle())
                }
                .buttonStyle(HexPressStyle())
            }
        }
    }
}
