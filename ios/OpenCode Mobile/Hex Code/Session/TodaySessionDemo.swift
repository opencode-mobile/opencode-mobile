import Foundation

/// Fake sessions for looking at home without a server: Settings › Developer › Sample session, or
/// `-HexCodeDemoSession <preset>` at launch (debug builds). Nothing here talks to OpenCode. The
/// presets are the same on both platforms.
enum TodaySessionDemo: String, CaseIterable {
    /// Working, with a four-step plan and a message waiting.
    case card
    /// Idle, a card with no steps and no Next line.
    case plain
    /// The reply didn't match the card shape.
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
    /// A Now of about 150 characters.
    case long
    /// A Now of about 300 characters, the most the prompt asks for.
    case longest
    /// Code, bold, a `Link:` line and a localhost link: formatting and link tiles.
    case formatted

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
    - [x] Remove old session helper
    - [x] New login form
    - [ ] Token refresh
    - [ ] Auth tests
    """

    static let formattedReply = """
    ## Login screen `v2`
    Now: Moved token refresh into `AuthClient`, so an **expired session** renews quietly. Preview it at http://localhost:5173/login.
    Next: Run `npm test`, then read the [refresh docs](https://developer.mozilla.org/docs/Web/HTTP/Authentication).
    - [x] Remove `SessionHelper`
    - [x] New login form
    - [ ] **Token refresh**
    - [ ] Auth tests
    Link: Login preview | http://localhost:5173/login
    Link: https://github.com/sst/opencode/pull/42
    """

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
            toasts: toasts
        )
        return session
    }

    /// Opens the session and puts it in this state; `updating` then starts a refresh that never
    /// ends, and `updatefailed` one that fails.
    @MainActor
    func start(_ session: TodaySession, now: Date = Date()) async {
        await session.open()
        switch self {
        case .card:
            session.busySince = now.addingTimeInterval(-252)
            session.waitingCount = 1
        case .needs:
            session.pendingInput = PendingItem(id: "frm_demo", sessionID: "ses_demo", kind: .question, text: "2 questions · login length and remember-me")
        case .approval:
            session.pendingInput = PendingItem(id: "per_demo", sessionID: "ses_demo", kind: .approval, text: "Run bun run db:reset on the dev database")
        case .reconnecting:
            session.streamUp = false
            session.busySince = now.addingTimeInterval(-252)
        case .updating, .updatefailed:
            session.busySince = now.addingTimeInterval(-95)
        default:
            break
        }
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
        case .long: "## Login screen rewrite\nNow: Wiring the new token refresh into the API client, then checking that an expired session renews quietly instead of signing you out.\nNext: Run the auth tests, then fix whatever breaks.\n- [x] Remove old session helper\n- [x] New login form\n- [ ] Token refresh\n- [ ] Auth tests"
        case .longest: "## Login screen rewrite\nNow: Wiring the new token refresh into the API client. Two auth tests still lean on the old session helper, so I am rewriting those to use the new client first. After that the expired-session case should renew quietly, and the remember-me flag keeps its 30 day login.\nNext: Run the whole auth suite, then fix what breaks.\n- [x] Remove old session helper\n- [x] New login form\n- [ ] Token refresh\n- [ ] Auth tests"
        case .formatted: Self.formattedReply
        case .needs: "## Login screen rewrite\nNow: Token refresh is done. Paused before changing login length.\nNext: Your answers, then the auth tests.\n- [x] Remove old session helper\n- [x] New login form\n- [x] Token refresh\n- [ ] Auth tests"
        case .approval: "## Login screen rewrite\nNow: The auth tests need a clean database. Waiting for your approval.\nNext: Then run the auth tests.\n- [x] Remove old session helper\n- [x] New login form\n- [x] Token refresh\n- [ ] Auth tests"
        default: Self.reply
        }
    }

    private static let directory = "/Users/example/Projects/hexcode"
}

@MainActor
private final class DemoMemory: DailySessionMemory {
    var lastOpenedDay: String?

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
