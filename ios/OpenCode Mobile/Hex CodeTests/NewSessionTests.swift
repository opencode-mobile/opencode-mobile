import Foundation
import Testing
@testable import Hex_Code

/// Settings › Start a new session (app plan, "Settings and finish (M6)" › Start a new session, and
/// "Testing" › New session).
@MainActor
struct StartNewSessionTests {
    private func opened(goal: TodayGoal? = TodayGoal(id: "g1", objective: "Ship it", status: .active, rounds: 1, maxRounds: 50)) async -> (GoalHarness, FakeMemory, MemorySummaryStore, MemoryAskStore) {
        let memory = FakeMemory("2026-11-05", session: "ses_a")
        let asks = MemoryAskStore()
        asks.saved = SavedAsks(sessionID: "ses_a", shown: nil, history: [AskEntry(question: "Why?", state: .answered("Because."), askedAt: Date())])
        let ask = AskModel(store: asks)
        let harness = GoalHarness(
            goal: goal,
            busy: true,
            inbox: [InboxEntry(id: "msg_1", delivery: "queue", text: "later")],
            memory: memory,
            ask: ask
        )
        let store = MemorySummaryStore()
        await harness.open()
        return (harness, memory, store, asks)
    }

    @Test func theNewSessionHasTheNextNumberAndStartsEmpty() async {
        let (harness, memory, _, asks) = await opened()
        let session = harness.session
        #expect(session.waitingCount == 1)
        #expect(session.goal != nil)
        #expect(session.ask.history.count == 1)
        // The new session never ran and has nothing waiting.
        harness.server.snapshot = LiveSnapshot(busySince: nil, hasRun: false, children: [], pending: [], inbox: [])
        let result = await session.startNewSession()
        #expect(result == .started)
        #expect(harness.server.calls.contains(.create(title: "2026-11-05 (2)", model: ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil))))
        #expect(session.sessionID == "ses_new1")
        #expect(session.goal == nil)
        #expect(session.waitingCount == 0)
        #expect(session.sentMessage == nil)
        #expect(session.pendingItems.isEmpty)
        #expect(session.todos.isEmpty)
        #expect(session.ask.history.isEmpty && session.ask.shown == nil)
        #expect(asks.saved?.sessionID == "ses_new1")
        guard case let .card(card)? = session.summary.summary else {
            Issue.record("expected the empty card")
            return
        }
        #expect(card.now == "New session. Nothing running yet.")
        #expect(card.links.isEmpty)
        #expect(memory.lastSessionID == "ses_new1")
        #expect(memory.lastOpenedDay == "2026-11-05")
        // The notice is the new session's, not the new day's.
        #expect(harness.toasts.current?.title == "New session")
        #expect(harness.toasts.current?.detail == "The earlier one is kept on your computer.")
    }

    @Test func theOldSessionIsNeverChanged() async {
        let (harness, _, _, _) = await opened()
        harness.server.snapshot = LiveSnapshot(busySince: nil, hasRun: false, children: [], pending: [], inbox: [])
        _ = await harness.session.startNewSession()
        #expect(harness.server.cancelled.isEmpty)
        #expect(harness.goals.commands.isEmpty)
        #expect(!harness.server.calls.contains(.rules("ses_a")) || harness.server.calls.filter { $0 == .rules("ses_a") }.count == 1)
    }

    @Test func theStreamFollowsTheNewSession() async {
        let (harness, _, _, _) = await opened(goal: nil)
        harness.server.snapshot = LiveSnapshot(busySince: nil, hasRun: false, children: [], pending: [], inbox: [])
        _ = await harness.session.startNewSession()
        await harness.eventually { harness.transport.connections == 2 }
        harness.transport.send(EventLines.connected)
        await harness.eventually { harness.server.calls.contains(.snapshot("ses_new1")) }
        // The old session's events no longer reach home.
        await harness.send(EventLines.started("ses_a"))
        #expect(harness.session.busySince == nil)
        await harness.send(EventLines.started("ses_new1"))
        #expect(harness.session.busySince != nil)
    }

    @Test func creatingFailsKeepsTodaysSessionWithTheNotice() async {
        let (harness, memory, _, _) = await opened()
        harness.server.failsCreate = true
        #expect(await harness.session.startNewSession() == .failed)
        #expect(harness.session.sessionID == "ses_a")
        #expect(harness.session.waitingCount == 1)
        #expect(memory.lastSessionID == "ses_a")
        #expect(harness.toasts.current?.title == "Couldn’t start a new session")
        #expect(harness.toasts.current?.detail == "Today’s session is still open.")
    }

    @Test func theRulesFailingIsASetupFailureAndTryAgainFindsTheNewSession() async {
        let (harness, _, _, _) = await opened(goal: nil)
        harness.server.failsRules = true
        #expect(await harness.session.startNewSession() == .setupFailed)
        #expect(harness.session.setup == .failed(retrying: false))
        harness.server.failsRules = false
        await harness.session.retry()
        #expect(harness.session.sessionID == "ses_new1")
        #expect(harness.server.calls.filter { if case .create = $0 { true } else { false } }.count == 1)
    }

    @Test func theSavedCardIsForgotten() async {
        let store = MemorySummaryStore()
        store.saved = SavedSummary(
            sessionID: "ses_a",
            summary: .card(StatusCard(title: "Old", now: "Done.")),
            updatedAt: Date(),
            day: "2026-11-05",
            directory: "/p"
        )
        let summary = StatusSummaryModel(store: store)
        summary.forgetSaved()
        #expect(store.saved == nil)
    }
}

/// The confirmation's waiting line is a plural in the string catalog.
struct NewSessionCopyTests {
    @Test func oneWaitingMessageIsSingular() {
        let one = 1
        let three = 3
        #expect(String(localized: "settings.new_session.waiting", defaultValue: "Its \(one) waiting messages still send there.") == "Its waiting message still sends there.")
        #expect(String(localized: "settings.new_session.waiting", defaultValue: "Its \(three) waiting messages still send there.") == "Its 3 waiting messages still send there.")
    }
}
