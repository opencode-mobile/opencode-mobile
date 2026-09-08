import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct QuestionLiveStoreTests {
    @Test func questionAskedBurst_isNeitherDelayedNorAmplified() async throws {
        let first = testQuestion("que-1")
        let second = testQuestion("que-2")
        let third = testQuestion("que-3")
        let repository = QuestionLiveStoreFakeSessionRepository(
            script: [
                [],
                [first],
                [first, second],
                [first, second, third],
            ]
        )
        let eventStream = FakeForegroundEventStream()
        let store = DefaultQuestionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = QuestionCollector(store: store, directory: "/repo")
        defer { collector.cancel() }

        _ = try await collector.awaitEmission { $0.isEmpty }

        let started = ContinuousClock.now
        eventStream.emit(OpencodeEvent(type: "question.asked", sessionID: "ses-1"))
        _ = try await collector.awaitEmission { $0.map(\.id) == ["que-1"] }
        #expect(started.duration(to: ContinuousClock.now) < .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs))

        eventStream.emit(OpencodeEvent(type: "question.asked", sessionID: "ses-1"))
        eventStream.emit(OpencodeEvent(type: "question.asked", sessionID: "ses-1"))
        _ = try await collector.awaitEmission { $0.map(\.id) == ["que-1", "que-2", "que-3"] }

        #expect(repository.questionFetchCount == 4)
    }

    @Test func reconnectPair_coalescesIntoOneFetch() async throws {
        let repository = QuestionLiveStoreFakeSessionRepository(script: [[]])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultQuestionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = QuestionCollector(store: store, directory: "/repo")
        defer { collector.cancel() }

        _ = try await collector.awaitEmission { $0.isEmpty }
        #expect(repository.questionFetchCount == 1)

        eventStream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: nil))
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        await settleBeyondDebounce()

        #expect(repository.questionFetchCount == 2)
    }

    @Test func unchangedList_doesNotReEmit() async throws {
        let request = testQuestion("que-1")
        let repository = QuestionLiveStoreFakeSessionRepository(script: [[request]])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultQuestionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = QuestionCollector(store: store, directory: "/repo")
        defer { collector.cancel() }

        _ = try await collector.awaitEmission { $0.map(\.id) == ["que-1"] }
        #expect(collector.emissions.count == 1)

        eventStream.emit(OpencodeEvent(type: "question.replied", sessionID: "ses-1"))
        eventStream.emit(OpencodeEvent(type: "question.replied", sessionID: "ses-1"))
        try await pollUntil { repository.questionFetchCount == 3 }

        #expect(collector.emissions.count == 1)
        #expect(repository.questionFetchCount == 3)
    }
}

@MainActor
private final class QuestionCollector {
    private(set) var emissions: [[QuestionRequest]] = []
    private var task: Task<Void, Never>?

    init(store: DefaultQuestionLiveStore, directory: String) {
        task = Task {
            for await requests in store.observeQuestions(directory: directory) {
                emissions.append(requests)
            }
        }
    }

    func awaitEmission(
        timeout: Duration = .seconds(3),
        matching predicate: @escaping ([QuestionRequest]) -> Bool
    ) async throws -> [QuestionRequest] {
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            if let match = emissions.last(where: predicate) {
                return match
            }
            try await Task.sleep(for: .milliseconds(20))
        }
        throw QuestionLiveStoreTimeoutError()
    }

    func cancel() {
        task?.cancel()
        task = nil
    }
}

private struct QuestionLiveStoreTimeoutError: Error {}

@MainActor
private func pollUntil(
    timeout: Duration = .seconds(3),
    predicate: () -> Bool
) async throws {
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
        if predicate() { return }
        try await Task.sleep(for: .milliseconds(20))
    }
    throw QuestionLiveStoreTimeoutError()
}

@MainActor
private func settleBeyondDebounce() async {
    try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs + 400))
}

private func testQuestion(_ id: String) -> QuestionRequest {
    QuestionRequest(
        id: id,
        sessionID: "ses-1",
        questions: [
            QuestionInfo(
                question: "Which approach?",
                header: "Approach",
                options: [QuestionOption(label: "A", description: "First")],
                multiple: false,
                custom: true
            ),
        ],
        toolMessageID: nil,
        toolCallID: nil
    )
}

private final class QuestionLiveStoreFakeSessionRepository: SessionRepository, @unchecked Sendable {
    private let script: [[QuestionRequest]]
    private let lock = NSLock()
    private var index = 0
    private var _questionFetchCount = 0

    var questionFetchCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return _questionFetchCount
    }

    init(script: [[QuestionRequest]]) {
        self.script = script
    }

    func listPendingQuestions(directory: String?) async throws -> [QuestionRequest] {
        lock.lock()
        _questionFetchCount += 1
        let result: [QuestionRequest]
        if script.isEmpty {
            result = []
        } else if index < script.count {
            result = script[index]
            index += 1
        } else {
            result = script[script.count - 1]
        }
        lock.unlock()
        return result
    }

    func listSessions(directory: String?) async throws -> [Session] { [] }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { fatalError() }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?, directory: String?) async throws -> Session { fatalError() }
    func archiveSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { fatalError() }
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] { fatalError() }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id: String) async throws -> Bool { fatalError() }
    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory: String?
    ) async throws -> Bool { fatalError() }
}
