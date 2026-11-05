import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionLiveStoreWindowTests {
    @Test func firstLoadAsksForOnePageOfRoots() async {
        let repository = WindowFakeSessionRepository(rootCount: 120)
        let store = makeStore(repository)
        let latest = observe(store)
        defer { latest.stop() }

        #expect(await windowWaitUntil { latest.value?.isFromCache == false })
        #expect(repository.limits == [HomeSessionPaging.pageSize])
        #expect(latest.value?.sessions.count == HomeSessionPaging.pageSize)
        #expect(latest.value?.hasMore == true)
    }

    @Test func loadMoreAsksForOneMorePageInOneRequest() async {
        let repository = WindowFakeSessionRepository(rootCount: 120)
        let store = makeStore(repository)
        let latest = observe(store)
        defer { latest.stop() }
        #expect(await windowWaitUntil { latest.value?.isFromCache == false })

        await store.loadMore(directory: "/repo")

        #expect(repository.limits == [50, 100])
        #expect(await windowWaitUntil { latest.value?.sessions.count == 100 })
        #expect(latest.value?.hasMore == true)
    }

    @Test func sessionEventsRefetchTheCurrentWindowSize() async {
        let repository = WindowFakeSessionRepository(rootCount: 120)
        let eventStream = FakeForegroundEventStream()
        let store = makeStore(repository, eventStream: eventStream)
        let latest = observe(store)
        defer { latest.stop() }
        #expect(await windowWaitUntil { latest.value?.isFromCache == false })
        await store.loadMore(directory: "/repo")

        eventStream.emit(OpencodeEvent(type: "session.updated", sessionID: "root-1"))

        #expect(await windowWaitUntil(timeoutMs: 3_000) { repository.limits.count == 3 })
        #expect(repository.limits == [50, 100, 100])
    }

    @Test func loadMoreIsANoOpWhenTheWindowHoldsEverything() async {
        let repository = WindowFakeSessionRepository(rootCount: 30)
        let store = makeStore(repository)
        let latest = observe(store)
        defer { latest.stop() }
        #expect(await windowWaitUntil { latest.value?.isFromCache == false })
        #expect(latest.value?.hasMore == false)

        await store.loadMore(directory: "/repo")

        #expect(repository.limits == [50])
    }

    @Test func loadMoreIsANoOpWhileOneRuns() async {
        let repository = WindowFakeSessionRepository(rootCount: 300)
        let store = makeStore(repository)
        let latest = observe(store)
        defer { latest.stop() }
        #expect(await windowWaitUntil { latest.value?.isFromCache == false })

        repository.holdNext()
        let first = Task { await store.loadMore(directory: "/repo") }
        #expect(await windowWaitUntil { repository.isHeld })
        await store.loadMore(directory: "/repo")
        repository.release()
        await first.value

        #expect(repository.limits == [50, 100])
        #expect(await windowWaitUntil { latest.value?.sessions.count == 100 })
    }

    @Test func loadedWindowsSeedTheAncestryUntilTheCacheClears() async {
        let repository = WindowFakeSessionRepository(rootCount: 3)
        let store = makeStore(repository)
        let latest = observe(store)
        defer { latest.stop() }
        #expect(await windowWaitUntil { latest.value?.isFromCache == false })

        #expect(store.ancestry.rootOf("root-1") == "root-1")
        store.clearCache()
        #expect(store.ancestry.rootOf("root-1") == nil)
    }

    private func makeStore(
        _ repository: WindowFakeSessionRepository,
        eventStream: FakeForegroundEventStream = FakeForegroundEventStream()
    ) -> DefaultSessionLiveStore {
        DefaultSessionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream,
            sessionCache: NoOpSessionCache.shared
        )
    }

    private func observe(_ store: DefaultSessionLiveStore) -> LatestWindow {
        let latest = LatestWindow()
        latest.task = Task { @MainActor in
            for await snapshot in store.observeSessions(directory: "/repo") {
                latest.value = snapshot
            }
        }
        return latest
    }
}

@MainActor
private final class LatestWindow {
    var value: SessionListSnapshot?
    var task: Task<Void, Never>?

    func stop() { task?.cancel() }
}

@MainActor
private func windowWaitUntil(timeoutMs: Int = 2_000, _ predicate: @MainActor () -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
    while Date() < deadline {
        if predicate() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return predicate()
}

/// `rootCount` roots, newest first; records the limit of every window request.
private final class WindowFakeSessionRepository: SessionRepository, @unchecked Sendable {
    private let lock = NSLock()
    private let roots: [Session]
    private var _limits: [Int] = []
    private var holdNextWindow = false
    private var held: CheckedContinuation<Void, Never>?

    init(rootCount: Int) {
        roots = (0 ..< rootCount).map { index in
            Session(
                id: "root-\(index)", title: "Root \(index)", slug: nil, projectID: nil,
                directory: "/repo", parentID: nil, updatedAtEpochMs: Int64(rootCount - index)
            )
        }
    }

    var limits: [Int] { lock.withLock { _limits } }
    var isHeld: Bool { lock.withLock { held != nil } }

    func holdNext() {
        lock.withLock { holdNextWindow = true }
    }

    func release() {
        let continuation = lock.withLock {
            let continuation = held
            held = nil
            return continuation
        }
        continuation?.resume()
    }

    func listRootSessionsWindow(directory _: String, limit: Int) async throws -> SessionWindow {
        let hold = lock.withLock {
            _limits.append(limit)
            let hold = holdNextWindow
            holdNextWindow = false
            return hold
        }
        if hold {
            await withCheckedContinuation { continuation in
                lock.withLock { held = continuation }
            }
        }
        return SessionWindow(sessions: Array(roots.prefix(limit)), hasMore: roots.count > limit)
    }

    func listSessions(directory _: String?) async throws -> [Session] { roots }
    func createSession(parentID _: String?, title _: String?, directory _: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory _: String?) async throws -> [SessionStatus] { [] }
    func getSession(id _: String, directory _: String?) async throws -> Session {
        throw SessionError.http(statusCode: 404, message: nil)
    }
    func updateSession(id _: String, title _: String?, directory _: String?) async throws -> Session { fatalError() }
    func archiveSession(id _: String, directory _: String?) async throws -> Session { fatalError() }
    func deleteSession(id _: String, directory _: String?) async throws -> Bool { fatalError() }
    func listChildSessions(id _: String) async throws -> [Session] { [] }
    func listSessionTodos(id _: String, directory _: String?) async throws -> [SessionTodo] { [] }
    func initializeSessionAgents(id _: String, messageID _: String, providerID _: String, modelID _: String) async throws -> Bool { fatalError() }
    func forkSession(id _: String, atMessageID _: String?) async throws -> Session { fatalError() }
    func abortSession(id _: String, directory _: String?) async throws -> Bool { fatalError() }
    func shareSession(id _: String) async throws -> Session { fatalError() }
    func unshareSession(id _: String) async throws -> Session { fatalError() }
    func getSessionDiff(id _: String, messageID _: String?, directory _: String?) async throws -> [FileDiff] { [] }
    func summarizeSession(id _: String, providerID _: String, modelID _: String) async throws -> Bool { fatalError() }
    func revertSession(id _: String, messageID _: String, partID _: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id _: String) async throws -> Bool { fatalError() }
    func respondToPermission(
        sessionID _: String,
        permissionID _: String,
        decision _: PermissionDecision,
        directory _: String?
    ) async throws -> Bool { fatalError() }
}
