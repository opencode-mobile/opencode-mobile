import Foundation
import Testing
@testable import Hex_Code

/// A store the test can look into.
@MainActor
final class MemorySummaryStore: SummaryStoring {
    var saved: SavedSummary?
    func load() -> SavedSummary? { saved }
    func save(_ saved: SavedSummary) { self.saved = saved }
}

/// The last card survives the app being closed (app plan, "Empty or failed reply").
@MainActor
struct LastCardTests {
    private struct Generator: SummaryGenerating {
        var reply: String
        func generateSummary(prompt: String) async throws -> String { reply }
    }

    private let start = Date(timeIntervalSince1970: 1_793_880_000) // Thursday 5 November, 12:00 UTC
    private let card = StatusSummary.card(StatusCard(title: "Task", now: "Doing it."))

    @Test func theSavedCardShowsAtOnceWithItsAge() {
        let store = MemorySummaryStore()
        store.saved = SavedSummary(sessionID: "ses_a", summary: card, updatedAt: start)
        let model = StatusSummaryModel(store: store, now: { [start] in start + 12 * 60 })
        model.open(sessionID: "ses_a")
        #expect(model.summary == card)
        #expect(model.refreshLabel(at: start + 12 * 60) == .minutesAgo(12))
    }

    @Test func anotherSessionsCardIsNotShown() {
        let store = MemorySummaryStore()
        store.saved = SavedSummary(sessionID: "ses_yesterday", summary: card, updatedAt: start)
        let model = StatusSummaryModel(store: store)
        model.open(sessionID: "ses_today")
        #expect(model.summary == nil)
    }

    @Test func aNewCardIsSavedForTheSession() async {
        let store = MemorySummaryStore()
        let model = StatusSummaryModel(generator: Generator(reply: "## Task\nNow: Doing it."), store: store, now: { [start] in start })
        model.open(sessionID: "ses_a")
        await model.refresh()
        #expect(store.saved == SavedSummary(sessionID: "ses_a", summary: card, updatedAt: start))
    }

    @Test func noCardAndAnEmptyReplyWhileWorkingSaysTheAgentIsWorking() async {
        let store = MemorySummaryStore()
        let model = StatusSummaryModel(
            generator: Generator(reply: ""),
            store: store,
            timeZone: TimeZone(identifier: "UTC")!,
            now: { [start] in start }
        )
        model.isWorking = { true }
        model.open(sessionID: "ses_a")
        await model.refresh()
        #expect(model.summary == .card(StatusCard(
            title: "Thursday 5 November",
            now: "The agent is working.",
            next: "The summary updates when this run ends."
        )))
        #expect(!model.failed)
        // The placeholder isn't saved: the next open still waits for a real card.
        #expect(store.saved == nil)
    }

    @Test func anEmptyReplyWhileWorkingKeepsTheSavedCard() async {
        let store = MemorySummaryStore()
        store.saved = SavedSummary(sessionID: "ses_a", summary: card, updatedAt: start)
        let model = StatusSummaryModel(generator: Generator(reply: ""), store: store, now: { [start] in start })
        model.isWorking = { true }
        model.open(sessionID: "ses_a")
        await model.refresh()
        #expect(model.summary == card)
        #expect(!model.failed)
    }

    @Test func aRelaunchWhileTheAgentWorksShowsTheLastCard() async {
        let store = MemorySummaryStore()
        let server = FakeSessionServer()
        server.listed = [SessionListing(id: "ses_a", title: "2026-11-05", directory: "/p", updatedAt: start, busySince: start)]
        func session() -> (TodaySession, FakeTransport) {
            let transport = FakeTransport()
            let session = TodaySession(
                setup: DailySessionSetup(service: server, memory: FakeMemory("2026-11-05")),
                summary: StatusSummaryModel(store: store, now: { [start] in start }),
                directory: { "/p" },
                model: { ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil) },
                toasts: HexToaster(),
                api: server,
                stream: LiveEventStream(transport: transport, sleep: { _ in try await Task.sleep(for: .milliseconds(10)) }),
                now: { [start] in start },
                timeZone: TimeZone(identifier: "UTC")!
            )
            return (session, transport)
        }
        func eventually(_ condition: @MainActor () -> Bool) async {
            for _ in 0 ..< 200 where !condition() { try? await Task.sleep(for: .milliseconds(5)) }
        }
        let (first, firstStream) = session()
        await first.open()
        await eventually { firstStream.connections == 1 }
        firstStream.send(EventLines.connected)
        await eventually { first.summary.summary == card }
        // Closed, and opened again while the agent works: the saved card shows at once…
        server.reply = ""
        server.snapshot = LiveSnapshot(busySince: start, hasRun: true, children: [], pending: [], inbox: [])
        let (second, secondStream) = session()
        await second.open()
        #expect(second.summary.summary == card)
        // …and stays, without "Couldn't update", when the server answers with no text mid-run.
        await eventually { secondStream.connections == 1 }
        secondStream.send(EventLines.connected)
        await eventually { second.busySince != nil && !second.summary.isUpdating && server.calls.filter { $0 == .generate("ses_a") }.count == 2 }
        #expect(second.summary.summary == card)
        #expect(!second.summary.failed)
    }

    // MARK: Before setup finishes

    /// Setup that waits until the test lets it finish.
    final class SlowService: DailySessionService, @unchecked Sendable {
        let gate: Gate
        let server: FakeSessionServer

        init(gate: Gate, server: FakeSessionServer) {
            self.gate = gate
            self.server = server
        }

        func sessionsPage(directory: String, search: String, limit: Int, cursor: String?) async throws -> SessionListingPage {
            await gate.wait()
            return try await server.sessionsPage(directory: directory, search: search, limit: limit, cursor: cursor)
        }

        func createSession(title: String, directory: String, model: ModelChoice) async throws -> SessionListing {
            try await server.createSession(title: title, directory: directory, model: model)
        }

        func putMessageRules(sessionID: String) async throws {
            try await server.putMessageRules(sessionID: sessionID)
        }
    }

    private func slowSession(_ store: MemorySummaryStore, listed: [SessionListing], gate: Gate) -> TodaySession {
        let server = FakeSessionServer()
        server.listed = listed
        return TodaySession(
            setup: DailySessionSetup(service: SlowService(gate: gate, server: server), memory: FakeMemory("2026-11-05")),
            summary: StatusSummaryModel(store: store, now: { [start] in start }),
            directory: { "/p" },
            model: { ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil) },
            toasts: HexToaster(),
            now: { [start] in start },
            timeZone: TimeZone(identifier: "UTC")!
        )
    }

    private func saved(_ id: String, day: String = "2026-11-05", directory: String = "/p") -> SavedSummary {
        SavedSummary(sessionID: id, summary: card, updatedAt: start - 600, day: day, directory: directory)
    }

    private func todays(_ id: String) -> SessionListing {
        SessionListing(id: id, title: "2026-11-05", directory: "/p", updatedAt: start, busySince: start)
    }

    private func settle() async {
        for _ in 0 ..< 10 { await Task.yield() }
    }

    @Test func todaysCardShowsBeforeSetupFinishesAndStays() async {
        let store = MemorySummaryStore()
        store.saved = saved("ses_a")
        let gate = Gate()
        let session = slowSession(store, listed: [todays("ses_a")], gate: gate)
        let opening = Task { await session.open() }
        await settle()
        #expect(session.setup == .opening)
        #expect(session.summary.summary == card)
        #expect(session.summary.refreshLabel(at: start) == .minutesAgo(10))
        gate.open()
        await opening.value
        #expect(session.setup == .ready(sessionID: "ses_a"))
        #expect(session.summary.summary == card)
    }

    @Test func yesterdaysCardDoesNotShow() async {
        let store = MemorySummaryStore()
        store.saved = saved("ses_y", day: "2026-11-04")
        let gate = Gate()
        let session = slowSession(store, listed: [], gate: gate)
        let opening = Task { await session.open() }
        await settle()
        #expect(session.summary.summary == nil)
        gate.open()
        await opening.value
    }

    @Test func anotherProjectsCardDoesNotShow() async {
        let store = MemorySummaryStore()
        store.saved = saved("ses_q", directory: "/q")
        let gate = Gate()
        let session = slowSession(store, listed: [], gate: gate)
        let opening = Task { await session.open() }
        await settle()
        #expect(session.summary.summary == nil)
        gate.open()
        await opening.value
    }

    @Test func theCardGoesWhenSetupFindsADifferentSession() async {
        let store = MemorySummaryStore()
        store.saved = saved("ses_old")
        let gate = Gate()
        let session = slowSession(store, listed: [todays("ses_new")], gate: gate)
        let opening = Task { await session.open() }
        await settle()
        #expect(session.summary.summary == card)
        gate.open()
        await opening.value
        #expect(session.setup == .ready(sessionID: "ses_new"))
        #expect(session.summary.summary != card)
    }

    @Test func aSavedCardCarriesItsDateAndProject() async {
        let store = MemorySummaryStore()
        let model = StatusSummaryModel(generator: Generator(reply: "## Task\nNow: Doing it."), store: store, now: { [start] in start })
        model.preopen(day: "2026-11-05", directory: "/p")
        model.open(sessionID: "ses_a")
        await model.refresh()
        #expect(store.saved?.day == "2026-11-05")
        #expect(store.saved?.directory == "/p")
    }
}
