import Foundation
import OpenCodeKit

/// Fake sessions for looking at home without a server: Settings › Developer › Sample session, or
/// `-HexCodeDemoSession <preset>` at launch (debug builds). Nothing here talks to OpenCode. The
/// presets are the same on both platforms.
enum TodaySessionDemo: String, CaseIterable {
    /// Working, with a subagent running, a message waiting and the agent's list half done.
    case card
    /// Idle, a card with no to-do row and no Next line.
    case plain
    /// A reply with no shape at all: a "Status" card, every line as Now.
    case fallback
    /// Couldn't open today's session.
    case failed
    /// A pending question.
    case needs
    /// A pending approval.
    case approval
    /// The first session after first run: the empty card.
    case empty
    /// A new day: the empty card and the notice.
    case newday
    /// The event stream is down.
    case reconnecting
    /// A summary request on its way.
    case updating
    /// The last summary request failed.
    case updatefailed
    /// A Now of 150 characters; every to-do item done.
    case long
    /// A Now of 298 characters, near the most the prompt asks for; no to-do row.
    case longest
    /// Idle, with code, bold, a `Link:` line and a localhost link in Now: formatting and link
    /// tiles; code in a to-do item.
    case formatted
    /// A list answer: a lead line and four bullets (a real v4 reply).
    case bullets
    /// A long Now full of code (branch names), as a real phone showed it, working with an approval
    /// waiting and a to-do row: the card steps down in size by what is drawn, not by its character
    /// count.
    case branches

    static var launchPreset: TodaySessionDemo? {
        #if DEBUG
        UserDefaults.standard.string(forKey: "HexCodeDemoSession").flatMap(TodaySessionDemo.init(rawValue:))
        #else
        nil
        #endif
    }

    /// The preset after `preset` for the Sample session row, nil (Off) after the last.
    static func next(after preset: TodaySessionDemo?) -> TodaySessionDemo? {
        guard let preset else { return allCases.first }
        let index = allCases.firstIndex(of: preset)! + 1
        return index < allCases.count ? allCases[index] : nil
    }

    static let reply = """
    ## Login screen rewrite
    Now: Wiring the new token refresh into the API client.
    Next: Run the auth tests, then fix whatever breaks.
    """

    /// 150 characters.
    static let longNow = "Three apps share one API client in packages/api: the web app, the phone app and the admin tool. Token "
        + "refresh lives there, so one fix covers them all."

    /// 298 characters.
    static let longestNow = "Mapping which screens need a signed-in user and which can open read-only. Settings, the project list and "
        + "the session view can all be read without logging in. Billing and team admin both write data, so they "
        + "stay behind login. This list becomes the read-only flag in the router next. Edits hide later."

    static let formattedReply = "## Login `401` fix\nNow: The **token refresh** in `client.ts` now retries once; the login page at "
        + "http://localhost:5173/login no longer shows a 401.\nNext: Run `bun test auth`, then open a PR.\n"
        + "Link: Issue 412 | https://github.com/example/app/issues/412"

    static let bulletsReply = """
    ## Safer remove_item ideas
    Now: Four one-line ways to make `remove_item` safer, without changing code.
    - Validate non-negative `qty`
    - Treat missing item as zero
    - Clamp result to zero
    - Return a copy of stock
    Next: Apply one of these
    """

    static let branchesReply = "## Mobile apps: evidence hunt\nNow: Found real mobile work across branches: `origin/ios-pwa-shell`, "
        + "`origin/ios-titlebar-safe-area`, `origin/mobile-bottom-nav`, `origin/pwa-push`, plus a since-deleted "
        + "`packages/mobile-voice` (iOS `Info.plist`) and Capacitor-style Android/iOS icon sets under "
        + "`packages/desktop/icons`. No mobile app source in…\n"
        + "Next: Finish sweeping branches, then publish the findings page.\n"
        + "Link: Prototype | http://Ivans-MacBook-Pro.local:8787/2026-11-06/mobile-apps/"

    static let fallbackReply = """
    Okay, I finished the token refresh and now I am going through the auth tests one at a time. Two of them \
    still lean on the old session helper, so I am rewriting those to use the new client first, which should not \
    take long, and after that I will run the whole suite again and see what else is left over.
    """

    /// A session in this state; call `start(_:)` to fill it.
    @MainActor
    func make(toasts: HexToaster, now: Date = Date()) -> TodaySession {
        let today = DailySession.title(for: now)
        let yesterday = DailySession.title(for: now.addingTimeInterval(-86_400))
        let memory = DemoMemory(lastOpenedDay: self == .empty ? nil : yesterday)
        let existing = [SessionListing(id: "ses_demo", title: today, directory: Self.directory, updatedAt: now, busySince: now.addingTimeInterval(-600))]
        let service = DemoService(
            sessions: [.empty, .newday].contains(self) ? [] : existing,
            fails: self == .failed
        )
        let summary = StatusSummaryModel(generator: DemoGenerator(reply: replyText, hangs: false))
        let session = TodaySession(
            setup: DailySessionSetup(service: service, memory: memory),
            summary: summary,
            directory: { Self.directory },
            model: { ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil) },
            toasts: toasts,
            control: DemoControl(running: sampleRunning(now: now), recent: Self.sampleTools(now: now))
        )
        return session
    }

    /// Opens the session and puts it in this state; `updating` then starts a refresh that never
    /// ends, and `updatefailed` one that fails.
    @MainActor
    func start(_ session: TodaySession, now: Date = Date()) async {
        await session.open()
        let running = sampleRunning(now: now)
        session.updateSample { live in
            live.todos = todos
            live.pending = samplePending
            live.projectID = "demo"
            for (id, child) in running.childSessions {
                live.children.insert(id)
                live.childSessions[id] = child
            }
            switch self {
            case .card:
                live.busySince = now.addingTimeInterval(-252)
                live.inbox = [InboxEntry(id: "msg_demo", delivery: "queue", text: "Then run the auth tests and tell me what failed.")]
                live.apply(running: running)
            case .branches:
                live.busySince = now.addingTimeInterval(-90)
            case .reconnecting, .updating, .updatefailed:
                live.busySince = now.addingTimeInterval(-252)
            default:
                break
            }
        }
        if self == .card { session.setSampleRecent(RecentSteps.make(Self.sampleTools(now: now))) }
        #if DEBUG
        // `-HexCodeSentMessage "<text>"`: a message in flight, for the You line.
        if let sent = UserDefaults.standard.string(forKey: "HexCodeSentMessage") { session.sentMessage = sent }
        #endif
        if self == .reconnecting { session.streamUp = false }
        switch self {
        case .updating:
            session.summary.generator = DemoGenerator(reply: nil, hangs: true)
            await session.summary.refresh()
        case .updatefailed:
            session.summary.generator = DemoGenerator(reply: nil, hangs: false)
            await session.summary.refresh()
        default:
            break
        }
    }

    private var replyText: String? {
        switch self {
        case .fallback: Self.fallbackReply
        case .plain: "## Login screen rewrite\nNow: Reading the auth tests to see what the old helper covers."
        case .long: "## API client\nNow: \(Self.longNow)\nNext: Fix token refresh in the shared client."
        case .longest: "## Read-only mode first\nNow: \(Self.longestNow)\nNext: Add a read-only flag to the router."
        case .formatted: Self.formattedReply
        case .bullets: Self.bulletsReply
        case .branches: Self.branchesReply
        case .needs: "## Login screen rewrite\nNow: Token refresh is done. Paused before changing login length.\nNext: Your answers, then the auth tests."
        case .approval: "## Login screen rewrite\nNow: The auth tests need a clean database. Waiting for your approval.\nNext: Then run the auth tests."
        default: Self.reply
        }
    }

    /// The questions and approvals waiting in this state.
    private var samplePending: [PendingItem] {
        switch self {
        case .needs:
            [PendingItem(question: PendingFormV2(id: "frm_demo", sessionID: "ses_demo", title: "Questions", kind: "question", fields: [
                .init(key: "q0", title: "Login length", description: "How long should a login last?", type: "string", options: [
                    .init(value: "30 days", label: "30 days", description: "Today's behaviour"),
                    .init(value: "7 days", label: "7 days", description: "Safer on shared computers"),
                    .init(value: "1 day", label: "1 day", description: ""),
                ], custom: true),
                .init(key: "q1", title: "Remember me", description: "Which sign-in options should the form show?", type: "multiselect", options: [
                    .init(value: "Remember me", label: "Remember me", description: ""),
                    .init(value: "Sign in with GitHub", label: "Sign in with GitHub", description: ""),
                ], custom: true),
            ]))]
        case .approval:
            [PendingItem(approval: PendingPermissionV2(
                id: "per_demo", sessionID: "ses_demo", action: "shell",
                resources: ["bun run db:reset", "bun test auth"], save: ["bun *"]
            ))]
        case .branches:
            [PendingItem(approval: PendingPermissionV2(
                id: "per_demo", sessionID: "ses_demo", action: "edit", resources: ["src/auth/session.ts"], save: ["*"],
                files: [.init(file: "src/auth/session.ts", patch: "--- a/src/auth/session.ts\n+++ b/src/auth/session.ts\n@@ -1,3 +1,3 @@\n-const maxAge = days(30)\n+const maxAge = days(7)\n export { maxAge }", status: "modified", additions: 1, deletions: 1)]
            ))]
        default:
            []
        }
    }

    /// Running now in the `card` state: a subagent and a dev server.
    private func sampleRunning(now: Date) -> RunningSnapshot {
        guard self == .card else { return RunningSnapshot(childBusySince: [:], childSessions: [:], shells: []) }
        return RunningSnapshot(
            childBusySince: ["ses_child": now.addingTimeInterval(-74)],
            childSessions: ["ses_child": ChildSession(title: "Find every caller of the old helper", agent: "general")],
            shells: [.init(id: "sh_demo", sessionID: "ses_demo", command: "bun run dev --port 5173", startedAt: now.addingTimeInterval(-312))]
        )
    }

    /// Recent in the `card` state: an edit, a command and a subagent start.
    private static func sampleTools(now: Date) -> [ToolPartV2] {
        let ms = { (seconds: TimeInterval) in Int64(now.addingTimeInterval(-seconds).timeIntervalSince1970 * 1000) }
        return [
            ToolPartV2(id: "t3", name: "subagent", status: "completed", description: "Find every caller of the old helper", agent: "general", completedMs: ms(74)),
            ToolPartV2(id: "t2", name: "shell", status: "completed", command: "bun test auth", exit: 1, completedMs: ms(150)),
            ToolPartV2(id: "t1", name: "edit", status: "completed", files: [ToolFileV2(file: "src/auth/session.ts", status: "modified", additions: 12, deletions: 3)], completedMs: ms(260)),
        ]
    }

    /// The agent's to-do list in this state; plain, fallback, longest, empty and new day keep none.
    private var todos: [TodoItem] {
        switch self {
        case .card: Self.loginTodos(.completed, .completed, .inProgress, .pending)
        case .needs, .approval: Self.loginTodos(.completed, .completed, .completed, .pending)
        case .reconnecting: Self.loginTodos(.completed, .inProgress, .pending, .pending)
        case .updating, .updatefailed: Self.loginTodos(.completed, .completed, .inProgress, .pending)
        case .long: Self.loginTodos(.completed, .completed, .completed, .completed)
        case .branches: [
                TodoItem(content: "List remote branches", status: .completed),
                TodoItem(content: "Search `packages/` for mobile code", status: .inProgress),
                TodoItem(content: "Publish the findings page", status: .pending),
            ]
        case .formatted: [
                TodoItem(content: "Find the 401", status: .completed),
                TodoItem(content: "Retry once in `client.ts`", status: .inProgress),
                TodoItem(content: "Run `bun test auth`", status: .pending),
            ]
        default: []
        }
    }

    /// The login rewrite's four items, with these statuses.
    private static func loginTodos(_ statuses: TodoItem.Status...) -> [TodoItem] {
        zip(["Remove old session helper", "New login form", "Token refresh", "Auth tests"], statuses).map {
            TodoItem(content: $0, status: $1)
        }
    }

    private static let directory = "/Users/example/Projects/hexcode"
}

@MainActor
private final class DemoMemory: DailySessionMemory {
    var lastOpenedDay: String?
    var lastSessionID: String?

    init(lastOpenedDay: String?) {
        self.lastOpenedDay = lastOpenedDay
    }
}

private struct DemoFailure: Error {}

private struct DemoService: DailySessionService {
    let sessions: [SessionListing]
    let fails: Bool

    func sessionsPage(directory: String, search: String, limit: Int, cursor: String?) async throws -> SessionListingPage {
        try await Task.sleep(for: .milliseconds(300))
        if fails { throw DemoFailure() }
        return SessionListingPage(sessions: sessions, next: nil)
    }

    func createSession(title: String, directory: String, model: ModelChoice) async throws -> SessionListing {
        SessionListing(id: "ses_new", title: title, directory: directory, updatedAt: Date())
    }

    func putMessageRules(sessionID: String) async throws {}
}

/// Answers and stops that always work, after a short wait.
private struct DemoControl: SessionControlAPI {
    let running: RunningSnapshot
    let recent: [ToolPartV2]

    func replyToApproval(sessionID: String, requestID: String, decision: SessionStateAPIClient.PermissionDecision, note: String?) async throws {
        try await Task.sleep(for: .milliseconds(500))
    }

    func answerQuestion(sessionID: String, formID: String, answer: [String: FormAnswerV2]) async throws {
        try await Task.sleep(for: .milliseconds(500))
    }

    func dismissQuestion(sessionID: String, formID: String, note: String) async throws {
        try await Task.sleep(for: .milliseconds(500))
    }

    func interrupt(sessionID: String) async throws -> Bool {
        try await Task.sleep(for: .milliseconds(300))
        return true
    }

    func runningSubagents(rootID: String) async throws -> [String] { [] }

    func running(rootID: String, directory: String) async throws -> RunningSnapshot { running }

    func recentTools(sessionID: String) async throws -> [ToolPartV2] { recent }
}

private struct DemoGenerator: SummaryGenerating {
    /// Nil fails.
    let reply: String?
    let hangs: Bool

    func generateSummary(prompt: String) async throws -> String {
        try await Task.sleep(for: hangs ? .seconds(3600) : .milliseconds(600))
        guard let reply else { throw DemoFailure() }
        return reply
    }
}
