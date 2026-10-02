import XCTest
@testable import OpenCode_Mobile

@MainActor
final class SessionReadyStoreTests: XCTestCase {
    func testNotifyPromptSent_idleAfterBusyWhileUnfocused_setsReady() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionReadyStore(sessionStatusLiveStore: statusStore, eventStream: eventStream)
        let ready = ReadyCollector(store: store, directory: "/repo")
        await ready.waitForSubscription()

        store.notifyPromptSent(sessionID: "ses-1", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "idle")])
        await ready.waitUntil { $0.contains("ses-1") }
        ready.cancel()

        XCTAssertTrue(ready.latest.contains("ses-1"))
    }

    func testIdleWhileFocused_doesNotSetReady() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionReadyStore(sessionStatusLiveStore: statusStore, eventStream: eventStream)
        let ready = ReadyCollector(store: store, directory: "/repo")
        await ready.waitForSubscription()

        store.setFocusedSession(sessionID: "ses-1", directory: "/repo")
        store.notifyPromptSent(sessionID: "ses-1", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "idle")])
        await settle()
        ready.cancel()

        XCTAssertTrue(ready.latest.isEmpty)
    }

    func testFocusedStatusEvent_thenLeave_thenIdleEvent_setsReadyWithoutBusySnapshot() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionReadyStore(sessionStatusLiveStore: statusStore, eventStream: eventStream)
        let ready = ReadyCollector(store: store, directory: "/repo")
        await ready.waitForSubscription()

        store.setFocusedSession(sessionID: "ses-1", directory: "/repo")
        await settle()
        eventStream.emit(OpencodeEvent(type: "session.status", sessionID: "ses-1"))
        await settle()
        store.setFocusedSession(sessionID: nil, directory: nil)
        await settle()
        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        await ready.waitUntil { $0.contains("ses-1") }
        ready.cancel()

        XCTAssertTrue(ready.latest.contains("ses-1"))
    }

    func testHomeOnlyBusy_neverWatched_staysQuiet() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionReadyStore(sessionStatusLiveStore: statusStore, eventStream: eventStream)
        let ready = ReadyCollector(store: store, directory: "/repo")
        await ready.waitForSubscription()

        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "idle")])
        await settle()
        ready.cancel()

        XCTAssertTrue(ready.latest.isEmpty)
    }

    func testStaleRelease_afterReturningToParent_keepsParentFocused() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionReadyStore(sessionStatusLiveStore: statusStore, eventStream: eventStream)
        let ready = ReadyCollector(store: store, directory: "/repo")
        await ready.waitForSubscription()

        store.setFocusedSession(sessionID: "parent", directory: "/repo")
        store.notifyPromptSent(sessionID: "parent", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["parent": status(type: "busy", sessionID: "parent")])
        await settle()
        // Open the child, then "Back to main session": the parent re-focuses before the
        // leaving child (and the earlier parent appearance) release their focus.
        store.setFocusedSession(sessionID: "child", directory: "/repo")
        store.releaseFocusedSession(sessionID: "parent")
        store.setFocusedSession(sessionID: "parent", directory: "/repo")
        store.releaseFocusedSession(sessionID: "child")
        statusStore.emit(directory: "/repo", statuses: ["parent": status(type: "idle", sessionID: "parent")])
        await settle()
        ready.cancel()

        XCTAssertTrue(ready.latest.isEmpty)
    }

    func testSwitchingFocusToAnotherSession_whileBusy_marksPriorReady() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionReadyStore(sessionStatusLiveStore: statusStore, eventStream: eventStream)
        let ready = ReadyCollector(store: store, directory: "/repo")
        await ready.waitForSubscription()

        store.setFocusedSession(sessionID: "parent", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["parent": status(type: "busy", sessionID: "parent")])
        await settle()
        store.setFocusedSession(sessionID: "child", directory: "/repo")
        store.releaseFocusedSession(sessionID: "parent")
        statusStore.emit(directory: "/repo", statuses: ["parent": status(type: "idle", sessionID: "parent")])
        await ready.waitUntil { $0.contains("parent") }
        ready.cancel()

        XCTAssertEqual(ready.latest, ["parent"])
    }

    func testSetFocusedSession_clearsReady() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionReadyStore(sessionStatusLiveStore: statusStore, eventStream: eventStream)
        let ready = ReadyCollector(store: store, directory: "/repo")
        await ready.waitForSubscription()

        store.notifyPromptSent(sessionID: "ses-1", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "idle")])
        await ready.waitUntil { $0.contains("ses-1") }

        store.setFocusedSession(sessionID: "ses-1", directory: "/repo")
        await ready.waitUntil { $0.isEmpty }
        ready.cancel()

        XCTAssertTrue(ready.latest.isEmpty)
    }

    func testOpenReady_thenStatusEvent_thenLeave_doesNotRestampReady() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionReadyStore(sessionStatusLiveStore: statusStore, eventStream: eventStream)
        let ready = ReadyCollector(store: store, directory: "/repo")
        await ready.waitForSubscription()

        store.notifyPromptSent(sessionID: "ses-1", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        // Idle sessions are often absent from the status map.
        statusStore.emit(directory: "/repo", statuses: [:])
        await ready.waitUntil { $0.contains("ses-1") }

        store.setFocusedSession(sessionID: "ses-1", directory: "/repo")
        await ready.waitUntil { $0.isEmpty }

        eventStream.emit(OpencodeEvent(type: "session.status", sessionID: "ses-1"))
        await settle()
        store.setFocusedSession(sessionID: nil, directory: nil)
        await settle()
        statusStore.emit(directory: "/repo", statuses: [:])
        await settle()
        ready.cancel()

        XCTAssertTrue(ready.latest.isEmpty)
    }

    func testBindProfile_switchAway_doesNotMarkReadyFromOtherServer() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionReadyStore(sessionStatusLiveStore: statusStore, eventStream: eventStream)
        let ready = ReadyCollector(store: store, directory: "/repo")
        await ready.waitForSubscription()

        store.bindProfile(profileID: "profile-a")
        store.notifyPromptSent(sessionID: "ses-1", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        store.bindProfile(profileID: "profile-b")
        await settle()
        statusStore.emit(directory: "/repo", statuses: [:])
        await settle()
        ready.cancel()

        XCTAssertTrue(ready.latest.isEmpty)
    }

    func testBindProfile_parksWatch_newStoreOnOriginalProfile_idleCatchUpSetsReady() async {
        let persistence = InMemorySessionReadyPersistence()
        let first = DefaultSessionReadyStore(
            sessionStatusLiveStore: FakeReadyStatusStore(),
            eventStream: FakeForegroundEventStream(),
            persistence: persistence
        )
        first.bindProfile(profileID: "profile-a")
        first.notifyPromptSent(sessionID: "ses-1", directory: "/repo")
        first.bindProfile(profileID: "profile-b")

        let statusStore = FakeReadyStatusStore()
        let second = DefaultSessionReadyStore(
            sessionStatusLiveStore: statusStore,
            eventStream: FakeForegroundEventStream(),
            persistence: persistence
        )
        let ready = ReadyCollector(store: second, directory: "/repo")
        await ready.waitForSubscription()
        second.bindProfile(profileID: "profile-a")
        statusStore.emit(directory: "/repo", statuses: [:])
        await ready.waitUntil { $0.contains("ses-1") }
        ready.cancel()

        XCTAssertTrue(ready.latest.contains("ses-1"))
    }

    func testBindProfile_switchAwayAndBack_idleCatchUpSetsReady() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionReadyStore(sessionStatusLiveStore: statusStore, eventStream: eventStream)
        let ready = ReadyCollector(store: store, directory: "/repo")
        await ready.waitForSubscription()

        store.bindProfile(profileID: "profile-a")
        store.notifyPromptSent(sessionID: "ses-1", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        store.bindProfile(profileID: "profile-b")
        await settle()
        statusStore.emit(directory: "/repo", statuses: [:])
        await settle()
        store.bindProfile(profileID: "profile-a")
        await settle()
        statusStore.emit(directory: "/repo", statuses: [:])
        await ready.waitUntil { $0.contains("ses-1") }
        ready.cancel()

        XCTAssertTrue(ready.latest.contains("ses-1"))
    }

    func testClearCache_emptiesReady() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionReadyStore(sessionStatusLiveStore: statusStore, eventStream: eventStream)
        let ready = ReadyCollector(store: store, directory: "/repo")
        await ready.waitForSubscription()

        store.notifyPromptSent(sessionID: "ses-1", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "idle")])
        await ready.waitUntil { $0.contains("ses-1") }
        store.clearCache()
        await ready.waitUntil { $0.isEmpty }
        ready.cancel()

        XCTAssertTrue(ready.latest.isEmpty)
    }

    func testReadySurvivesRestartViaPersistence() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let persistence = InMemorySessionReadyPersistence()
        let first = DefaultSessionReadyStore(
            sessionStatusLiveStore: statusStore,
            eventStream: eventStream,
            persistence: persistence
        )
        let ready = ReadyCollector(store: first, directory: "/repo")
        await ready.waitForSubscription()

        first.notifyPromptSent(sessionID: "ses-1", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "idle")])
        await ready.waitUntil { $0.contains("ses-1") }
        ready.cancel()

        let second = DefaultSessionReadyStore(
            sessionStatusLiveStore: FakeReadyStatusStore(),
            eventStream: FakeForegroundEventStream(),
            persistence: persistence
        )
        let reloaded = ReadyCollector(store: second, directory: "/repo")
        await reloaded.waitForSubscription()
        await reloaded.waitUntil { $0.contains("ses-1") }
        reloaded.cancel()

        XCTAssertTrue(reloaded.latest.contains("ses-1"))
    }

    func testWatchedSurvivesRestart_thenIdleCatchUp_setsReady() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let persistence = InMemorySessionReadyPersistence()
        let first = DefaultSessionReadyStore(
            sessionStatusLiveStore: statusStore,
            eventStream: eventStream,
            persistence: persistence
        )
        let ready = ReadyCollector(store: first, directory: "/repo")
        await ready.waitForSubscription()

        first.notifyPromptSent(sessionID: "ses-1", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        ready.cancel()

        let statusStore2 = FakeReadyStatusStore()
        let second = DefaultSessionReadyStore(
            sessionStatusLiveStore: statusStore2,
            eventStream: FakeForegroundEventStream(),
            persistence: persistence
        )
        let reloaded = ReadyCollector(store: second, directory: "/repo")
        await reloaded.waitForSubscription()
        statusStore2.emit(directory: "/repo", statuses: [:])
        await reloaded.waitUntil { $0.contains("ses-1") }
        reloaded.cancel()

        XCTAssertTrue(reloaded.latest.contains("ses-1"))
    }

    func testReadySurvivesLaterBusyUntilFocus() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionReadyStore(sessionStatusLiveStore: statusStore, eventStream: eventStream)
        let ready = ReadyCollector(store: store, directory: "/repo")
        await ready.waitForSubscription()

        store.notifyPromptSent(sessionID: "ses-1", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "idle")])
        await ready.waitUntil { $0.contains("ses-1") }
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        ready.cancel()

        XCTAssertTrue(ready.latest.contains("ses-1"))
    }

    func testReadyExpiresAfterTTL() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let persistence = InMemorySessionReadyPersistence()
        let clock = MutableSessionReadyClock(nowEpochMsValue: 1_000_000)
        let first = DefaultSessionReadyStore(
            sessionStatusLiveStore: statusStore,
            eventStream: eventStream,
            persistence: persistence,
            clock: clock
        )
        let ready = ReadyCollector(store: first, directory: "/repo")
        await ready.waitForSubscription()

        first.notifyPromptSent(sessionID: "ses-1", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "idle")])
        await ready.waitUntil { $0.contains("ses-1") }
        ready.cancel()

        clock.nowEpochMsValue += DefaultSessionReadyStore.readyTTLMs
        let second = DefaultSessionReadyStore(
            sessionStatusLiveStore: FakeReadyStatusStore(),
            eventStream: FakeForegroundEventStream(),
            persistence: persistence,
            clock: clock
        )
        let reloaded = ReadyCollector(store: second, directory: "/repo")
        await reloaded.waitForSubscription()
        await settle()
        reloaded.cancel()

        XCTAssertTrue(reloaded.latest.isEmpty)
    }

    func testProfileSwitch_statusStoreTearsDownStreams_readyStoreResubscribes() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionReadyStore(sessionStatusLiveStore: statusStore, eventStream: eventStream)
        let ready = ReadyCollector(store: store, directory: "/repo")
        await ready.waitForSubscription()
        store.bindProfile(profileID: "profile-a")
        await settle()

        store.bindProfile(profileID: "profile-b")
        statusStore.clearCache()
        await settle()

        store.notifyPromptSent(sessionID: "ses-1", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        statusStore.emit(directory: "/repo", statuses: [:])
        await ready.waitUntil { $0.contains("ses-1") }
        ready.cancel()

        XCTAssertTrue(ready.latest.contains("ses-1"))
        XCTAssertEqual(statusStore.liveSubscriberCount(directory: "/repo"), 1)
    }

    func testClearCache_statusStoreTearsDownStreams_readyStoreResubscribes() async {
        let statusStore = FakeReadyStatusStore()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionReadyStore(sessionStatusLiveStore: statusStore, eventStream: eventStream)
        let ready = ReadyCollector(store: store, directory: "/repo")
        await ready.waitForSubscription()

        statusStore.clearCache()
        store.clearCache()
        await settle()

        store.notifyPromptSent(sessionID: "ses-1", directory: "/repo")
        statusStore.emit(directory: "/repo", statuses: ["ses-1": status(type: "busy")])
        await settle()
        statusStore.emit(directory: "/repo", statuses: [:])
        await ready.waitUntil { $0.contains("ses-1") }
        ready.cancel()

        XCTAssertTrue(ready.latest.contains("ses-1"))
        XCTAssertEqual(statusStore.liveSubscriberCount(directory: "/repo"), 1)
    }

    private func status(type: String, sessionID: String = "ses-1") -> SessionStatus {
        SessionStatus(sessionID: sessionID, raw: ["type": type])
    }

    private func settle() async {
        try? await Task.sleep(for: .milliseconds(20))
    }
}

@MainActor
private final class ReadyCollector {
    private(set) var latest: Set<String> = []
    private var subscribed = false
    private var task: Task<Void, Never>?

    init(store: DefaultSessionReadyStore, directory: String) {
        task = Task { [weak self] in
            guard let self else { return }
            for await ids in store.observeReadyIds(directory: directory) {
                self.latest = ids
                self.subscribed = true
            }
        }
    }

    func waitForSubscription() async {
        for _ in 0..<50 {
            if subscribed { return }
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    func waitUntil(_ predicate: (Set<String>) -> Bool) async {
        for _ in 0..<50 {
            if predicate(latest) { return }
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    func cancel() {
        task?.cancel()
    }
}

@MainActor
private final class FakeReadyStatusStore: SessionStatusLiveStore {
    private var continuations: [String: [UUID: AsyncStream<SessionActivitySnapshot>.Continuation]] = [:]
    private var latest: [String: SessionActivitySnapshot] = [:]

    func emit(directory: String, statuses: [String: SessionStatus], seeded: Bool = true) {
        var entries: [String: SessionActivityEntry] = [:]
        for (id, status) in statuses {
            let activity = SessionActivity(raw: status.raw)
            guard activity != .idle else { continue }
            entries[id] = SessionActivityEntry(activity: activity, source: .server, observedAtMs: 0)
        }
        emit(directory: directory, snapshot: SessionActivitySnapshot(entries: entries, seeded: seeded))
    }

    func emit(directory: String, snapshot: SessionActivitySnapshot) {
        latest[directory] = snapshot
        continuations[directory]?.values.forEach { $0.yield(snapshot) }
    }

    func liveSubscriberCount(directory: String) -> Int {
        continuations[directory]?.count ?? 0
    }

    func observeActivity(directory: String) -> AsyncStream<SessionActivitySnapshot> {
        AsyncStream { continuation in
            let id = UUID()
            var bucket = continuations[directory] ?? [:]
            bucket[id] = continuation
            continuations[directory] = bucket
            continuation.yield(latest[directory] ?? SessionActivitySnapshot.empty)
            continuation.onTermination = { @Sendable _ in
                Task { @MainActor in
                    self.continuations[directory]?.removeValue(forKey: id)
                }
            }
        }
    }

    func clearCache() {
        let live = continuations
        continuations.removeAll()
        latest.removeAll()
        live.values.forEach { bucket in bucket.values.forEach { $0.finish() } }
    }
}
