import Foundation
import Observation

/// Today's session as home shows it: whether it opened, the agent's state for the top bar, a
/// pending question or approval, the waiting messages, and the status summary.
///
/// With a `LiveSessionAPI` and a stream it follows OpenCode: on open (and back in front on a new
/// date) it finds or creates today's session and puts the message rules, then opens the event
/// stream; on every (re)connect it reads the state again and, after the first, refreshes the
/// summary; a run's end refreshes it too. The sample sessions (`TodaySessionDemo`) use it without
/// either.
@MainActor
@Observable
final class TodaySession {
    enum Setup: Equatable {
        /// Finding or creating today's session. Silent: the status area and top bar state are empty.
        case opening
        /// "Couldn't open today's session" with Try again; `retrying` while Try again runs.
        case failed(retrying: Bool)
        case ready(sessionID: String)
    }

    /// The stream closes this long after the app goes to the background.
    static let backgroundGrace: Duration = .seconds(5)

    /// Waits; a test passes its own.
    @ObservationIgnored var sleep: @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }

    private(set) var setup: Setup = .opening
    var streamUp = true
    var pendingInput: PendingItem?
    /// When the current run started; nil while the session is idle.
    var busySince: Date?
    /// Messages queued in the inbox, sent after this turn.
    var waitingCount = 0
    let summary: StatusSummaryModel

    @ObservationIgnored private let dailySetup: DailySessionSetup
    @ObservationIgnored private let directory: () -> String
    @ObservationIgnored private let model: () -> ModelChoice
    @ObservationIgnored private let toasts: HexToaster
    @ObservationIgnored private let api: (any LiveSessionAPI)?
    @ObservationIgnored private let stream: LiveEventStream?
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let timeZone: TimeZone
    @ObservationIgnored private(set) var live: LiveSessionState?
    /// The local date today's session was opened for.
    @ObservationIgnored private var day: String?
    @ObservationIgnored private var connectedOnce = false
    @ObservationIgnored private var isOpening = false
    @ObservationIgnored private var backgroundStop: Task<Void, Never>?

    init(
        setup: DailySessionSetup,
        summary: StatusSummaryModel,
        directory: @escaping () -> String,
        model: @escaping () -> ModelChoice,
        toasts: HexToaster,
        api: (any LiveSessionAPI)? = nil,
        stream: LiveEventStream? = nil,
        now: @escaping () -> Date = Date.init,
        timeZone: TimeZone = .current
    ) {
        dailySetup = setup
        self.summary = summary
        self.directory = directory
        self.model = model
        self.toasts = toasts
        self.api = api
        self.stream = stream
        self.now = now
        self.timeZone = timeZone
        stream?.onConnected = { [weak self] in await self?.connected() }
        stream?.onDisconnected = { [weak self] in self?.streamUp = false }
        stream?.onEvent = { [weak self] in self?.received($0) }
        summary.isWorking = { [weak self] in self?.busySince != nil }
    }

    /// The top bar's state; nil (empty) until today's session is open.
    var agentState: AgentState? {
        guard case .ready = setup else { return nil }
        return AgentState.resolve(streamUp: streamUp, pendingInput: pendingInput != nil, busySince: busySince)
    }

    var sessionID: String? {
        if case let .ready(id) = setup { return id }
        return nil
    }

    // MARK: Opening

    /// On open, on Try again, and back in front on a new date: find or create today's session and
    /// put the rules, then follow it. A new session shows the empty card (no request); one that
    /// ran refreshes the summary.
    func open() async {
        guard !isOpening else { return }
        isOpening = true
        defer { isOpening = false }
        if case .failed = setup {
            setup = .failed(retrying: true)
        } else {
            setup = .opening
        }
        stream?.stop()
        let today = DailySession.title(for: now(), timeZone: timeZone)
        switch await dailySetup.open(today: today, directory: directory(), model: model()) {
        case .failed:
            setup = .failed(retrying: false)
        case let .opened(session, _, showsNewDayNotice):
            day = today
            live = LiveSessionState(rootID: session.id, hasRun: session.busySince != nil)
            publish()
            streamUp = true
            setup = .ready(sessionID: session.id)
            if showsNewDayNotice { toasts.show(Self.newDayNotice()) }
            summary.clear()
            if let api { summary.generator = SessionSummaryGenerator(api: api, sessionID: session.id) }
            connectedOnce = false
            stream?.start()
            await refreshSummary()
        }
    }

    /// Try again, after "Couldn't open today's session".
    func retry() async {
        guard case .failed(retrying: false) = setup else { return }
        await open()
    }

    // MARK: Foreground and background

    /// Back in front: on a new date (or after a failed setup) open today's session; else reconnect
    /// at once.
    func enteredForeground() async {
        backgroundStop?.cancel()
        backgroundStop = nil
        guard api != nil else { return }
        switch setup {
        case .failed(retrying: false):
            await open()
        case .ready:
            if day != DailySession.title(for: now(), timeZone: timeZone) {
                await open()
            } else {
                stream?.wake()
            }
        default:
            break
        }
    }

    /// The stream closes after the background grace; events missed meanwhile are read back on
    /// return.
    func enteredBackground() {
        guard let stream else { return }
        backgroundStop?.cancel()
        let sleep = sleep
        backgroundStop = Task { [weak self] in
            try? await sleep(Self.backgroundGrace)
            guard !Task.isCancelled else { return }
            stream.stop()
            self?.backgroundStop = nil
        }
    }

    // MARK: Model

    /// Settings › Project changed: open today's session in the new project (found or created, rules
    /// put, the stream reopened). The old project's session is left as it is.
    func projectChanged() async {
        guard api != nil else { return }
        await open()
    }

    /// Settings › Model changed: the session's model follows (`POST /api/session/{id}/model`).
    func switchModel(_ choice: ModelChoice) async {
        guard let api, let sessionID else { return }
        try? await api.switchModel(sessionID: sessionID, model: choice)
    }

    // MARK: Stream

    private func connected() async {
        streamUp = true
        guard let api, let rootID = live?.rootID else { return }
        if let snapshot = try? await api.snapshot(rootID: rootID), live?.rootID == rootID {
            live?.apply(snapshot)
            publish()
        }
        if connectedOnce { await refreshSummary() }
        connectedOnce = true
    }

    private func received(_ event: SessionEvent) {
        guard live != nil else { return }
        let effect = live?.apply(event)
        publish()
        if effect == .runEnded {
            Task { await summary.refresh() }
        }
    }

    private func publish() {
        busySince = live?.busySince
        pendingInput = live?.firstPending
        waitingCount = live?.waitingCount ?? 0
    }

    /// The empty card for a session that never ran; else a new summary.
    private func refreshSummary() async {
        if live?.hasRun == false {
            summary.showEmpty(timeZone: timeZone)
        } else {
            await summary.refresh()
        }
    }

    #if DEBUG
    /// Developer settings' "Drop event stream": as if the network dropped, held down for 10 s.
    func dropStream() {
        stream?.drop(holdFor: 10)
    }
    #endif

    // MARK: Taps that open M4's sheets

    /// Tapping Needs input or its hint card opens the question sheet, which comes in M4.
    func openPendingInput() {}

    /// Tapping the progress or the waiting line opens Details, which comes in M4.
    func openDetails() {}

    static func newDayNotice() -> HexToastItem {
        HexToastItem(
            tone: .sun,
            title: String(localized: "home.newday.title", defaultValue: "New day, new session"),
            detail: String(localized: "home.newday.detail", defaultValue: "Yesterday’s is kept on your computer."),
            duration: 3.6
        )
    }
}
