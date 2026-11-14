import Foundation
import OpenCodeKit

/// The `playground` sample session (`TodaySessionDemo.playground`): a scripted run that plays on
/// the phone, for App Store review and for anyone with no computer to pair yet. Settings › Sample
/// session and Welcome's "Try a sample session" open it, in every build.
///
/// It starts idle, with a "Ready when you are" card. The first take plays the whole run: busy, the
/// to-do list ticking through, an approval for `npm test` that waits for Allow or Deny, a question
/// that waits for its answer, then idle with a card that names the take and shows the sample
/// artifact. Each later take plays a shorter run. Stop ends a run at once.
///
/// It stands in for OpenCode as today's session's API and control, and puts its events through
/// `TodaySession`'s own path for the stream's, so home shows it as it would a real session. Nothing
/// is saved and nothing leaves the phone.
@MainActor
final class PlaygroundScript: LiveSessionAPI, SessionControlAPI {
    static let sessionID = "ses_demo"

    /// The suggestion chip's take: the whole run with no speech model and no microphone.
    static let suggestion = "Add a dark mode toggle"

    static let approvalID = "per_play"
    static let questionID = "frm_play"
    static let artifact = CardArtifact(id: "art_demo", title: "Sign-in speed")

    /// Waits between steps; a test passes one that doesn't.
    private let sleep: @Sendable (Duration) async throws -> Void
    /// Today's sample session, which this script plays.
    private weak var session: TodaySession?
    /// What the next summary says.
    private(set) var reply = PlaygroundScript.readyReply
    /// The card's artifact once the first run has ended.
    private(set) var cardArtifact: CardArtifact?
    /// Runs started; the first is the whole run.
    private(set) var runs = 0
    private var run: Task<Void, Never>?
    /// Takes sent while a run plays, in order: they wait in the inbox until it ends.
    private var waiting: [Take] = []
    private var approvalWait: CheckedContinuation<SessionStateAPIClient.PermissionDecision, any Error>?
    private var questionWait: CheckedContinuation<String?, any Error>?
    private var nextID = 0

    private struct Take {
        var id: String
        var text: String
    }

    private struct Unused: Error {}

    init(sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.sleep = sleep
    }

    /// Today's sample session, played by a new script: found at once, idle, on the made-up project.
    static func session(
        toasts: HexToaster,
        now: Date = Date(),
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) -> TodaySession {
        let script = PlaygroundScript(sleep: sleep)
        let today = DailySession.title(for: now)
        // It ran before, so the card is a summary rather than the empty card.
        let listing = SessionListing(id: sessionID, title: today, directory: directory, updatedAt: now, busySince: now.addingTimeInterval(-600))
        let session = TodaySession(
            setup: DailySessionSetup(service: DemoService(sessions: [listing], fails: false), memory: DemoMemory(lastOpenedDay: today)),
            summary: StatusSummaryModel(),
            directory: { directory },
            model: { DemoSetup.setup.model },
            toasts: toasts,
            api: script,
            control: script
        )
        script.session = session
        session.artifactSource = { [weak script] _, _ in script?.cardArtifact }
        session.onClosed = { [weak script] in script?.end() }
        return session
    }

    /// Ask on the side has nothing real to read here.
    static let askAnswer: @Sendable (String) async throws -> String = { _ in
        "This is a sample session, so there is no real code to look at. Pair your computer to ask about your own project."
    }

    /// The sample was left: a run playing stops where it is, with no card.
    func end() {
        run?.cancel()
        run = nil
        waiting = []
        approvalWait?.resume(throwing: CancellationError())
        approvalWait = nil
        questionWait?.resume(throwing: CancellationError())
        questionWait = nil
    }

    // MARK: Today's session's API

    func snapshot(rootID: String) async throws -> LiveSnapshot { throw Unused() }

    func generate(sessionID: String, prompt: String) async throws -> String {
        try await sleep(.milliseconds(600))
        return reply
    }

    func switchModel(sessionID: String, model: ModelChoice) async throws {}

    func sessionUpdated(sessionID: String) async throws -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    /// The take joins the inbox. With no run playing it is delivered and its run starts; during a
    /// run it waits for the run's end, whatever its delivery.
    func prompt(sessionID: String, text: String, images: [OutgoingImage], files: [OutgoingFile], delivery: Delivery) async throws -> String {
        nextID += 1
        let take = Take(id: "msg_play_\(nextID)", text: text)
        emit(.inboxEnqueued(sessionID: Self.sessionID, id: take.id, delivery: delivery.rawValue, text: text))
        waiting.append(take)
        if run == nil { startRun() }
        return take.id
    }

    func cancelInbox(sessionID: String, itemID: String) async throws {
        waiting.removeAll { $0.id == itemID }
    }

    // MARK: Control

    func replyToApproval(sessionID: String, requestID: String, decision: SessionStateAPIClient.PermissionDecision, note: String?) async throws {
        guard requestID == Self.approvalID, let approvalWait else { throw ControlError.gone }
        self.approvalWait = nil
        approvalWait.resume(returning: decision)
    }

    func answerQuestion(sessionID: String, formID: String, answer: [String: FormAnswerV2]) async throws {
        guard formID == Self.questionID, let questionWait else { throw ControlError.gone }
        self.questionWait = nil
        let chosen: String? = switch answer["q0"] {
        case let .text(text)?: text
        case let .choices(choices)?: choices.first
        case nil: nil
        }
        questionWait.resume(returning: chosen?.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    func dismissQuestion(sessionID: String, formID: String, note: String) async throws {
        guard formID == Self.questionID, let questionWait else { throw ControlError.gone }
        self.questionWait = nil
        questionWait.resume(returning: nil)
    }

    /// Stop: the run ends where it is, and the card says so. Takes still waiting stay in the inbox
    /// and go with the next one.
    func interrupt(sessionID: String) async throws -> Bool {
        guard sessionID == Self.sessionID, let run else { return false }
        run.cancel()
        self.run = nil
        approvalWait?.resume(throwing: CancellationError())
        approvalWait = nil
        questionWait?.resume(throwing: CancellationError())
        questionWait = nil
        reply = Self.stoppedReply
        emit(.executionEnded(sessionID: Self.sessionID))
        return true
    }

    func runningSubagents(rootID: String) async throws -> [String] { [] }

    func running(rootID: String, directory: String) async throws -> RunningSnapshot {
        RunningSnapshot(childBusySince: [:], childSessions: [:], shells: [])
    }

    func recentTools(sessionID: String) async throws -> [ToolPartV2] { [] }

    // MARK: The script

    /// Every take waiting is delivered, and a run starts for the newest.
    private func startRun() {
        guard let take = waiting.last else { return }
        for waitingTake in waiting {
            emit(.inboxDelivered(sessionID: Self.sessionID, id: waitingTake.id))
        }
        waiting = []
        runs += 1
        let whole = runs == 1
        run = Task { [weak self] in
            do {
                if whole {
                    try await self?.playWhole(take.text)
                } else {
                    try await self?.playShort(take.text)
                }
            } catch {
                // Stopped or left: the run has already ended.
                return
            }
            guard let self, !Task.isCancelled else { return }
            self.run = nil
            self.emit(.executionEnded(sessionID: Self.sessionID))
            if !self.waiting.isEmpty { self.startRun() }
        }
    }

    /// The first take: to-dos, an approval and a question, then the card with the artifact.
    private func playWhole(_ take: String) async throws {
        try await pause(.milliseconds(500))
        emit(.executionStarted(sessionID: Self.sessionID, at: Date()))
        todos(["in_progress", "pending", "pending", "pending"])
        try await pause(.seconds(3))
        todos(["completed", "in_progress", "pending", "pending"])
        try await pause(.seconds(3.5))
        todos(["completed", "completed", "in_progress", "pending"])
        try await pause(.seconds(1))
        let decision = try await approval()
        let allowed = decision != .reject
        try await pause(.seconds(allowed ? 4 : 1))
        todos(["completed", "completed", allowed ? "completed" : "cancelled", "in_progress"])
        try await pause(.seconds(1.5))
        let answer = try await question()
        try await pause(.seconds(3))
        todos(["completed", "completed", allowed ? "completed" : "cancelled", "completed"])
        try await pause(.seconds(1.5))
        reply = Self.finishedReply(take: take, testsRan: allowed, answer: answer)
        cardArtifact = Self.artifact
    }

    /// A later take: to-dos, then the card.
    private func playShort(_ take: String) async throws {
        try await pause(.milliseconds(500))
        emit(.executionStarted(sessionID: Self.sessionID, at: Date()))
        let items = ["Read the code it touches", "Make the change", "Check it still builds"]
        for done in 0 ... items.count {
            let statuses = items.indices.map { $0 < done ? "completed" : ($0 == done ? "in_progress" : "pending") }
            emit(.todosWritten(sessionID: Self.sessionID, todos: zip(items, statuses).map { TodoItemV2(content: $0, status: $1) }))
            if done < items.count { try await pause(.seconds(2.5)) }
        }
        try await pause(.seconds(1))
        reply = Self.shortReply(take: take)
    }

    /// The approval for `npm test` waits for Allow once, Always or Deny.
    private func approval() async throws -> SessionStateAPIClient.PermissionDecision {
        try await withCheckedThrowingContinuation { continuation in
            approvalWait = continuation
            emit(.permissionAsked(PendingItem(approval: PendingPermissionV2(
                id: Self.approvalID, sessionID: Self.sessionID, action: "shell", resources: ["npm test"], save: ["npm *"]
            ))))
        }
    }

    /// The question waits for its answer; nil when it was dismissed.
    private func question() async throws -> String? {
        try await withCheckedThrowingContinuation { continuation in
            questionWait = continuation
            emit(.formCreated(PendingItem(question: PendingFormV2(id: Self.questionID, sessionID: Self.sessionID, title: "Questions", kind: "question", fields: [
                .init(key: "q0", title: "When it's done", description: "What should I do once the change works?", type: "string", options: [
                    .init(value: "Open a pull request", label: "Open a pull request", description: "For you to review"),
                    .init(value: "Commit it", label: "Commit it", description: "On this branch"),
                    .init(value: "Leave it for me", label: "Leave it for me", description: ""),
                ], custom: true),
            ]))))
        }
    }

    private func pause(_ duration: Duration) async throws {
        try await sleep(duration)
        try Task.checkCancellation()
    }

    private func todos(_ statuses: [String]) {
        emit(.todosWritten(sessionID: Self.sessionID, todos: zip(Self.wholeRunTodos, statuses).map { TodoItemV2(content: $0, status: $1) }))
    }

    private func emit(_ event: SessionEvent) {
        session?.receiveSample(event)
    }

    // MARK: What the agent says

    private static let directory = DemoSetup.setup.project.path

    static let wholeRunTodos = ["Read the code it touches", "Make the change", "Run `npm test`", "Write up what changed"]

    static let readyReply = """
    ## Ready when you are
    Now: This is a sample session on a made-up web app. Nothing here runs on a real computer.
    Next: Tap the suggestion below, or hold the button and say what to change.
    """

    static let stoppedReply = """
    ## Stopped
    Now: You stopped the run before it finished. This is a sample, so nothing changed anywhere.
    Next: Say what to do next.
    """

    /// The first run's card: the take, whether the tests ran, and the answer to the question.
    static func finishedReply(take: String, testsRan: Bool, answer: String?) -> String {
        let tests = testsRan
            ? "All 24 tests pass, and sign-in got faster on the way."
            : "I didn't run `npm test`, as you said, so the tests haven't run."
        let next = if let answer, !answer.isEmpty {
            "Next: You chose “\(quoted(answer))”. The speed report is below."
        } else {
            "Next: Say what to do with it. The speed report is below."
        }
        return "## Sample change done\nNow: Done: “\(quoted(take))”. \(tests)\n\(next)"
    }

    static func shortReply(take: String) -> String {
        "## Sample change done\nNow: Done: “\(quoted(take))”. This is a sample, so nothing changed on a real computer.\n"
            + "Next: Try another, or leave the sample to pair your own computer."
    }

    /// The take on one line, cut at 80 characters.
    private static func quoted(_ take: String) -> String {
        let line = take.split(whereSeparator: \.isNewline).joined(separator: " ").trimmingCharacters(in: .whitespaces)
        return line.count > 80 ? String(line.prefix(79)) + "…" : line
    }
}
