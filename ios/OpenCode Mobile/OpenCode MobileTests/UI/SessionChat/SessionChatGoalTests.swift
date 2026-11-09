import Foundation
import Testing
@testable import OpenCode_Mobile

/// The Session Chat goal region: read, follow, retry, reconnect and the goal actions.
@MainActor
struct SessionChatGoalTests {
    private static let session = "ses_goal"

    @Test func constants_matchDesktop() {
        #expect(SessionChatGoal.retryDelaysMs == [1_000, 2_000, 4_000])
        #expect(SessionChatGoal.commandTimeoutMs == 15_000)
    }

    @Test func start_readsTheGoal() async {
        let repository = FakeGoalRepository(goal: goal(.active))
        let region = makeRegion(repository)
        region.start()
        await settle { region.state.goal != nil }

        #expect(region.state.goal == goal(.active))
        #expect(repository.reads == 1)
    }

    @Test func v1_readsNothingAndShowsNoGoal() async {
        let repository = FakeGoalRepository(goal: goal(.active), available: false)
        let region = makeRegion(repository)
        region.start()
        try? await Task.sleep(for: .milliseconds(30))

        #expect(region.state.goal == nil)
        #expect(repository.reads == 0)
    }

    @Test func failedRead_showsNoGoalAndRetriesQuietly() async {
        let repository = FakeGoalRepository(goal: goal(.paused), failures: 2)
        let region = makeRegion(repository)
        region.start()
        await settle { region.state.goal != nil }

        #expect(repository.reads == 3)
        #expect(region.state.goal?.status == .paused)
        #expect(region.state.errorNotice == nil)
    }

    @Test func failedRead_givesUpAfterTheLastRetry() async {
        let repository = FakeGoalRepository(goal: goal(.active), failures: 99)
        let region = makeRegion(repository)
        region.start()
        await settle { repository.reads >= 4 }
        try? await Task.sleep(for: .milliseconds(30))

        #expect(repository.reads == 4)
        #expect(region.state.goal == nil)
    }

    @Test func changedEvent_replacesTheGoal_forThisSessionOnly() async {
        let stream = FakeForegroundEventStream()
        let region = makeRegion(FakeGoalRepository(goal: nil), stream: stream)
        region.start()
        await settle { stream.eventSubscriberCount > 0 }

        stream.emit(changed(sessionID: "ses_other", goal(.active)))
        try? await Task.sleep(for: .milliseconds(20))
        #expect(region.state.goal == nil)

        stream.emit(changed(sessionID: Self.session, goal(.paused, reason: "Paused by the user")))
        await settle { region.state.goal != nil }
        #expect(region.state.goal?.status == .paused)

        stream.emit(changed(sessionID: Self.session, nil))
        await settle { region.state.goal == nil }
        #expect(region.state.goal == nil)
    }

    @Test func reconnect_readsTheGoalAgain() async {
        let stream = FakeForegroundEventStream()
        let repository = FakeGoalRepository(goal: goal(.active))
        let region = makeRegion(repository, stream: stream)
        region.start()
        await settle { region.state.goal != nil && stream.eventSubscriberCount > 0 }

        repository.goal = goal(.complete, reason: "All tests pass")
        stream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: nil))
        await settle { region.state.goal?.status == .complete }

        #expect(repository.reads == 2)
    }

    @Test func disconnected_keepsTheGoalStaleAndBlocksActions() async {
        let repository = FakeGoalRepository(goal: goal(.active))
        let region = makeRegion(repository)
        region.start()
        await settle { region.state.goal != nil }

        region.setConnected(false)
        #expect(region.state.isStale)
        #expect(region.state.goal != nil)
        #expect(!region.canAct)
        region.perform(.pause)
        #expect(region.state.pendingAction == nil)

        region.setConnected(true)
        #expect(!region.state.isStale)
        await settle { repository.reads == 2 }
        #expect(repository.reads == 2)
    }

    @Test func action_showsProgressUntilTheChangedEvent() async {
        let stream = FakeForegroundEventStream()
        let repository = FakeGoalRepository(goal: goal(.active))
        let region = makeRegion(repository, stream: stream)
        region.start()
        await settle { region.state.goal != nil && stream.eventSubscriberCount > 0 }
        region.openSheet()

        region.perform(.pause)
        #expect(region.state.pendingAction == .pause)
        await settle { repository.sent == [.pause] }
        // Sent, but not confirmed: still in progress.
        try? await Task.sleep(for: .milliseconds(20))
        #expect(region.state.pendingAction == .pause)

        stream.emit(changed(sessionID: Self.session, goal(.paused, reason: "Paused by the user")))
        await settle { region.state.pendingAction == nil }
        #expect(region.state.goal?.status == .paused)
        #expect(!region.state.isSheetOpen)
        #expect(region.state.errorNotice == nil)
    }

    @Test func action_onlyWhatTheStatusAllows() async {
        let repository = FakeGoalRepository(goal: goal(.active))
        let region = makeRegion(repository)
        region.start()
        await settle { region.state.goal != nil }

        region.perform(.resume)
        #expect(region.state.pendingAction == nil)
        #expect(SessionChatGoal.allows(.pause, for: goal(.active)))
        #expect(!SessionChatGoal.allows(.pause, for: goal(.paused)))
        #expect(SessionChatGoal.allows(.resume, for: goal(.paused)))
        #expect(SessionChatGoal.allows(.clear, for: goal(.complete)))
    }

    @Test func action_failureShowsTheServerMessageAndReadsAgain() async {
        let repository = FakeGoalRepository(goal: goal(.paused))
        repository.sendError = CommandError.http(
            statusCode: 500,
            message: #"{"_tag":"CommandExecutionError","command":"goal","message":"No paused goal to resume."}"#
        )
        let region = makeRegion(repository)
        region.start()
        await settle { region.state.goal != nil }

        region.perform(.resume)
        await settle { region.state.errorNotice != nil }
        #expect(region.state.pendingAction == nil)
        #expect(region.state.errorNotice == "Couldn’t resume the goal: No paused goal to resume")
        await settle { repository.reads == 2 }
        #expect(repository.reads == 2)

        region.clearNotice()
        #expect(region.state.errorNotice == nil)
    }

    @Test func action_timeoutFreesTheButtonAndReadsAgain() async {
        let repository = FakeGoalRepository(goal: goal(.active))
        let region = makeRegion(repository, commandTimeoutMs: 40)
        region.start()
        await settle { region.state.goal != nil }

        region.perform(.clear)
        #expect(region.state.pendingAction == .clear)
        await settle { region.state.pendingAction == nil }
        #expect(region.state.pendingAction == nil)
        await settle { repository.reads == 2 }
        #expect(repository.reads == 2)
        #expect(region.state.errorNotice == nil)
    }

    @Test func sheet_opensOnlyWithAGoalAndClosesWhenItGoes() async {
        let stream = FakeForegroundEventStream()
        let region = makeRegion(FakeGoalRepository(goal: nil), stream: stream)
        region.start()
        await settle { stream.eventSubscriberCount > 0 }
        region.openSheet()
        #expect(!region.state.isSheetOpen)

        stream.emit(changed(sessionID: Self.session, goal(.active)))
        await settle { region.state.goal != nil }
        region.openSheet()
        #expect(region.state.isSheetOpen)

        stream.emit(changed(sessionID: Self.session, nil))
        await settle { region.state.goal == nil }
        #expect(!region.state.isSheetOpen)
    }

    @Test func placeholderSession_readsNothingUntilRebound() async {
        let repository = FakeGoalRepository(goal: goal(.active))
        let region = SessionChatGoal(
            sessionID: OptimisticSessionCreate.placeholderSessionID,
            worktree: "/Users/example/project",
            repository: repository,
            foregroundEventStream: nil,
            retryDelaysMs: [1, 1, 1]
        )
        region.start()
        try? await Task.sleep(for: .milliseconds(20))
        #expect(repository.reads == 0)

        region.rebind(sessionID: Self.session)
        region.start()
        await settle { region.state.goal != nil }
        #expect(repository.reads == 1)
    }

    // MARK: Helpers

    private func makeRegion(
        _ repository: FakeGoalRepository,
        stream: FakeForegroundEventStream? = nil,
        commandTimeoutMs: Int = 10_000
    ) -> SessionChatGoal {
        SessionChatGoal(
            sessionID: Self.session,
            worktree: "/Users/example/project",
            repository: repository,
            foregroundEventStream: stream,
            retryDelaysMs: [1, 1, 1],
            commandTimeoutMs: commandTimeoutMs
        )
    }

    private func goal(_ status: SessionGoalStatus, reason: String? = nil) -> SessionGoal {
        SessionGoal(id: "goal_1", objective: "Make the tests pass", status: status, reason: reason, rounds: 2, maxRounds: 50, updatedAt: 10)
    }

    private func changed(sessionID: String, _ goal: SessionGoal?) -> OpencodeEvent {
        var properties: [String: AnySendable] = ["sessionID": .string(sessionID)]
        if let goal {
            var state: [String: AnySendable] = [
                "id": .string(goal.id),
                "objective": .string(goal.objective),
                "status": .string(goal.status.rawValue),
                "rounds": .int(goal.rounds),
                "maxRounds": .int(goal.maxRounds),
                "updatedAt": .int64(goal.updatedAt),
            ]
            if let reason = goal.reason { state["reason"] = .string(reason) }
            properties["goal"] = .dictionary(state)
        } else {
            properties["goal"] = .null
        }
        return OpencodeEvent(type: SessionGoalAdapter.changedEvent, sessionID: sessionID, rawProperties: properties)
    }

    private func settle(timeoutMs: Int = 2000, _ predicate: @escaping @MainActor () -> Bool) async {
        let deadline = Date().addingTimeInterval(Double(timeoutMs) / 1000)
        while !predicate(), Date() < deadline {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }
}

private final class FakeGoalRepository: SessionGoalRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var _goal: SessionGoal?
    private var _reads = 0
    private var _sent: [SessionGoalAction] = []
    private var failuresLeft: Int
    private let available: Bool
    var sendError: Error?

    init(goal: SessionGoal?, available: Bool = true, failures: Int = 0) {
        self._goal = goal
        self.available = available
        self.failuresLeft = failures
    }

    var goal: SessionGoal? {
        get { lock.withLock { _goal } }
        set { lock.withLock { _goal = newValue } }
    }

    var reads: Int { lock.withLock { _reads } }
    var sent: [SessionGoalAction] { lock.withLock { _sent } }

    func isAvailable() async -> Bool { available }

    func goal(sessionID _: String, directory _: String) async throws -> SessionGoal? {
        try lock.withLock {
            _reads += 1
            if failuresLeft > 0 {
                failuresLeft -= 1
                throw GoalAPIError.httpStatus(404, nil)
            }
            return _goal
        }
    }

    func send(_ action: SessionGoalAction, sessionID _: String, directory _: String) async throws {
        try lock.withLock {
            _sent.append(action)
            if let sendError { throw sendError }
        }
    }
}
