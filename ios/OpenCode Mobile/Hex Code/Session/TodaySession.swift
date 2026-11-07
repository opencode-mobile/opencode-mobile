import Foundation
import Observation
import OpenCodeKit
import os
import UIKit

/// Today's session as home shows it: whether it opened, the agent's state for the top bar, a
/// pending question or approval, the waiting messages, and the status summary.
///
/// With a `LiveSessionAPI` and a stream it follows OpenCode: on open (and back in front on a new
/// date) it finds or creates today's session and puts the message rules, then opens the event
/// stream; on every (re)connect it reads the state again and then refreshes the summary (the saved
/// card shows until then); a run's end refreshes it too. The sample sessions (`TodaySessionDemo`) use it without
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
        /// "Signed out of <name>" with Pair again: the server answered `401` to the saved sign-in.
        case signedOut
    }

    /// The stream closes this long after the app goes to the background.
    static let backgroundGrace: Duration = .seconds(5)

    /// Waits; a test passes its own.
    @ObservationIgnored var sleep: @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }

    private(set) var setup: Setup = .opening
    var streamUp = true
    /// The oldest question or approval, for the hint card.
    var pendingInput: PendingItem?
    /// Every question and approval waiting, oldest first (one queue).
    private(set) var pendingItems: [PendingItem] = []
    /// The question or approval whose sheet is open.
    private(set) var presentedNeed: PendingItem?
    /// Details is open: Running now and Recent are read, and kept up to date.
    var showsDetails = false {
        didSet { detailsChanged(from: oldValue) }
    }
    /// The latest edits, commands and subagent starts, for Details.
    private(set) var recentSteps: [RecentStep] = []
    /// The state Details reads Running now from.
    private(set) var liveState: LiveSessionState?
    /// When the current run started; nil while the session is idle.
    var busySince: Date?
    /// Messages queued in the inbox, sent after this turn.
    var waitingCount = 0
    /// The paired computer's address: a tile's `localhost` link opens there.
    var computerHost: String?
    /// The paired computer's name, for "Signed out of <name>".
    var serverName: String?
    /// Runs before each setup: the server's version, the address in use and the sign-in's renewal
    /// (`ServerUpkeep`). Signed out stops setup there.
    @ObservationIgnored var serverCheck: (() async -> UpkeepOutcome)?
    /// Those messages, in order.
    var waitingMessages: [InboxEntry] = []
    /// Every message not delivered yet (after this turn or at the next step), for Details.
    private(set) var undeliveredMessages: [InboxEntry] = []
    /// The phone's thumbnails of the images it sent, by inbox id, until delivered or cancelled.
    private(set) var waitingThumbnails: [String: [UIImage]] = [:]
    /// The agent's to-do list (`todowrite`), cancelled items left out; empty for none.
    var todos: [TodoItem] = []
    /// Subagents and shell commands running, for the Details link.
    var runningCount = 0
    /// While a message is in flight (app plan, "Just sent"): from its `session.inbox.delivered`
    /// until the card made after that run ends, the Next line reads "You: “…”".
    var sentMessage: String?
    /// The You line is a goal set from a draft: "Goal: “…”".
    private(set) var sentIsGoal = false
    /// Today's goal; nil for none. From `get` on open and every reconnect, then from
    /// `rpc.opencode.goal.changed`.
    private(set) var goal: TodayGoal?
    /// The goal sheet is open.
    var showsGoalSheet = false
    /// Ask on the side: the message on home and its history.
    let ask: AskModel
    let summary: StatusSummaryModel

    @ObservationIgnored private let dailySetup: DailySessionSetup
    @ObservationIgnored private let directory: () -> String
    @ObservationIgnored private let model: () -> ModelChoice
    @ObservationIgnored private let toasts: HexToaster
    @ObservationIgnored private let api: (any LiveSessionAPI)?
    @ObservationIgnored private let control: (any SessionControlAPI)?
    @ObservationIgnored private let goals: (any GoalAPI)?
    /// Each goal round counts as a turn end for the summary.
    @ObservationIgnored private var goalRounds = GoalRounds()
    /// Goal events seen, to tell whether one came after a command or during a `get`.
    @ObservationIgnored private var goalEvents = 0
    @ObservationIgnored private let stream: LiveEventStream?
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let timeZone: TimeZone
    @ObservationIgnored private(set) var live: LiveSessionState?
    /// The local date today's session was opened for.
    @ObservationIgnored private var day: String?
    @ObservationIgnored private var isOpening = false
    @ObservationIgnored private var backgroundStop: Task<Void, Never>?
    /// Publishes again when a young shell has run long enough to count as running.
    @ObservationIgnored private var shellTick: Task<Void, Never>?
    /// What the phone sent, by inbox id, until it is delivered.
    @ObservationIgnored private var sentTexts: [String: String] = [:]
    /// The last delivered message whose text wasn't known yet (its send hadn't returned).
    @ObservationIgnored private var deliveredWithoutText: String?
    /// The run the sent message started (or joined) has ended: the next card made while idle
    /// replaces the You line.
    @ObservationIgnored private var sentRunEnded = false
    /// A message was sent to today's session from this phone since it opened: it is no longer
    /// empty, even before its run starts.
    @ObservationIgnored private var messageSent = false
    /// The latest inbox ids delivered or cancelled.
    @ObservationIgnored private var leftInbox: [String] = []
    /// Questions and approvals being answered from this phone: their leaving isn't "No longer needed".
    @ObservationIgnored private var answering: Set<String> = []
    /// Opens the next question or approval after one is answered.
    @ObservationIgnored private var nextNeed: Task<Void, Never>?
    /// After Stop: one more interrupt if a subagent's note wakes today's session.
    @ObservationIgnored private var stopRetry: StopRetry?
    /// Reads Recent again a moment after a tool finishes, while Details is open.
    @ObservationIgnored private var recentReread: Task<Void, Never>?

    init(
        setup: DailySessionSetup,
        summary: StatusSummaryModel,
        directory: @escaping () -> String,
        model: @escaping () -> ModelChoice,
        toasts: HexToaster,
        api: (any LiveSessionAPI)? = nil,
        control: (any SessionControlAPI)? = nil,
        goals: (any GoalAPI)? = nil,
        stream: LiveEventStream? = nil,
        now: @escaping () -> Date = Date.init,
        timeZone: TimeZone = .current,
        ask: AskModel? = nil
    ) {
        dailySetup = setup
        self.summary = summary
        self.goals = goals
        self.ask = ask ?? AskModel()
        self.directory = directory
        self.model = model
        self.toasts = toasts
        self.api = api
        self.control = control
        self.stream = stream
        self.now = now
        self.timeZone = timeZone
        stream?.onConnected = { [weak self] in await self?.connected() }
        stream?.onDisconnected = { [weak self] in self?.streamUp = false }
        stream?.onEvent = { [weak self] in self?.received($0) }
        summary.isWorking = { [weak self] in self?.busySince != nil }
        summary.onCard = { [weak self] busy in self?.cardMade(busy: busy) }
        // An Ask finds the session gone (`404`): setup runs again, as for any request.
        self.ask.onSessionGone = { [weak self] in Task { await self?.open() } }
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
        let directory = directory()
        // Today's saved card for this project shows at once, while setup finds the session.
        summary.preopen(day: today, directory: directory)
        if let serverCheck, await serverCheck() == .signedOut {
            summary.clear()
            setup = .signedOut
            return
        }
        switch await dailySetup.open(today: today, directory: directory, model: model()) {
        case .failed:
            setup = .failed(retrying: false)
        case .signedOut:
            summary.clear()
            setup = .signedOut
        case let .opened(session, _, showsNewDayNotice):
            // A goal still running on yesterday's session is left as it is; the notice says so.
            let yesterdaysGoalRuns = showsNewDayNotice ? await yesterdaysGoalIsActive() : false
            await follow(session, day: today)
            if showsNewDayNotice { toasts.show(Self.newDayNotice(goalRunning: yesterdaysGoalRuns)) }
        }
    }

    /// Follows `session` from now on: its state, summary, Ask and goal; the stream filters for it.
    /// A different session than before starts with nothing carried over from the last one.
    private func follow(_ session: SessionListing, day today: String) async {
        let changed = live?.rootID != session.id
        day = today
        presentedNeed = nil
        recentSteps = []
        stopRetry = nil
        // A goal belongs to its session; it is read again once the stream is up.
        goal = nil
        goalRounds = GoalRounds()
        showsGoalSheet = false
        if changed {
            sentMessage = nil
            sentIsGoal = false
            sentRunEnded = false
            sentTexts = [:]
            deliveredWithoutText = nil
            waitingThumbnails = [:]
            showsDetails = false
        }
        live = LiveSessionState(rootID: session.id, hasRun: session.busySince != nil)
        messageSent = false
        publish()
        streamUp = true
        setup = .ready(sessionID: session.id)
        summary.open(sessionID: session.id)
        if let api { summary.generator = SessionSummaryGenerator(api: api, sessionID: session.id) }
        ask.open(sessionID: session.id)
        if let api {
            let id = session.id
            ask.generate = { prompt in try await api.generate(sessionID: id, prompt: prompt) }
        }
        stream?.start()
        if stream == nil || isEmptySession {
            // A session that never ran shows its empty card now; with no stream, nothing will
            // tell us more.
            await refreshSummary()
        }
        // Otherwise the first summary waits for the stream's read-back, which says whether the
        // agent is working (an empty reply then isn't a failure); the saved card shows meanwhile.
    }

    // MARK: Start a new session

    enum NewSessionResult: Equatable, Sendable {
        /// Home follows the new session.
        case started
        /// Creating it failed: today's session is still open, and the sheet stays.
        case failed
        /// It was made but its rules failed: "Couldn't open today's session", whose Try again finds it.
        case setupFailed
    }

    /// Settings › Start a new session: the day's next numbered session, started empty with the
    /// same project, model and level. The old one is never changed: no interrupt, no archive, no
    /// inbox cancel, and its goal keeps running. The draft stays on the phone.
    func startNewSession() async -> NewSessionResult {
        guard api != nil, case .ready = setup, !isOpening else {
            toasts.show(Self.newSessionFailedNotice())
            return .failed
        }
        isOpening = true
        defer { isOpening = false }
        let today = DailySession.title(for: now(), timeZone: timeZone)
        switch await dailySetup.startNew(today: today, directory: directory(), model: model()) {
        case .createFailed:
            toasts.show(Self.newSessionFailedNotice())
            return .failed
        case .rulesFailed:
            stream?.stop()
            setup = .failed(retrying: false)
            return .setupFailed
        case let .opened(session):
            stream?.stop()
            // The old session's card isn't today's any more.
            summary.forgetSaved()
            await follow(session, day: today)
            toasts.show(Self.newSessionNotice())
            return .started
        }
    }

    static func newSessionNotice() -> HexToastItem {
        HexToastItem(
            tone: .sun,
            title: String(localized: "settings.new_session.done", defaultValue: "New session"),
            detail: String(localized: "settings.new_session.done.sub", defaultValue: "The earlier one is kept on your computer."),
            duration: 2.6
        )
    }

    static func newSessionFailedNotice() -> HexToastItem {
        HexToastItem(
            tone: .stop,
            title: String(localized: "settings.new_session.failed", defaultValue: "Couldn’t start a new session"),
            detail: String(localized: "settings.new_session.failed.sub", defaultValue: "Today’s session is still open.")
        )
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

    /// This session is no longer shown (Forget, or another computer): its stream closes.
    func close() {
        backgroundStop?.cancel()
        stream?.stop()
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

    /// Settings › Model changed: the session's model follows (`POST /api/session/{id}/model`). A
    /// switch moves `time.updated`; the card stays current, so its basis moves with it.
    func switchModel(_ choice: ModelChoice) async {
        guard let api, let sessionID else { return }
        guard (try? await api.switchModel(sessionID: sessionID, model: choice)) != nil else { return }
        if let updated = try? await api.sessionUpdated(sessionID: sessionID), self.sessionID == sessionID {
            summary.moveBasis(to: updated)
        }
    }

    // MARK: Stream

    private func connected() async {
        streamUp = true
        guard let api, let rootID = live?.rootID else { return }
        // Today's goal is read on every (re)connect, beside the rest of the state.
        Task { await readGoal() }
        guard let snapshot = try? await api.snapshot(rootID: rootID), live?.rootID == rootID else {
            // The state couldn't be read: whether anything is new is unknown.
            await refreshSummary()
            return
        }
        live?.apply(snapshot)
        waitingThumbnails = waitingThumbnails.filter { id, _ in snapshot.inbox.contains { $0.id == id } }
        publish()
        if showsDetails { Task { await refreshDetails() } }
        await summaryOnReconnect(updated: snapshot.updated)
    }

    /// Open, foreground and reconnect (app plan, "Nothing new"): no request while the agent works
    /// (the run's end refreshes), nor when the card was made while idle from the session's
    /// `time.updated` as it is now.
    private func summaryOnReconnect(updated: Int64?) async {
        if isEmptySession {
            summary.showEmpty(timeZone: timeZone)
        } else if live?.hasRun == false, busySince == nil {
            // Sent to, but its run hasn't started: nothing to summarise yet.
            summary.showWorkingOnMessage()
        } else if busySince != nil {
            HexLog.summary.info("summary: busy, no request")
            summary.showWorkingIfEmpty()
        } else if let updated, summary.isCurrent(updated: updated) {
            HexLog.summary.info("summary: nothing new, no request")
        } else {
            await summary.refresh()
        }
    }

    private func received(_ event: SessionEvent) {
        guard let rootID = live?.rootID else { return }
        let effect = live?.apply(event)
        if case let .inboxRemoved(_, id) = event { inboxLeft(id) }
        publish()
        if stopRetry?.observe(event, at: now(), busy: busySince != nil) == true {
            HexLog.summary.info("stop: a subagent's note woke today's session; interrupting it once more")
            Task { _ = try? await control?.interrupt(sessionID: rootID) }
        }
        if showsDetails, case let .toolFinished(sessionID) = event, sessionID == rootID {
            rereadRecent()
        }
        // Only today's session's goal; subagents never have goals.
        if case let .goalChanged(sessionID, newGoal) = event, sessionID == rootID {
            goalChanged(newGoal)
        }
        switch effect {
        case .runEnded:
            if sentMessage != nil { sentRunEnded = true }
            // Never dropped: a mid-run card from ↻ or a goal round still on its way is followed by
            // this one.
            Task { await summary.refresh(runEnd: true) }
        case let .delivered(id, text):
            inboxLeft(id)
            if let text = sentTexts.removeValue(forKey: id) ?? text {
                showSent(text)
            } else {
                deliveredWithoutText = id
            }
        case nil:
            break
        }
    }

    /// A message left the inbox (delivered or cancelled): its thumbnails go. A send that returns
    /// after that keeps none.
    private func inboxLeft(_ id: String) {
        waitingThumbnails[id] = nil
        leftInbox.append(id)
        if leftInbox.count > 20 { leftInbox.removeFirst() }
    }

    /// A later delivered message replaces the You line.
    private func showSent(_ text: String, goal: Bool = false) {
        sentMessage = text
        sentIsGoal = goal
        sentRunEnded = false
    }

    /// The card made after the sent message's run ended replaces the You line.
    private func cardMade(busy: Bool) {
        guard !busy, sentRunEnded else { return }
        sentMessage = nil
        sentIsGoal = false
        sentRunEnded = false
    }

    private func publish() {
        busySince = live?.busySince
        pendingInput = live?.firstPending
        pendingItems = live?.pending ?? []
        waitingCount = live?.waitingCount ?? 0
        waitingMessages = live?.waiting ?? []
        undeliveredMessages = live?.undelivered ?? []
        liveState = live
        // The sheet's question or approval left without this phone answering it.
        if let shown = presentedNeed, !answering.contains(shown.id), !pendingItems.contains(where: { $0.id == shown.id }) {
            presentedNeed = nil
            toasts.show(Self.goneNotice())
        }
        todos = live?.todos ?? []
        if busySince != nil || waitingCount > 0 { summary.showWorkingOnMessage() }
        let date = now()
        runningCount = live?.runningCount(at: date) ?? 0
        shellTick?.cancel()
        shellTick = nil
        if let next = live?.nextShellCount(after: date) {
            let delay = next.timeIntervalSince(date)
            shellTick = Task { [weak self] in
                try? await Task.sleep(for: .seconds(delay))
                guard !Task.isCancelled else { return }
                self?.publish()
            }
        }
    }

    /// A session that never ran and has nothing sent or waiting (app plan, "Empty session").
    private var isEmptySession: Bool {
        guard let live else { return false }
        return !live.hasRun && !messageSent && live.inbox.isEmpty && live.busySince == nil
    }

    /// The empty card for a session that never ran; else a new summary.
    private func refreshSummary() async {
        if isEmptySession {
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

    // MARK: Sending

    struct NotOpen: Error {}

    /// The agent is working (or waiting on you): a message can go after this turn or now.
    var agentBusy: Bool { busySince != nil || pendingInput != nil }

    /// Sends a message, with its images, to today's session; returns its inbox id.
    func send(_ text: String, images: [OutgoingImage], delivery: Delivery) async throws -> String {
        guard let api, let sessionID else { throw NotOpen() }
        #if DEBUG
        // `-HexCodeFailSends YES`: every send fails, as without a network.
        if UserDefaults.standard.bool(forKey: "HexCodeFailSends") { throw URLError(.notConnectedToInternet) }
        #endif
        let id = try await api.prompt(sessionID: sessionID, text: text, images: images, delivery: delivery)
        messageSent = true
        summary.showWorkingOnMessage()
        if !images.isEmpty, !leftInbox.contains(id) {
            waitingThumbnails[id] = images.prefix(DictationDraft.maxImages).map(\.image.thumbnail)
        }
        if deliveredWithoutText == id {
            // Delivered before the send returned, with no text from the inbox.
            deliveredWithoutText = nil
            showSent(text)
        } else {
            sentTexts[id] = text
        }
        return id
    }

    /// Takes back a message still waiting to send.
    func cancelWaiting(_ itemID: String) async throws {
        guard let api, let sessionID else { throw NotOpen() }
        try await api.cancelInbox(sessionID: sessionID, itemID: itemID)
        sentTexts[itemID] = nil
        inboxLeft(itemID)
        // The cancelled event removes it too; this doesn't wait for it.
        _ = live?.apply(.inboxRemoved(sessionID: sessionID, id: itemID))
        publish()
    }

    /// Returns once the event stream is up (at once when it is).
    func waitForConnection() async {
        while !streamUp, !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(200))
        }
    }

    // MARK: Needs you

    /// The hint card, Needs input in the top bar, or a Needs you row in Details: the sheet for that
    /// item, else the oldest.
    func openPendingInput(_ id: String? = nil) {
        nextNeed?.cancel()
        presentedNeed = id.flatMap { id in pendingItems.first { $0.id == id } } ?? pendingItems.first
    }

    /// Hide: the sheet closes unanswered; the hint stays.
    func hideNeed() {
        nextNeed?.cancel()
        presentedNeed = nil
    }

    /// The subagent's title, for "From a subagent · …"; nil for today's session's own requests.
    func subagentTitle(of item: PendingItem) -> String? {
        guard let live, item.sessionID != live.rootID else { return nil }
        let child = live.childSessions[item.sessionID]
        return [child?.title, child?.agent].compactMap { $0 }.first { !$0.isEmpty } ?? item.sessionID
    }

    /// Today's session's project, once read: `global` hides Always.
    var projectID: String? { live?.projectID }

    /// Allow once, Always or Deny. Deny always sends a note (the spoken one, else the default), so
    /// the agent carries on.
    func reply(to item: PendingItem, _ decision: SessionStateAPIClient.PermissionDecision, note: String? = nil) async -> NeedsReply {
        let message = decision == .reject ? Self.denyNote(spoken: note) : nil
        return await respond(item, refusesOthers: decision == .reject) { control in
            try await control.replyToApproval(sessionID: item.sessionID, requestID: item.id, decision: decision, note: message)
        }
    }

    /// Submit: every page's answer in one reply.
    func submit(_ item: PendingItem, answer: [String: FormAnswerV2]) async -> NeedsReply {
        await respond(item) { control in
            try await control.answerQuestion(sessionID: item.sessionID, formID: item.id, answer: answer)
        }
    }

    /// Dismiss: the form goes with the note, so the run goes on.
    func dismiss(_ item: PendingItem) async -> NeedsReply {
        await respond(item) { control in
            try await control.dismissQuestion(sessionID: item.sessionID, formID: item.id, note: SessionPrompts.Needs.dismissNote)
        }
    }

    static func denyNote(spoken: String?) -> String {
        let spoken = spoken?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return spoken.isEmpty ? SessionPrompts.Needs.denyNote : spoken
    }

    /// Sent: the item leaves the queue, the sheet closes, and the next one opens 0.3 s later.
    /// Gone (`404`, `409`): the same, with "No longer needed". Failed: the sheet stays.
    private func respond(
        _ item: PendingItem,
        refusesOthers: Bool = false,
        _ call: @escaping (any SessionControlAPI) async throws -> Void
    ) async -> NeedsReply {
        guard let control else { return .failed }
        answering.insert(item.id)
        defer { answering.remove(item.id) }
        let outcome: NeedsReply
        do {
            try await call(control)
            outcome = .sent
        } catch ControlError.gone {
            outcome = .gone
        } catch {
            HexLog.summary.error("needs: couldn't answer \(item.id, privacy: .public): \(String(describing: error), privacy: .public)")
            toasts.show(Self.failedNotice())
            return .failed
        }
        live?.answered(item, refusedOthers: refusesOthers && outcome == .sent)
        if presentedNeed?.id == item.id { presentedNeed = nil }
        publish()
        if outcome == .gone {
            toasts.show(Self.goneNotice())
        } else {
            openNextNeed()
        }
        return outcome
    }

    /// The next waiting item opens 0.3 s after the sheet closed.
    private func openNextNeed() {
        nextNeed?.cancel()
        guard !pendingItems.isEmpty else { return }
        nextNeed = Task { [weak self] in
            try? await Task.sleep(for: Self.nextNeedDelay)
            guard !Task.isCancelled, let self, self.presentedNeed == nil, !self.showsDetails else { return }
            self.presentedNeed = self.pendingItems.first
        }
    }

    static let nextNeedDelay: Duration = .milliseconds(300)

    static func goneNotice() -> HexToastItem {
        HexToastItem(
            tone: .info,
            title: String(localized: "needs.gone", defaultValue: "No longer needed"),
            detail: String(localized: "needs.gone.sub", defaultValue: "It was answered elsewhere or the run ended.")
        )
    }

    static func failedNotice() -> HexToastItem {
        HexToastItem(
            tone: .stop,
            title: String(localized: "needs.failed", defaultValue: "Couldn’t send"),
            detail: String(localized: "needs.failed.sub", defaultValue: "Try again.")
        )
    }

    // MARK: Stop

    /// The stop button is lit: the agent works or waits for you.
    var canStop: Bool {
        switch agentState {
        case .working?, .needsInput?: true
        default: false
        }
    }

    /// The 3 s hold is done: interrupt today's session, then each running subagent; if a subagent's
    /// note wakes the session within 5 s, interrupt it once more. Background shells keep running.
    /// The server pauses the goal itself.
    func stop() async {
        guard let control, let rootID = live?.rootID else {
            toasts.show(Self.stopFailedNotice())
            return
        }
        stopRetry = StopRetry(rootID: rootID, stoppedAt: now())
        var stopped: Bool
        do {
            stopped = try await control.interrupt(sessionID: rootID)
        } catch {
            stopRetry = nil
            HexLog.summary.error("stop: couldn't interrupt: \(String(describing: error), privacy: .public)")
            toasts.show(Self.stopFailedNotice())
            return
        }
        let children = (try? await control.runningSubagents(rootID: rootID)) ?? Array(live?.runningChildren ?? [])
        for child in children {
            // A subagent stopped counts too: it may be all that was running.
            if (try? await control.interrupt(sessionID: child)) == true { stopped = true }
        }
        HexLog.summary.info("stop: interrupted today's session and \(children.count) subagents")
        toasts.show(stopped ? Self.stoppedNotice(waiting: (live?.waitingCount ?? 0) > 0) : Self.nothingToStopNotice())
    }

    static func stoppedNotice(waiting: Bool) -> HexToastItem {
        HexToastItem(
            tone: .stop,
            title: String(localized: "stop.done", defaultValue: "Stopped"),
            detail: waiting
                ? String(localized: "stop.done.waiting", defaultValue: "Waiting messages send after your next one.")
                : String(localized: "stop.done.sub", defaultValue: "Talk to start again."),
            duration: 3.2
        )
    }

    static func nothingToStopNotice() -> HexToastItem {
        HexToastItem(
            tone: .info,
            title: String(localized: "stop.nothing", defaultValue: "Nothing to stop"),
            detail: String(localized: "stop.nothing.sub", defaultValue: "The agent had already finished.")
        )
    }

    static func stopFailedNotice() -> HexToastItem {
        HexToastItem(
            tone: .stop,
            title: String(localized: "stop.failed", defaultValue: "Couldn’t stop"),
            detail: String(localized: "stop.failed.sub", defaultValue: "Check the connection and hold again.")
        )
    }

    static func holdToStopNotice() -> HexToastItem {
        HexToastItem(
            tone: .stop,
            title: String(localized: "stop.hold", defaultValue: "Hold to stop"),
            detail: String(localized: "stop.hold.sub", defaultValue: "Keep pressing for 3 seconds. A tap does nothing.")
        )
    }

    static func stopCancelledNotice() -> HexToastItem {
        HexToastItem(
            tone: .info,
            title: String(localized: "stop.cancelled", defaultValue: "Stop cancelled"),
            detail: String(localized: "stop.cancelled.sub", defaultValue: "The agent keeps running."),
            duration: 2.2
        )
    }

    // MARK: Details

    /// "Details ›", the to-do row and the waiting line open Details.
    func openDetails() {
        showsDetails = true
    }

    /// A Needs you row: Details closes, then that item's sheet opens.
    @ObservationIgnored private var needAfterDetails: String?

    func openFromDetails(_ id: String) {
        needAfterDetails = id
        showsDetails = false
    }

    /// Details has gone: a Needs you row's sheet opens now, or an Asked on the side row shows.
    func detailsDismissed() {
        if let ask = askAfterDetails {
            askAfterDetails = nil
            self.ask.openFromHistory(ask)
        }
        guard let id = needAfterDetails else { return }
        needAfterDetails = nil
        openPendingInput(id)
    }

    private func detailsChanged(from wasOpen: Bool) {
        guard showsDetails != wasOpen else { return }
        if showsDetails {
            Task { await refreshDetails() }
        } else {
            recentReread?.cancel()
            recentReread = nil
        }
    }

    /// Running now (running subagents and agent shells) and Recent, read on opening and on a
    /// reconnect while open.
    func refreshDetails() async {
        guard let control, let rootID = live?.rootID else { return }
        async let running = try? control.running(rootID: rootID, directory: directory())
        async let recent = try? control.recentTools(sessionID: rootID)
        if let running = await running, live?.rootID == rootID {
            live?.apply(running: running)
            publish()
        }
        if let recent = await recent, live?.rootID == rootID {
            recentSteps = RecentSteps.make(recent)
        }
    }

    /// Recent again 1 s after a tool of today's session finishes.
    private func rereadRecent() {
        recentReread?.cancel()
        recentReread = Task { [weak self] in
            try? await Task.sleep(for: RecentSteps.rereadDelay)
            guard !Task.isCancelled, let self, self.showsDetails, let control = self.control, let rootID = self.live?.rootID else { return }
            if let parts = try? await control.recentTools(sessionID: rootID), self.live?.rootID == rootID {
                self.recentSteps = RecentSteps.make(parts)
            }
        }
    }

    /// Running now at `date`: at most 3 rows, how many more, and the total.
    func running(at date: Date) -> (rows: [RunningRow], more: Int, total: Int) {
        guard let liveState else { return ([], 0, 0) }
        return RunningNow.rows(liveState, at: date)
    }

    // MARK: Goal

    /// No goal event within this long of a `204`: the app reads `get`.
    static let goalEventWait: Duration = .seconds(2)

    /// The line under the top bar; nil for no line (no goal while the agent works, or before
    /// today's session is open).
    var goalLine: GoalLineState? {
        guard case .ready = setup else { return nil }
        return GoalLineState.of(goal, busy: agentBusy)
    }

    /// The top bar's ◎, the goal line.
    func openGoalSheet() {
        showsGoalSheet = true
    }

    /// The goal from an event replaces the one kept; a higher round refreshes the summary as a
    /// mid-run card.
    private func goalChanged(_ newGoal: TodayGoal?) {
        goalEvents += 1
        goal = newGoal
        if goalRounds.observe(newGoal) {
            HexLog.summary.info("goal: round \(newGoal?.rounds ?? 0); refreshing the summary")
            Task { await summary.refresh() }
        }
    }

    /// `get`: on open, every reconnect, after a `500`, and after a command whose event didn't come.
    /// An event that arrives meanwhile is newer and wins.
    func readGoal() async {
        guard let goals, let rootID = live?.rootID else { return }
        let events = goalEvents
        let read: TodayGoal?
        do {
            read = try await goals.goal(sessionID: rootID, directory: directory())
        } catch {
            HexLog.summary.error("goal: couldn't read it: \(String(describing: error), privacy: .public)")
            return
        }
        guard live?.rootID == rootID, goalEvents == events else { return }
        goal = read
        goalRounds.read(read)
    }

    /// Whether the goal on yesterday's session (the one open before a new day's) is still active.
    private func yesterdaysGoalIsActive() async -> Bool {
        guard let goals, let id = dailySetup.yesterdaySessionID else { return false }
        let goal = try? await goals.goal(sessionID: id, directory: directory())
        return goal?.isActive ?? false
    }

    /// Set or Replace, from the sheet's take or a draft (with its images). Refused on the phone when
    /// it would read as a command or is too long. On `204` the sheet closes; a goal from a draft
    /// makes the You line "Goal: “…”" until the next card.
    @discardableResult
    func setGoal(_ text: String, images: [OutgoingImage] = [], fromDraft: Bool = false) async -> GoalOutcome {
        if let refusal = GoalText.refusal(text) {
            toasts.show(Self.goalRefusedNotice(refusal))
            return .refused(refusal)
        }
        let objective = GoalText.objective(text)
        let old = goal
        let busy = agentBusy
        let outcome = await runGoal(.set(objective), images: images)
        guard outcome == .done else { return outcome }
        showsGoalSheet = false
        toasts.show(Self.goalSetNotice(replaced: old != nil, busy: busy))
        if fromDraft { showSent(objective, goal: true) }
        if let old { await removeKickoff(of: old) }
        return .done
    }

    /// Pause: the agent finishes this turn, then stops. The sheet stays open.
    @discardableResult
    func pauseGoal() async -> GoalOutcome {
        let outcome = await runGoal(.pause)
        if outcome == .done {
            toasts.show(HexToastItem(
                tone: .info,
                title: String(localized: "goal.notice.paused", defaultValue: "Goal paused"),
                detail: String(localized: "goal.notice.paused.sub", defaultValue: "The agent finishes this turn, then stops.")
            ))
        }
        return outcome
    }

    /// Resume: 50 more rounds, counted from 1.
    @discardableResult
    func resumeGoal() async -> GoalOutcome {
        let outcome = await runGoal(.resume)
        if outcome == .done {
            toasts.show(HexToastItem(
                tone: .steer,
                title: String(localized: "goal.notice.resumed", defaultValue: "Goal resumed"),
                detail: String(localized: "goal.notice.resumed.sub", defaultValue: "The agent carries on.")
            ))
        }
        return outcome
    }

    /// Clear: the sheet closes. A kickoff of the old goal still waiting in the inbox is taken back.
    @discardableResult
    func clearGoal() async -> GoalOutcome {
        let old = goal
        let busy = agentBusy
        let outcome = await runGoal(.clear)
        guard outcome == .done else { return outcome }
        showsGoalSheet = false
        toasts.show(HexToastItem(
            tone: .steer,
            title: String(localized: "goal.notice.cleared", defaultValue: "Goal cleared"),
            detail: busy ? String(localized: "goal.notice.cleared.busy", defaultValue: "The agent finishes this turn.") : nil
        ))
        if let old { await removeKickoff(of: old) }
        return .done
    }

    /// Sends `/goal`. A `500` means the goal changed elsewhere: `get`, and "The goal had already
    /// changed". Without an event within 2 s of a `204`, `get`.
    private func runGoal(_ command: GoalCommand, images: [OutgoingImage] = []) async -> GoalOutcome {
        guard let goals, let sessionID else {
            toasts.show(Self.goalFailedNotice())
            return .failed
        }
        let events = goalEvents
        do {
            try await goals.goalCommand(sessionID: sessionID, text: command.text, images: images)
        } catch GoalAPIError.stale {
            HexLog.summary.info("goal: the goal had already changed")
            await readGoal()
            toasts.show(HexToastItem(
                tone: .info,
                title: String(localized: "goal.notice.stale", defaultValue: "The goal had already changed")
            ))
            return .stale
        } catch {
            HexLog.summary.error("goal: couldn't send the command: \(String(describing: error), privacy: .public)")
            toasts.show(Self.goalFailedNotice())
            return .failed
        }
        let sleep = sleep
        Task { [weak self] in
            try? await sleep(Self.goalEventWait)
            guard let self, self.goalEvents == events, self.sessionID == sessionID else { return }
            HexLog.summary.info("goal: no event after the command; reading it")
            await self.readGoal()
        }
        return .done
    }

    /// After Clear or Replace: the old goal's kickoff, if it still waits in the inbox, is taken back,
    /// or the agent would still run a turn on it.
    private func removeKickoff(of old: TodayGoal) async {
        guard let api, let sessionID else { return }
        let waiting = (live?.inbox ?? []).filter { $0.goalID == old.id }
        for item in waiting {
            do {
                try await api.cancelInbox(sessionID: sessionID, itemID: item.id)
                _ = live?.apply(.inboxRemoved(sessionID: sessionID, id: item.id))
            } catch {
                HexLog.summary.error("goal: couldn't take back the old kickoff: \(String(describing: error), privacy: .public)")
            }
        }
        if !waiting.isEmpty { publish() }
    }

    static func goalSetNotice(replaced: Bool, busy: Bool) -> HexToastItem {
        HexToastItem(
            tone: .steer,
            title: replaced
                ? String(localized: "goal.notice.replaced", defaultValue: "Goal replaced")
                : String(localized: "goal.notice.set", defaultValue: "Goal set"),
            detail: busy
                ? String(localized: "goal.notice.set.busy", defaultValue: "The agent starts on it at its next step.")
                : String(localized: "goal.notice.set.idle", defaultValue: "The agent keeps going until it’s done."),
            duration: 3
        )
    }

    static func goalFailedNotice() -> HexToastItem {
        HexToastItem(
            tone: .stop,
            title: String(localized: "goal.notice.failed", defaultValue: "Couldn’t change the goal"),
            detail: String(localized: "goal.notice.failed.sub", defaultValue: "Try again.")
        )
    }

    static func goalRefusedNotice(_ refusal: GoalText.Refusal) -> HexToastItem {
        switch refusal {
        case .commandWord:
            HexToastItem(
                tone: .info,
                title: String(localized: "goal.refused.word", defaultValue: "Say a longer goal"),
                detail: String(localized: "goal.refused.word.sub", defaultValue: "A single word is read as a goal command.")
            )
        case .tooLong:
            HexToastItem(
                tone: .info,
                title: String(localized: "goal.refused.long", defaultValue: "That’s too long for a goal"),
                detail: String(localized: "goal.refused.long.sub", defaultValue: "Goals are limited to 4,000 characters.")
            )
        }
    }

    // MARK: Ask on the side

    /// Asks the session a side question; nothing is sent to the agent.
    func askOnTheSide(_ question: String) {
        ask.ask(question)
    }

    /// An Asked on the side row: Details closes, then that answer shows open.
    @ObservationIgnored private var askAfterDetails: UUID?

    func openAskFromDetails(_ id: UUID) {
        askAfterDetails = id
        showsDetails = false
    }

    // MARK: Sample sessions

    /// Sample sessions (Settings › Developer): put the state in directly.
    func updateSample(_ change: (inout LiveSessionState) -> Void) {
        guard var state = live else { return }
        change(&state)
        live = state
        publish()
    }

    func setSampleRecent(_ steps: [RecentStep]) {
        recentSteps = steps
    }

    /// "New day, new session"; when yesterday's goal was still active, the detail says it keeps
    /// running there.
    static func newDayNotice(goalRunning: Bool = false) -> HexToastItem {
        HexToastItem(
            tone: .sun,
            title: String(localized: "home.newday.title", defaultValue: "New day, new session"),
            detail: goalRunning
                ? String(localized: "home.newday.detail.goal", defaultValue: "Yesterday’s is kept on your computer. Its goal keeps running there.")
                : String(localized: "home.newday.detail", defaultValue: "Yesterday’s is kept on your computer."),
            duration: 3.6
        )
    }
}
