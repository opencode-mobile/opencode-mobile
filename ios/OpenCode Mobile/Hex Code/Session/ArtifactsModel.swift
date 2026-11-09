import Foundation
import Observation
import OpenCodeKit
import os
import UIKit

/// The enable or turn-off flow (app plan, "Artifacts (M8)", variant A): one sheet that explains,
/// shows the exact prompt, then ticks through Sent → Agent working → Approval → Checking → On.
struct ArtifactsFlow: Equatable {
    enum Mode: Equatable, Sendable {
        case enable
        case disable
    }

    /// A row of the step list.
    enum Step: Equatable, Sendable {
        case todo
        case doing
        /// Waiting on something else (the approval).
        case dimmed
        case ok
        case warn
        case fail
    }

    enum Stage: Equatable, Sendable {
        /// Reading the config before anything is shown.
        case preparing
        /// What artifacts are, the file, the exact prompt and where it goes; Cancel / Send.
        case explain
        /// The prompt can't be sent: only the line to copy.
        case cantSend(CantSend)
        case running
        case done
        /// The line to copy, the file, Copy and Try again.
        case fallback(Fallback)
        /// "Your OpenCode config changed unexpectedly", with Restore.
        case broken(ConfigCheck.Result)
        case restoring
    }

    enum CantSend: Equatable, Sendable {
        /// OpenCode reads it from `OPENCODE_CONFIG_CONTENT`.
        case environment
        /// `GET /api/config` couldn't be read.
        case unreadable
        /// The file is there but OpenCode can't read it: never send "create it" at a broken file.
        case alreadyBroken
    }

    enum Fallback: Equatable, Sendable {
        case rejected
        /// The agent replied `FAILED: <reason>`.
        case failed(String)
        /// `429 provider.free-limit`: the error's message.
        case freeLimit(String)
        /// The run couldn't start or failed: the error's message.
        case couldntRun(String)
        /// 60 s without the change.
        case timedOut
        /// The run ended and the route still answers as before.
        case stillOff
        /// Restore wrote the saved text back.
        case restored
    }

    var mode: Mode
    var stage: Stage = .preparing
    var target: ConfigTarget.TargetFile?
    var sent = Step.todo
    var work = Step.todo
    /// Nil until an approval is asked.
    var approval: Step?
    var check = Step.todo
    var end = Step.todo
    /// What the agent is doing, from its tool events.
    var liveLine: LiveLine?
    /// The setup session's approval, waiting.
    var pendingApproval: PendingItem?
    /// The sheet shows the approval in place of the steps.
    var showsApproval = false
    /// The status the route last answered while checking.
    var lastStatus: Int?
    /// The agent's last words, for its reason.
    var reply: String?
    var sentAt: Date?
    /// Time spent waiting on the person's approval, which doesn't count towards the timeout.
    var approvalWait: TimeInterval = 0
    var approvalSince: Date?
    var runEnded = false

    enum LiveLine: Equatable, Sendable {
        /// Sent, nothing from the agent yet.
        case waiting
        case thinking
        case reading(String)
        case editing(String)
        /// Any other tool, by name.
        case tool(String)
    }

    var isRunning: Bool { stage == .running }

    /// Sent, done or failed: the sheet's close button closes it rather than hiding it.
    var isSettled: Bool {
        switch stage {
        case .running, .preparing, .restoring: false
        default: true
        }
    }

    /// The fallback's line can be shown: there's a file to name.
    var offersLine: Bool { target != nil }

    var prompt: String? {
        target.map { mode == .enable ? SessionPrompts.Artifacts.Setup.enable(file: $0.path) : SessionPrompts.Artifacts.Setup.disable(file: $0.path) }
    }
}

/// Artifacts on this computer and project (app plan, "Artifacts (M8)"): whether they're on, the
/// card's artifact, the `artifact-rules` entry on today's session, and the enable and turn-off flow
/// through the separate "Hex Code setup" session. The agent's reply is never the status: the list
/// route is, checked every 2 s while the flow is open.
@MainActor
@Observable
final class ArtifactsModel {
    static let checkInterval: Duration = .seconds(2)
    static let runTimeout: TimeInterval = 60
    static let hintKey = "hexcode.artifacts.hint_seen"
    /// After Restore, the config is read again up to this many more times, this far apart.
    static let restoreChecks = 6
    static let restoreInterval: Duration = .milliseconds(500)

    private(set) var state: ArtifactsState = .checking
    private(set) var flow: ArtifactsFlow?
    /// The enable or turn-off sheet is on screen.
    var sheetShown = false
    /// "See what the agent says".
    var readerShown = false
    private(set) var transcript: [SetupMessage] = []
    /// The one-time hint on the card.
    private(set) var hintShown = false
    /// Restore is offered: the file's text was saved before the edit.
    var canRestore: Bool { savedText != nil }

    /// Waits; a test passes its own.
    @ObservationIgnored var sleep: @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    /// The wait between checks after Restore; a test passes its own.
    @ObservationIgnored var restoreWait: @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    /// "Make a small page that previews …" goes into the draft.
    @ObservationIgnored var onSuggestion: (String) -> Void = { _ in }
    /// The server's name, for "Waiting for <name>…".
    @ObservationIgnored var serverName: () -> String = { "" }

    @ObservationIgnored private let api: (any ArtifactsAPI)?
    @ObservationIgnored private let directory: () -> String
    @ObservationIgnored private let model: () -> ModelChoice
    @ObservationIgnored private let todaySessionID: () -> String?
    @ObservationIgnored private let toasts: HexToaster
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let now: () -> Date
    /// Whether `/openapi.json` declares the routes, read once per connection.
    @ObservationIgnored private var declared: Bool?
    @ObservationIgnored private(set) var setupSessionID: String?
    @ObservationIgnored private var loop: Task<Void, Never>?
    /// The config before the edit, and the target file's exact text, for the after-check and Restore.
    @ObservationIgnored private var before: OpenCodeConfig?
    @ObservationIgnored private var savedText: Data?
    /// Today's sessions whose `artifact-rules` entry is known to be put (true) or deleted (false).
    @ObservationIgnored private var rules: [String: Bool] = [:]

    init(
        api: (any ArtifactsAPI)?,
        directory: @escaping () -> String,
        model: @escaping () -> ModelChoice,
        todaySessionID: @escaping () -> String?,
        toasts: HexToaster,
        defaults: UserDefaults = .standard,
        now: @escaping () -> Date = Date.init
    ) {
        self.api = api
        self.directory = directory
        self.model = model
        self.todaySessionID = todaySessionID
        self.toasts = toasts
        self.defaults = defaults
        self.now = now
    }

    // MARK: State

    /// Today's session opened (setup, a new day, a new project or session): the routes are read
    /// once per connection, then whether artifacts are on; the rule follows.
    func sessionOpened() async {
        guard let api else { return }
        if declared == nil { declared = await api.declared() }
        if declared == false {
            await set(.unavailable)
            await syncRules()
            return
        }
        _ = await refresh()
        await syncRules()
    }

    /// Another address or sign-in: the routes are read again at the next open.
    func connectionChanged() {
        declared = nil
        if state == .unavailable { state = .checking }
    }

    /// Reads the list route and applies its answer.
    @discardableResult
    func refresh() async -> ArtifactsAnswer {
        guard let api, declared != false else { return .unknown }
        let raw = await api.list(directory: directory())
        let answer = ArtifactsAnswer.read(status: raw.status, body: raw.body)
        flow?.lastStatus = raw.status
        await apply(answer)
        return answer
    }

    private func apply(_ answer: ArtifactsAnswer) async {
        switch answer {
        case .on: await set(.on)
        case .off: await set(.off)
        case .changed: await set(.unavailable)
        case .signedOut, .unknown: break
        }
    }

    private func set(_ next: ArtifactsState) async {
        guard state != next else { return }
        let wasOn = state == .on
        state = next
        if next == .on { hintShown = false }
        if wasOn != (next == .on) { await syncRules() }
    }

    /// `artifact-rules` is on today's session only while artifacts are on.
    func syncRules() async {
        guard let api, let id = todaySessionID() else { return }
        switch state {
        case .on:
            guard rules[id] != true else { return }
            if await api.putRules(sessionID: id) { rules[id] = true }
        case .off, .unavailable:
            guard rules[id] != false else { return }
            await api.deleteRules(sessionID: id)
            rules[id] = false
        case .checking:
            break
        }
    }

    /// The card's artifact, read with each summary refresh: the newest one of today's session or
    /// a subagent (`sessions`) from its latest run. A `403` is no tile and no error.
    func cardArtifact(rootID: String, sessions: Set<String>) async -> CardArtifact? {
        guard let api, state != .unavailable, declared != false else { return nil }
        guard case let .on(list) = await refresh() else { return nil }
        let busy = await api.busyStart(sessionID: rootID)
        return ArtifactPick.newest(list, sessions: sessions.union([rootID]), since: busy)
    }

    // MARK: The one-time hint

    /// A run's tools wrote a `.html` file while artifacts are off: the hint shows, once per phone.
    func noteTools(_ parts: [ToolPartV2]) {
        guard state == .off, !defaults.bool(forKey: Self.hintKey), !hintShown else { return }
        let wrotePage = parts.contains { part in
            guard ["write", "edit", "patch", "multiedit"].contains(part.name) else { return false }
            let paths = [part.path].compactMap { $0 } + part.files.map(\.file)
            return paths.contains { $0.lowercased().hasSuffix(".html") || $0.lowercased().hasSuffix(".htm") }
        }
        if wrotePage { hintShown = true }
    }

    /// ✕, or a tap: never again.
    func dismissHint() {
        hintShown = false
        defaults.set(true, forKey: Self.hintKey)
    }

    func hintTapped() {
        dismissHint()
        open()
    }

    // MARK: The flow

    /// Settings › Artifacts, the hint, the browser's "Turn on…" or a notice: the sheet, with the
    /// flow running or a new one (turn off when on).
    /// Returns the config read of a new flow.
    @discardableResult
    func open() -> Task<Void, Never>? {
        if flow != nil {
            sheetShown = true
            let pending = flow?.pendingApproval != nil
            flow?.showsApproval = pending
            return nil
        }
        guard state == .on || state == .off else { return nil }
        flow = ArtifactsFlow(mode: state == .on ? .disable : .enable)
        transcript = []
        sheetShown = true
        return Task { await prepare() }
    }

    /// Reads the config: the file that wins, whether it's already broken, and its exact text.
    func prepare() async {
        guard let api, flow != nil else { return }
        let directory = directory()
        let config = await api.config(directory: directory)
        before = config
        savedText = nil
        guard flow != nil else { return }
        switch ConfigTarget.choose(config) {
        case .environment:
            flow?.stage = .cantSend(.environment)
        case .unreadable:
            flow?.stage = .cantSend(.unreadable)
        case let .file(target):
            flow?.target = target
            let names = await api.files(in: target.folder, directory: directory)
            if names?.contains(target.name) == true, !target.hasDocument {
                flow?.stage = .cantSend(.alreadyBroken)
            } else {
                if target.hasDocument { savedText = await api.read(name: target.name, folder: target.folder) }
                if flow?.stage == .preparing { flow?.stage = .explain }
            }
        }
        startLoop()
    }

    /// Send to the agent (and Try again): the prompt goes to the setup session, created once.
    func send() async {
        guard let api, var flow, let prompt = flow.prompt else { return }
        switch flow.stage {
        case .explain, .fallback: break
        default: return
        }
        flow.stage = .running
        flow.sent = .doing
        flow.work = .todo
        flow.approval = nil
        flow.check = .doing
        flow.end = .todo
        flow.liveLine = .waiting
        flow.pendingApproval = nil
        flow.showsApproval = false
        flow.reply = nil
        flow.runEnded = false
        flow.sentAt = now()
        flow.approvalWait = 0
        flow.approvalSince = nil
        self.flow = flow
        do {
            let id = try await setupSession()
            try await api.send(prompt, sessionID: id)
        } catch {
            HexLog.summary.error("artifacts: couldn't send the prompt: \(String(describing: error), privacy: .public)")
            self.flow?.sent = .fail
            self.flow?.check = .todo
            settle(.fallback(.couldntRun(Self.message(of: error))))
            return
        }
        guard self.flow?.isRunning == true else { return }
        self.flow?.sent = .ok
        self.flow?.work = .doing
        startLoop()
    }

    /// The setup session: found or created once, then reused. Created in today's project with the
    /// model in use and the lockdown rules.
    private func setupSession() async throws -> String {
        guard let api else { throw URLError(.notConnectedToInternet) }
        if let setupSessionID { return setupSessionID }
        let directory = directory()
        let id: String
        if let found = try await api.findSetupSession(directory: directory) {
            id = found
        } else {
            let folder = before?.globalFolder ?? flow?.target?.folder ?? directory
            let rules = SessionPrompts.Artifacts.Setup.lockdown(globalFolder: folder).map {
                PermissionRule(action: $0.action, resource: $0.resource, effect: $0.effect)
            }
            id = try await api.createSetupSession(directory: directory, model: model(), rules: rules)
        }
        setupSessionID = id
        return id
    }

    /// Every 2 s while the flow is open: the route answers as wanted → done (it may come before the
    /// agent's reply, or from the line added by hand). While running, a missed approval is read, and
    /// 60 s without the change (not counting the wait for the person) is a timeout.
    func tick() async {
        guard let api, let current = flow else { return }
        switch current.stage {
        case .preparing, .done, .restoring: return
        default: break
        }
        let answer = await refresh()
        guard let flow = self.flow else { return }
        if wanted(answer, flow.mode) {
            await finish()
            return
        }
        guard flow.isRunning else { return }
        if flow.pendingApproval == nil, !flow.runEnded, let id = setupSessionID,
           let request = await api.pendingApprovals(sessionID: id).first {
            approvalAsked(PendingItem(approval: request))
        }
        guard let flow = self.flow, flow.isRunning, flow.pendingApproval == nil, let sentAt = flow.sentAt else { return }
        if now().timeIntervalSince(sentAt) - flow.approvalWait >= Self.runTimeout {
            self.flow?.work = .fail
            self.flow?.check = .warn
            settle(.fallback(.timedOut))
        }
    }

    private func wanted(_ answer: ArtifactsAnswer, _ mode: ArtifactsFlow.Mode) -> Bool {
        switch (answer, mode) {
        case (.on, .enable), (.off, .disable): true
        default: false
        }
    }

    /// One line of today's event stream: only the setup session's events count.
    func received(line: String) {
        guard let id = setupSessionID, flow?.isRunning == true, let event = SetupEvent.decode(line, sessionID: id) else { return }
        switch event {
        case let .toolStarted(name):
            let line = Self.liveLine(tool: name, file: flow?.target?.name ?? "")
            flow?.liveLine = line
        case let .approvalAsked(item):
            approvalAsked(item)
        case let .approvalReplied(requestID):
            // Answered elsewhere.
            if flow?.pendingApproval?.id == requestID { approvalDone(allowed: true) }
        case .runStarted:
            if flow?.liveLine == .waiting { flow?.liveLine = .thinking }
        case .runSucceeded, .runInterrupted:
            Task { await runEnded(error: nil) }
        case let .runFailed(error):
            Task { await runEnded(error: error) }
        }
    }

    static func liveLine(tool: String, file: String) -> ArtifactsFlow.LiveLine {
        switch tool {
        case "read": .reading(file)
        case "edit", "write", "patch", "multiedit", "apply_patch": .editing(file)
        default: .tool(tool)
        }
    }

    private func approvalAsked(_ item: PendingItem) {
        guard item.approval != nil, flow?.isRunning == true, flow?.pendingApproval?.id != item.id else { return }
        flow?.pendingApproval = item
        flow?.approval = .warn
        flow?.work = .dimmed
        flow?.approvalSince = now()
        if sheetShown {
            flow?.showsApproval = true
        } else {
            notify(.approval)
        }
    }

    /// Allow once or Reject; the approval offers nothing else ("Always" would allow every edit in
    /// the project).
    func answerApproval(allow: Bool) async {
        guard let api, let item = flow?.pendingApproval, let id = setupSessionID else { return }
        do {
            try await api.reply(sessionID: id, requestID: item.id, allow: allow)
        } catch let SessionStateAPIError.httpStatus(status, _) where status == 404 || status == 409 {
            // Gone already: as answered.
        } catch {
            toasts.show(TodaySession.failedNotice())
            return
        }
        approvalDone(allowed: allow)
        if !allow {
            flow?.work = .fail
            flow?.check = .todo
            settle(.fallback(.rejected))
        }
    }

    private func approvalDone(allowed: Bool) {
        guard var flow, flow.pendingApproval != nil else { return }
        if let since = flow.approvalSince { flow.approvalWait += now().timeIntervalSince(since) }
        flow.approvalSince = nil
        flow.pendingApproval = nil
        flow.showsApproval = false
        flow.approval = allowed ? .ok : .fail
        if allowed { flow.work = .doing }
        self.flow = flow
    }

    /// The setup session went idle: its reply, then the route once more, then the config.
    func runEnded(error: SetupRunError?) async {
        guard let api, flow?.isRunning == true, flow?.runEnded == false, let id = setupSessionID else { return }
        flow?.runEnded = true
        flow?.liveLine = .thinking
        transcript = await api.transcript(sessionID: id)
        flow?.reply = SetupMessage.lastReply(transcript)
        let answer = await refresh()
        guard let flow, flow.isRunning else { return }
        if wanted(answer, flow.mode) {
            await finish()
            return
        }
        if let problem = await afterCheck(), problem != .intact {
            self.flow?.work = .fail
            self.flow?.check = .warn
            settle(.broken(problem))
            return
        }
        self.flow?.work = .fail
        self.flow?.check = .warn
        if let error {
            settle(.fallback(error.isFreeLimit ? .freeLimit(error.message) : .couldntRun(error.message)))
        } else if case let .failed(reason)? = self.flow?.reply.map(SetupReply.read) {
            settle(.fallback(.failed(reason)))
        } else {
            settle(.fallback(.stillOff))
        }
    }

    /// The route answers as wanted: the config is compared with the copy from before, and the rule
    /// follows.
    private func finish() async {
        guard var flow, flow.stage != .done else { return }
        flow.check = .ok
        flow.end = .ok
        if flow.work == .doing || flow.work == .dimmed { flow.work = .ok }
        if flow.sent == .doing { flow.sent = .ok }
        if flow.pendingApproval != nil {
            flow.pendingApproval = nil
            flow.showsApproval = false
        }
        self.flow = flow
        if flow.mode == .enable {
            defaults.set(true, forKey: Self.hintKey)
            hintShown = false
        }
        await syncRules()
        if let problem = await afterCheck(), problem != .intact {
            settle(.broken(problem))
        } else {
            settle(.done)
        }
    }

    /// The config now against the copy from before: intact, broken, or keys missing.
    private func afterCheck() async -> ConfigCheck.Result? {
        guard let api, let before, let target = flow?.target else { return nil }
        let directory = directory()
        guard let after = await api.config(directory: directory) else { return nil }
        let names = await api.files(in: target.folder, directory: directory)
        return ConfigCheck.compare(before: before, after: after, target: target.path, fileExists: names?.contains(target.name))
    }

    private func settle(_ stage: ArtifactsFlow.Stage) {
        guard flow != nil else { return }
        flow?.stage = stage
        flow?.showsApproval = false
        switch stage {
        case .done:
            stopLoop()
            notify(.done)
        case .fallback(.restored):
            break
        case .fallback:
            notify(.fallback)
        case .broken:
            notify(.broken)
        default:
            break
        }
    }

    /// Restore: the saved text goes back byte for byte to the same path, then everything is checked
    /// again. The only time Hex Code writes a config file.
    func restore() async {
        guard let api, let savedText, let target = flow?.target, case .broken = flow?.stage else { return }
        let previous = flow?.stage ?? .restoring
        flow?.stage = .restoring
        guard await api.write(savedText, to: target.path, directory: directory()) else {
            flow?.stage = previous
            toasts.show(HexToastItem(
                tone: .stop,
                title: String(localized: "artifacts.restore.failed", defaultValue: "Couldn’t restore it"),
                detail: String(localized: "artifacts.restore.failed.sub", defaultValue: "Fix the file on the computer.")
            ))
            return
        }
        // OpenCode's config watcher reloads the file within about a second.
        var problem = await afterCheck()
        for _ in 0 ..< Self.restoreChecks where problem != nil && problem != .intact {
            try? await restoreWait(Self.restoreInterval)
            problem = await afterCheck()
        }
        let answer = await refresh()
        if let problem, problem != .intact {
            flow?.stage = .broken(problem)
        } else if let mode = flow?.mode, wanted(answer, mode) {
            settle(.done)
        } else {
            // The original is back: still checking, so a line changed by hand still flips it.
            flow?.check = .doing
            flow?.end = .todo
            settle(.fallback(.restored))
        }
    }

    /// Try again: the config is read again, then the prompt goes once more.
    func tryAgain() async {
        guard flow != nil else { return }
        flow?.stage = .preparing
        await prepare()
        if flow?.stage == .explain { await send() }
    }

    /// The sheet's close: a flow still on its way keeps running hidden; anything else ends.
    func close() {
        guard let flow else {
            sheetShown = false
            return
        }
        if flow.isRunning || flow.stage == .restoring {
            sheetShown = false
        } else {
            end()
        }
    }

    /// The approval's Hide: back to the steps.
    func hideApproval() {
        flow?.showsApproval = false
    }

    /// The approval's Review, from the steps.
    func showApproval() {
        guard flow?.pendingApproval != nil else { return }
        flow?.showsApproval = true
    }

    private func end() {
        stopLoop()
        flow = nil
        sheetShown = false
        readerShown = false
        before = nil
        savedText = nil
    }

    /// "See what the agent says": its messages, read again.
    func showReplies() {
        readerShown = true
        guard let api, let id = setupSessionID else { return }
        Task { transcript = await api.transcript(sessionID: id) }
    }

    /// The On result's chip: the suggestion goes into today's draft and the sheet closes.
    func suggestPage() {
        let text = String(localized: "artifacts.flow.on_suggestion", defaultValue: "Make a small page that previews")
        end()
        onSuggestion(text)
    }

    func copyLine() {
        guard let line = flow?.target?.line else { return }
        UIPasteboard.general.string = line
        toasts.show(HexToastItem(tone: .steer, title: String(localized: "artifacts.sheet.copied", defaultValue: "Copied"), detail: line))
    }

    // MARK: Loop

    private func startLoop() {
        guard loop == nil else { return }
        let sleep = sleep
        loop = Task { [weak self] in
            while !Task.isCancelled {
                try? await sleep(Self.checkInterval)
                guard !Task.isCancelled, let self else { return }
                guard let flow = self.flow, flow.stage != .done else { break }
                await self.tick()
            }
            self?.loop = nil
        }
    }

    private func stopLoop() {
        loop?.cancel()
        loop = nil
    }

    var isChecking: Bool { loop != nil }

    // MARK: Notices

    enum Notice { case approval, done, fallback, broken }

    /// While the sheet is hidden, a notice says what happened, with Open.
    private func notify(_ notice: Notice) {
        guard !sheetShown, let flow else { return }
        let item: HexToastItem
        let file = flow.target?.name ?? ""
        switch notice {
        case .approval:
            item = HexToastItem(
                tone: .info,
                title: String(localized: "artifacts.notice.approval", defaultValue: "Approval needed"),
                detail: String(localized: "artifacts.notice.approval.sub", defaultValue: "Hex Code setup · edit \(file)"),
                action: .init(title: String(localized: "artifacts.notice.review", defaultValue: "Review")),
                duration: 6
            )
        case .done:
            item = HexToastItem(
                tone: .steer,
                title: flow.mode == .enable
                    ? String(localized: "artifacts.sheet.now_on", defaultValue: "Artifacts are on")
                    : String(localized: "artifacts.flow.now_off", defaultValue: "Artifacts are off"),
                detail: flow.mode == .enable
                    ? String(localized: "artifacts.notice.on.sub", defaultValue: "Pages the agent makes now show on the card")
                    : nil,
                duration: 3.6
            )
        case .fallback:
            item = HexToastItem(
                tone: .info,
                title: String(localized: "artifacts.flow.not_changed", defaultValue: "The agent didn’t change it"),
                detail: String(localized: "artifacts.notice.open.sub", defaultValue: "Open the sheet to see why"),
                action: .init(title: String(localized: "artifacts.notice.open", defaultValue: "Open")),
                duration: 6
            )
        case .broken:
            item = HexToastItem(
                tone: .stop,
                title: String(localized: "artifacts.broken.title", defaultValue: "Your OpenCode config changed unexpectedly"),
                action: .init(title: String(localized: "artifacts.notice.open", defaultValue: "Open")),
                duration: 8
            )
        }
        toasts.show(item, onAction: notice == .done ? nil : { [weak self] in self?.open() })
    }

    static func message(of error: any Error) -> String {
        if case let SessionStateAPIError.httpStatus(status, body)? = error as? SessionStateAPIError {
            if let body, let data = body.data(using: .utf8),
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let message = object["message"] as? String {
                return message
            }
            return "HTTP \(status)"
        }
        return (error as NSError).localizedDescription
    }

    // MARK: Settings row

    /// Settings › Agent › Artifacts: its value.
    var rowValue: String? {
        if let flow {
            switch flow.stage {
            case .running where flow.pendingApproval != nil, .fallback, .broken, .cantSend:
                return String(localized: "settings.artifacts.needs_you", defaultValue: "Needs you")
            case .running, .preparing, .explain, .restoring:
                if flow.stage == .running || flow.stage == .restoring {
                    return flow.mode == .enable
                        ? String(localized: "settings.artifacts.turning_on", defaultValue: "Turning on…")
                        : String(localized: "settings.artifacts.turning_off", defaultValue: "Turning off…")
                }
            case .done:
                break
            }
        }
        switch state {
        case .checking: return nil
        case .on: return String(localized: "settings.artifacts.on", defaultValue: "On")
        case .off: return String(localized: "settings.artifacts.off", defaultValue: "Off")
        case .unavailable: return String(localized: "settings.artifacts.unavailable", defaultValue: "Not available")
        }
    }

    /// Its second line.
    var rowSubtitle: String? {
        if let flow, flow.isRunning, flow.pendingApproval != nil {
            return String(localized: "settings.artifacts.approval.sub", defaultValue: "Approval needed in Hex Code setup")
        }
        switch state {
        case .checking: return nil
        case .on: return String(localized: "settings.artifacts.on.sub", defaultValue: "The newest one from each run shows on the card.")
        case .off: return String(localized: "settings.artifacts.off.sub", defaultValue: "Pages the agent makes, opened on your phone.")
        case .unavailable: return String(localized: "settings.artifacts.unavailable.sub", defaultValue: "This OpenCode handles artifacts differently.")
        }
    }

    /// Tapping does something: not while checking, nor when not available.
    var rowEnabled: Bool { flow != nil || state == .on || state == .off }

    // MARK: Samples

    #if DEBUG
    /// The component gallery and sample sessions: a state put in directly.
    func setSample(state: ArtifactsState, flow: ArtifactsFlow? = nil) {
        self.state = state
        self.flow = flow
    }
    #endif
}
