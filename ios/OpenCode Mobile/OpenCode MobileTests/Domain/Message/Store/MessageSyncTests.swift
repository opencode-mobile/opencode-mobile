import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct MessageSyncTests {
    private let decoder = JSONDecoder()

    @Test func warmReopen_unchangedIdentity_confirmsCacheSyncWithoutSeed() async {
        // Warm reopen: the in-memory store still holds the previous session's
        // rows, but no disk seed runs this loop (NoOpSessionCache) and REST
        // returns an identical frame identity. The Syncing indicator must clear
        // via cacheSyncConfirmed even though the second loop did not seed.
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let store = InMemoryMessageStore()
        store.replaceAll(sessionID: "ses-1", messages: [
            Message(
                id: "msg-1",
                role: .user,
                text: "Hello",
                parts: [.text(TextPart(text: "Hello", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ])
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: store),
            eventStream: eventStream,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) }
        )

        // First observation publishes and records the frame identity.
        let first = MessageCollector()
        let firstTask = Task { await first.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        _ = await waitUntil { first.latest()?.first?.text == "Hello" }
        firstTask.cancel()
        try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.subscriberStopTimeoutMs + 50))

        // Warm reopen: same store rows, REST unchanged, no disk seed. The
        // unchanged identity must still yield cacheSyncConfirmed.
        let second = CacheConfirmAwareCollector()
        let secondTask = Task { await second.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let confirmed = await waitUntil { second.sawCacheSyncConfirmed }
        secondTask.cancel()
        #expect(confirmed)
        #expect(second.latest()?.first?.text == "Hello")
    }

    @Test func initialLoad_emitsMessagesFromRepository() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }

        let loaded = await waitUntil {
            collector.latest()?.first?.text == "Hello"
        }
        task.cancel()
        #expect(loaded)
        #expect(collector.emissions.count >= 1)
    }

    @Test func initialLoad_messageLoopDoesNotRunOnMainThread() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let callerOnMain = MainThreadFlag()
        // Build MessageSync with an explicit listMessages probe so we record the
        // caller thread before any MainActor hop into the fake repository.
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                callerOnMain.note(Thread.isMainThread)
                return try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            pollIntervalMs: LiveStoreRefreshPolicy.workingPollIntervalMs,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) }
        )
        let collector = MessageCollector()
        // Start observation from MainActor (production DisplayMessagePipeline path).
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil {
            collector.latest()?.first?.text == "Hello"
        }
        task.cancel()
        #expect(loaded)
        #expect(callerOnMain.sawMain == false)
        #expect(callerOnMain.calls > 0)
    }

    @Test func initialLoad_buffersEventsThatArriveDuringRestRequest() async {
        for iteration in 0..<20 {
            let sessionID = "ses-buffer-\(iteration)"
            let repo = FakeLiveMessageRepository()
            repo.holdNextListMessages()
            let eventStream = FakeForegroundEventStream()
            let sync = makeMessageSync(repo: repo, eventStream: eventStream)
            let collector = MessageCollector()
            let task = Task {
                await collector.collect(sync.observeMessages(sessionID: sessionID, directory: "/repo"))
            }
            let ready = await waitUntil {
                repo.isListBlocked && eventStream.hasEventSubscriber
            }
            #expect(ready, "iteration \(iteration) never reached held REST + event subscriber")

            eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: sessionID))
            eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: sessionID))
            eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Hello", sessionID: sessionID))
            #expect(repo.isListBlocked, "iteration \(iteration) emitted after REST already resumed")
            repo.resumeListMessages()

            let streamed = await waitUntil {
                collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Hello"
            }
            task.cancel()
            #expect(streamed, "iteration \(iteration) missed event that arrived during held REST")
        }
    }

    @Test func initialLoad_overflowDuringRestTriggersAuthoritativeRecovery() async {
        let repo = FakeLiveMessageRepository()
        repo.messages = [
            Message(
                id: "msg-rest",
                role: .assistant,
                text: "from-rest",
                parts: [.text(TextPart(text: "from-rest", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        repo.holdNextListMessages()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, eventStagingCapacity: 2)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let blocked = await waitUntil { repo.isListBlocked && eventStream.hasEventSubscriber }
        #expect(blocked)

        for index in 0..<4 {
            eventStream.emit(eventWithInfo(id: "msg-burst-\(index)", role: "assistant", sessionID: "ses-1"))
        }
        repo.messages = [
            Message(
                id: "msg-recovered",
                role: .assistant,
                text: "recovered",
                parts: [.text(TextPart(text: "recovered", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        repo.resumeListMessages()

        let recovered = await waitUntil {
            collector.latest()?.count == 1 && collector.latest()?.first?.id == "msg-recovered"
        }
        task.cancel()
        #expect(recovered)
        #expect(collector.emissions.contains { frame in frame.contains { $0.id == "msg-rest" } } == false)
    }

    @Test func overflowRecoveryFailure_keepsRecoveringAndLastFrame() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 60_000)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)
        let listening = await waitUntil { eventStream.hasCoordinatorSubscribers }
        #expect(listening)

        repo.shouldFailList = true
        eventStream.setConnectionState(.connected)
        eventStream.openGap()
        eventStream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: "ses-1", rawProperties: nil))
        let kept = await waitUntil {
            if case .recovering = sync.syncHealth(sessionID: "ses-1", directory: "/repo") {
                return true
            }
            return false
        }
        #expect(kept)
        #expect(collector.latest()?.first?.text == "Hello")

        // Let recovery converge so the observation task can exit cleanly.
        repo.shouldFailList = false
        _ = await waitUntil {
            sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy
        }
        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func sessionKeys_isolateProfiles() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let syncA = makeMessageSync(repo: repo, eventStream: eventStream, connectionProfileID: "profile-a")
        let syncB = makeMessageSync(repo: repo, eventStream: eventStream, connectionProfileID: "profile-b")

        let collectorA = MessageCollector()
        let taskA = Task { await collectorA.collect(syncA.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loadedA = await waitUntil { collectorA.latest()?.first?.text == "Hello" }
        #expect(loadedA)

        repo.messages = [
            Message(
                id: "msg-b",
                role: .assistant,
                text: "B",
                parts: [.text(TextPart(text: "B", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        let collectorB = MessageCollector()
        let taskB = Task { await collectorB.collect(syncB.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loadedB = await waitUntil { collectorB.latest()?.first?.text == "B" }
        taskA.cancel()
        taskB.cancel()
        #expect(loadedB)
        #expect(collectorA.latest()?.first?.text == "Hello")
    }

    @Test func router_ignoresNonMessageEvents() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 60_000)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)
        let before = collector.emissions.count

        eventStream.emit(OpencodeEvent(type: "permission.asked", sessionID: "ses-1", rawProperties: nil))
        eventStream.emit(OpencodeEvent(type: "session.created", sessionID: "ses-1", rawProperties: nil))
        try? await Task.sleep(for: .milliseconds(100))
        task.cancel()
        #expect(collector.emissions.count == before)
    }

    @Test func transportDrop_entersStaleWhileRetainingFrame() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 60_000)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)

        eventStream.start()
        eventStream.setConnectionState(.disconnected)
        let stale = await waitUntil {
            if case .stale(.transportUnavailable) = sync.syncHealth(sessionID: "ses-1", directory: "/repo") {
                return true
            }
            return false
        }
        var chrome = false
        let chromeTask = Task {
            for await value in sync.observeCatchUpChrome(sessionID: "ses-1", directory: "/repo") {
                chrome = value
            }
        }
        let catchingUp = await waitUntil { chrome }
        chromeTask.cancel()
        task.cancel()
        #expect(stale)
        #expect(catchingUp)
        #expect(collector.latest()?.first?.text == "Hello")
    }

    @Test func refreshMessages_whileHealthy_doesNotShowCatchUpChrome() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 60_000)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)
        #expect(sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy)

        var chrome: [Bool] = []
        let chromeTask = Task {
            for await value in sync.observeCatchUpChrome(sessionID: "ses-1", directory: "/repo") {
                chrome.append(value)
            }
        }
        let quiet = await waitUntil { chrome.last == false }
        #expect(quiet)

        repo.holdNextListMessages()
        let refresh = Task { try await sync.refreshMessages(sessionID: "ses-1", directory: "/repo") }
        let blocked = await waitUntil { repo.isListBlocked }
        #expect(blocked)
        #expect(chrome.contains(true) == false)
        #expect(sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy)

        repo.resumeListMessages()
        _ = try? await refresh.value
        _ = await waitUntil {
            sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy && !repo.isListBlocked
        }
        try? await Task.sleep(for: .milliseconds(150))
        chromeTask.cancel()
        task.cancel()
        #expect(chrome.contains(true) == false)
        #expect(sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy)
    }

    @Test func refreshMessages_overlappingRefreshes_doNotShowCatchUpChrome() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 60_000)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)
        #expect(sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy)

        var chrome: [Bool] = []
        let chromeTask = Task {
            for await value in sync.observeCatchUpChrome(sessionID: "ses-1", directory: "/repo") {
                chrome.append(value)
            }
        }
        let quiet = await waitUntil { chrome.last == false }
        #expect(quiet)

        repo.holdNextListMessages()
        let first = Task { try await sync.refreshMessages(sessionID: "ses-1", directory: "/repo") }
        let second = Task { try await sync.refreshMessages(sessionID: "ses-1", directory: "/repo") }
        let blocked = await waitUntil { repo.isListBlocked }
        #expect(blocked)
        #expect(chrome.contains(true) == false)
        #expect(sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy)

        repo.resumeListMessages()
        _ = try? await first.value
        _ = try? await second.value
        _ = await waitUntil {
            sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy && !repo.isListBlocked
        }
        try? await Task.sleep(for: .milliseconds(150))
        chromeTask.cancel()
        task.cancel()
        #expect(chrome.contains(true) == false)
        #expect(sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy)
    }

    @Test func hungInitialRest_cacheSeed_confirmsSyncWhileSseConnected() async {
        let repo = FakeLiveMessageRepository()
        repo.holdNextListMessages()
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let cached = repo.messages
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            pollIntervalMs: 20,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            sessionCache: SeedingSessionCache(messages: cached)
        )
        let collector = CacheConfirmAwareCollector()
        let task = Task {
            await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo"))
        }
        let painted = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(painted)
        #expect(repo.isListBlocked)
        repo.resumeListMessages()
        let confirmed = await waitUntil { collector.sawCacheSyncConfirmed }
        task.cancel()
        #expect(confirmed)
    }

    @Test func hungInitialRest_cacheSeed_doesNotConfirmWhenOccupancyStopped() async {
        let repo = FakeLiveMessageRepository()
        repo.holdNextListMessages()
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.disconnected)
        let world = FakeWorldSignals()
        world.setOccupancy(.stopped)
        let cached = repo.messages
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            worldSignals: world,
            pollIntervalMs: 20,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            sessionCache: SeedingSessionCache(messages: cached)
        )
        let collector = CacheConfirmAwareCollector()
        let task = Task {
            await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo"))
        }
        let painted = await waitUntil { collector.latest()?.first?.text == "Hello" }
        try? await Task.sleep(for: .milliseconds(80))
        task.cancel()
        #expect(painted)
        #expect(!collector.sawCacheSyncConfirmed)
        #expect(!repo.isListBlocked)
        repo.resumeListMessages()
    }

    @Test func identicalTailConfirmsCacheWithoutRepublish() async {
        let repo = FakeLiveMessageRepository()
        repo.holdNextListMessages()
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let cached = repo.messages
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            sessionCache: SeedingSessionCache(messages: cached)
        )
        let collector = CacheConfirmAwareCollector()
        let task = Task {
            await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo"))
        }
        let painted = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(painted)
        #expect(collector.emissions.count == 1)
        repo.resumeListMessages()
        let confirmed = await waitUntil { collector.sawCacheSyncConfirmed }
        try? await Task.sleep(for: .milliseconds(40))
        task.cancel()
        #expect(confirmed)
        #expect(collector.emissions.count == 1)
    }

    @Test func identicalTail_resubscribeWithinTimeout_replaysMessagesNotOnlyCacheSyncConfirmed() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let cached = repo.messages
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            sessionCache: SeedingSessionCache(messages: cached)
        )
        let first = CacheConfirmAwareCollector()
        let firstTask = Task {
            await first.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo"))
        }
        _ = await waitUntil { first.sawCacheSyncConfirmed }
        firstTask.cancel()
        try? await Task.sleep(for: .milliseconds(200))

        let second = CacheConfirmAwareCollector()
        let secondTask = Task {
            await second.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo"))
        }
        let painted = await waitUntil { second.latest()?.first?.text == "Hello" }
        secondTask.cancel()
        #expect(painted)
    }

    @Test func differingTailMergesAndPublishesOnce() async {
        let repo = FakeLiveMessageRepository()
        repo.holdNextListMessages()
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let cached = [
            Message(
                id: "msg-1",
                role: .user,
                text: "Hello",
                parts: [.text(TextPart(text: "Hello", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 1
            ),
        ]
        repo.messages = cached + [
            Message(
                id: "msg-2",
                role: .assistant,
                text: "Later",
                parts: [.text(TextPart(text: "Later", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 2
            ),
        ]
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            sessionCache: SeedingSessionCache(messages: cached)
        )
        let collector = CacheConfirmAwareCollector()
        let task = Task {
            await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo"))
        }
        let painted = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(painted)
        #expect(collector.emissions.count == 1)
        repo.resumeListMessages()
        let merged = await waitUntil {
            collector.latest()?.contains(where: { $0.id == "msg-2" }) == true
        }
        try? await Task.sleep(for: .milliseconds(40))
        task.cancel()
        #expect(merged)
        #expect(collector.emissions.count == 2)
    }

    @Test func streamingCachedTail_completedRestTail_publishesRestContent() async {
        let user = Message(
            id: "m1",
            role: .user,
            text: "m1",
            parts: [.text(TextPart(text: "m1", isStreaming: false))],
            model: nil,
            mode: nil,
            sentAtEpochMs: 1
        )
        let cached = [
            user,
            Message(
                id: "a1",
                role: .assistant,
                text: "partial",
                parts: [.text(TextPart(text: "partial", isStreaming: true))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 1000,
                completedAtEpochMs: nil
            ),
        ]
        let repo = FakeLiveMessageRepository()
        repo.holdNextListMessages()
        repo.messages = [
            user,
            Message(
                id: "a1",
                role: .assistant,
                text: "partial and done",
                parts: [.text(TextPart(text: "partial and done", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 1000,
                completedAtEpochMs: 2000
            ),
        ]
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            sessionCache: SeedingSessionCache(messages: cached)
        )
        let collector = CacheConfirmAwareCollector()
        let task = Task {
            await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo"))
        }
        let painted = await waitUntil { collector.latest()?.last?.text == "partial" }
        #expect(painted)
        repo.resumeListMessages()
        let published = await waitUntil { collector.latest()?.last?.text == "partial and done" }
        task.cancel()
        #expect(published)
        #expect(collector.latest()?.last?.text == "partial and done")
    }

    @Test func completedIdenticalAssistantTail_confirmsCacheSync() async {
        let messages = [
            Message(
                id: "m1",
                role: .user,
                text: "m1",
                parts: [.text(TextPart(text: "m1", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 1
            ),
            Message(
                id: "a1",
                role: .assistant,
                text: "done",
                parts: [.text(TextPart(text: "done", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 1000,
                completedAtEpochMs: 2000
            ),
        ]
        let repo = FakeLiveMessageRepository()
        repo.holdNextListMessages()
        repo.messages = messages
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            sessionCache: SeedingSessionCache(messages: messages)
        )
        let collector = CacheConfirmAwareCollector()
        let task = Task {
            await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo"))
        }
        let painted = await waitUntil { collector.latest()?.last?.text == "done" }
        #expect(painted)
        repo.resumeListMessages()
        let confirmed = await waitUntil { collector.sawCacheSyncConfirmed }
        task.cancel()
        #expect(confirmed)
        #expect(collector.latest()?.last?.text == "done")
    }

    @Test func restTailStillStreaming_doesNotConfirm() async {
        let user = Message(
            id: "m1",
            role: .user,
            text: "m1",
            parts: [.text(TextPart(text: "m1", isStreaming: false))],
            model: nil,
            mode: nil,
            sentAtEpochMs: 1
        )
        let cached = [
            user,
            Message(
                id: "a1",
                role: .assistant,
                text: "partial",
                parts: [.text(TextPart(text: "partial", isStreaming: true))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 1000,
                completedAtEpochMs: nil
            ),
        ]
        let repo = FakeLiveMessageRepository()
        repo.holdNextListMessages()
        repo.messages = [
            user,
            Message(
                id: "a1",
                role: .assistant,
                text: "partial more",
                parts: [.text(TextPart(text: "partial more", isStreaming: true))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 1000,
                completedAtEpochMs: nil
            ),
        ]
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            sessionCache: SeedingSessionCache(messages: cached)
        )
        let collector = CacheConfirmAwareCollector()
        let task = Task {
            await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo"))
        }
        let painted = await waitUntil { collector.latest()?.last?.text == "partial" }
        #expect(painted)
        repo.resumeListMessages()
        let published = await waitUntil { collector.latest()?.last?.text == "partial more" }
        task.cancel()
        #expect(published)
        #expect(collector.latest()?.last?.text == "partial more")
    }

    @Test func hiddenReasoningSnapshotUpdates_doNotRestOrShowCatchUp() async {
            let repo = FakeLiveMessageRepository()
            repo.messages = hiddenReasoningTranscript()
            let eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(.connected)
            repo.holdNextListMessages()
            let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 60_000)
            let collector = MessageCollector()
            let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
            var chrome: [Bool] = []
            let chromeTask = Task {
                for await flag in sync.observeCatchUpChrome(sessionID: "ses-1", directory: "/repo") {
                    chrome.append(flag)
                }
            }
            let held = await waitUntil { repo.isListBlocked && repo.listHoldsEntered == 1 }
            #expect(held)
            repo.resumeListMessages()
            let loaded = await waitUntil { collector.latest()?.contains(where: { $0.id == "msg-2" }) == true }
            #expect(loaded)
            let afterInitial = repo.listMessagesCallCount
            #expect(afterInitial == 1)
            #expect(sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy)
            #expect(chrome.last == false)

            for _ in 1...3 {
                eventStream.republishSameGeneration()
            }
            eventStream.emit(eventWithReasoningPart(partID: "prt-r", messageID: "msg-2", text: "", sessionID: "ses-1"))
            eventStream.emit(
                eventWithPartDelta(
                    partID: "prt-r",
                    messageID: "msg-2",
                    delta: "Thinking",
                    field: "reasoning",
                    sessionID: "ses-1"
                )
            )
            eventStream.emit(eventWithTextPart(partID: "prt-t", messageID: "msg-2", text: "", sessionID: "ses-1"))
            eventStream.emit(
                eventWithPartDelta(partID: "prt-t", messageID: "msg-2", delta: "Answer", sessionID: "ses-1")
            )
            let streamed = await waitUntil {
                guard let assistant = collector.latest()?.first(where: { $0.id == "msg-2" }) else { return false }
                guard case .reasoning(let reasoning) = assistant.parts.first else { return false }
                guard assistant.parts.count > 1, case .text(let text) = assistant.parts[1] else { return false }
                return reasoning.text == "Thinking" && text.text == "Answer"
            }
            task.cancel()
            chromeTask.cancel()
            #expect(streamed)
            #expect(repo.listMessagesCallCount == afterInitial)
            #expect(chrome.last == false)
            #expect(sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy)
    }

    @Test func hiddenReasoningTransportGap_reconcilesOnce() async {
            let repo = FakeLiveMessageRepository()
            repo.messages = hiddenReasoningTranscript()
            let eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(.connected)
            repo.holdNextListMessages()
            let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 60_000)
            var chrome: [Bool] = []
            let task = Task { for await _ in sync.observeMessages(sessionID: "ses-1", directory: "/repo") {} }
            let chromeTask = Task {
                for await flag in sync.observeCatchUpChrome(sessionID: "ses-1", directory: "/repo") {
                    chrome.append(flag)
                }
            }
            let held = await waitUntil { repo.isListBlocked && repo.listHoldsEntered == 1 }
            #expect(held)
            repo.resumeListMessages()
            let loaded = await waitUntil { repo.listMessagesCallCount >= 1 && !repo.isListBlocked }
            #expect(loaded)
            let afterInitial = repo.listMessagesCallCount
            #expect(afterInitial == 1)

            repo.messages = [
                Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: 1),
                Message(
                    id: "msg-2",
                    role: .assistant,
                    text: "Done",
                    parts: [.text(TextPart(text: "Done", isStreaming: false))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: 2
                ),
            ]
            let listening = await waitUntil { eventStream.hasCoordinatorSubscribers }
            #expect(listening)
            repo.holdNextListMessages()
            eventStream.openGap()
            let recovering = await waitUntil { repo.isListBlocked && repo.listHoldsEntered == 2 }
            #expect(recovering)
            repo.resumeListMessages()
            let healthy = await waitUntil {
                if case .healthy = sync.syncHealth(sessionID: "ses-1", directory: "/repo") {
                    return repo.listMessagesCallCount == afterInitial + 1
                }
                return false
            }
            task.cancel()
            chromeTask.cancel()
            #expect(healthy)
            #expect(repo.listMessagesCallCount == afterInitial + 1)
            #expect(chrome.last == false)
    }

    @Test func idleDuringInitialSubscribe_paysThenOneIdleRest() async {
            let repo = FakeLiveMessageRepository()
            repo.messages = hiddenReasoningTranscript()
            let eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(.connected)
            repo.holdNextListMessages()
            let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 60_000)
            let collector = MessageCollector()
            var chrome: [Bool] = []
            let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
            let chromeTask = Task {
                for await flag in sync.observeCatchUpChrome(sessionID: "ses-1", directory: "/repo") {
                    chrome.append(flag)
                }
            }
            let subscribed = await waitUntil {
                repo.isListBlocked && eventStream.hasEventSubscriber && repo.listHoldsEntered == 1
            }
            #expect(subscribed)

            for _ in 1...5 {
                eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
            }
            repo.holdNextListMessages()
            repo.resumeListMessages()
            let idleHeld = await waitUntil { repo.isListBlocked && repo.listHoldsEntered == 2 }
            #expect(idleHeld)

            repo.messages = completedAssistantTranscript()
            repo.resumeListMessages()
            let healthy = await waitUntil {
                if case .healthy = sync.syncHealth(sessionID: "ses-1", directory: "/repo") {
                    return !repo.isListBlocked
                }
                return false
            }
            task.cancel()
            chromeTask.cancel()
            #expect(healthy)
            #expect(sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy)
            #expect(chrome.last == false)
    }

    @Test func idleDuringInitialSubscribe_visibleThoughtStillRefetchesCompletedTail() async {
        let repo = FakeLiveMessageRepository()
        repo.messages = visibleThoughtTranscript()
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        repo.holdNextListMessages()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 60_000)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let subscribed = await waitUntil {
            repo.isListBlocked && eventStream.hasEventSubscriber && repo.listHoldsEntered == 1
        }
        #expect(subscribed)

        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        repo.holdNextListMessages()
        repo.resumeListMessages()
        let idleHeld = await waitUntil { repo.isListBlocked && repo.listHoldsEntered == 2 }
        #expect(idleHeld)

        repo.messages = completedTurnWithExtraAssistant()
        repo.resumeListMessages()
        let painted = await waitUntil {
            collector.latest()?.contains(where: { $0.id == "msg-3" }) == true
        }
        task.cancel()
        #expect(painted)
        #expect(repo.listMessagesCallCount >= 2)
    }

    @Test func repeatedIncompleteIdle_reconcilesOnce() async {
        let repo = FakeLiveMessageRepository()
        repo.messages = hiddenReasoningTranscript()
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        repo.holdNextListMessages()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 60_000)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let held = await waitUntil { repo.isListBlocked && repo.listHoldsEntered == 1 }
        #expect(held)
        repo.resumeListMessages()
        let loaded = await waitUntil {
            collector.latest()?.contains(where: { $0.id == "msg-2" }) == true
                && sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy
        }
        #expect(loaded)
        let afterInitial = repo.listMessagesCallCount
        #expect(afterInitial == 1)

        repo.messages = [
            Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(
                id: "msg-2",
                role: .assistant,
                text: "Done",
                parts: [.text(TextPart(text: "Done", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 2
            ),
        ]
        repo.holdNextListMessages()
        for _ in 1...5 {
            eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        }
        let idleHeld = await waitUntil { repo.isListBlocked && repo.listHoldsEntered == 2 }
        #expect(idleHeld)
        repo.resumeListMessages()
        let healthy = await waitUntil {
            if case .healthy = sync.syncHealth(sessionID: "ses-1", directory: "/repo") {
                return !repo.isListBlocked
            }
            return false
        }
        task.cancel()
        #expect(healthy)
    }

    @Test func unscopedCatchUpChrome_clearsRecoveredSessionAndStaysBounded() async {
        let repo = FakeLiveMessageRepository()
        repo.messages = hiddenReasoningTranscript()
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        repo.holdNextListMessages()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 60_000)
        var globalChrome: [Bool] = []
        let chromeTask = Task {
            for await flag in sync.observeCatchUpChrome() {
                globalChrome.append(flag)
            }
        }
        let task = Task { for await _ in sync.observeMessages(sessionID: "ses-1", directory: "/repo") {} }
        let held = await waitUntil { repo.isListBlocked && repo.listHoldsEntered == 1 }
        #expect(held)
        repo.resumeListMessages()
        let loaded = await waitUntil { repo.listMessagesCallCount >= 1 && !repo.isListBlocked }
        #expect(loaded)
        let chromeCleared = await waitUntil { globalChrome.last == false }
        #expect(chromeCleared)

        let listening = await waitUntil { eventStream.hasCoordinatorSubscribers }
        #expect(listening)
        repo.holdNextListMessages()
        eventStream.openGap()
        let recovering = await waitUntil { repo.isListBlocked && repo.listHoldsEntered == 2 }
        #expect(recovering)
        #expect(globalChrome.contains(true))
        repo.resumeListMessages()
        let cleared = await waitUntil { globalChrome.last == false && !repo.isListBlocked }
        #expect(cleared)

        for _ in 1...3 {
            eventStream.republishSameGeneration()
        }
        let stillClear = await waitUntil {
            globalChrome.last == false && repo.listMessagesCallCount == 2
        }
        #expect(stillClear)
        #expect(repo.listMessagesCallCount == 2)

        task.cancel()
        let bounded = await waitUntil { globalChrome.last == false }
        chromeTask.cancel()
        #expect(bounded)
    }

    @Test func messageUpdated_suppressesEmptyNewSlot() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))

        let event = eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1")
        eventStream.emit(event)
        try? await Task.sleep(for: .milliseconds(500))
        task.cancel()

        let last = collector.latest()
        #expect(last?.count == 1)
        #expect(last?.contains(where: { $0.id == "msg-2" }) == false)
    }

    @Test func v2PromptedPublishesUserText() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        let events = EventDomainAdapter().parseEvents(
            dataLine: #"{"type":"session.next.prompted","data":{"sessionID":"ses-1","messageID":"msg-2","prompt":{"text":"Hello from CLI"},"delivery":"steer"}}"#
        )
        for event in events {
            eventStream.emit(event)
        }
        let published = await waitUntil {
            collector.latest()?.contains(where: { $0.id == "msg-2" && $0.text.contains("Hello from CLI") }) == true
        }
        task.cancel()
        #expect(published)
    }

    @Test func v2InboxEnqueuedPublishesUserText() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        let events = EventDomainAdapter().parseEvents(
            dataLine: #"{"type":"session.inbox.enqueued","data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"type":"user","payload":{"text":"Hello from inbox","files":[]},"delivery":"steer"}}}"#
        )
        for event in events {
            eventStream.emit(event)
        }
        let published = await waitUntil {
            collector.latest()?.contains(where: { $0.id == "msg-2" && $0.text.contains("Hello from inbox") }) == true
        }
        task.cancel()
        #expect(published)
    }

    @Test func disconnectWithoutReconnectDoesNotFetch() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)
        let afterInitial = repo.listMessagesCallCount

        eventStream.setConnectionState(.disconnected)
        try? await Task.sleep(for: .milliseconds(90))

        repo.messages = [
            Message(
                id: "msg-1",
                role: .user,
                text: "From poll after reconnect",
                parts: [.text(TextPart(text: "From poll after reconnect", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        try? await Task.sleep(for: .milliseconds(80))
        task.cancel()
        #expect(collector.latest()?.first?.text == "Hello")
        #expect(repo.listMessagesCallCount == afterInitial)
    }

    @Test func v2PromptedGlobalBusPublishesUserText() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        let events = EventDomainAdapter().parseEvents(
            dataLine: #"{"directory":"/repo","payload":{"type":"session.next.prompted","properties":{"sessionID":"ses-1","messageID":"msg-2","prompt":{"text":"Hello from CLI"},"delivery":"steer"}}}"#
        )
        for event in events {
            eventStream.emit(event)
        }
        let published = await waitUntil {
            collector.latest()?.contains(where: { $0.id == "msg-2" && $0.text.contains("Hello from CLI") }) == true
        }
        task.cancel()
        #expect(published)
    }

    @Test func messagePartUpdated_textPartAppendsToSlot() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        // Create a user message slot first.
        eventStream.emit(eventWithInfo(id: "msg-2", role: "user", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))

        // Add a text part to it.
        let partEvent = eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "Hello from part", sessionID: "ses-1")
        eventStream.emit(partEvent)
        let updated = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text.contains("Hello from part") == true
        }
        task.cancel()

        let msg2 = collector.latest()?.first(where: { $0.id == "msg-2" })
        #expect(updated)
        #expect(msg2?.text.contains("Hello from part") == true)
        #expect(msg2?.parts.count == 1)
    }

    @Test func messagePartUpdated_textDelta_marksStreaming() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(200))

        // Tool pending.
        eventStream.emit(eventWithToolPart(partID: "prt-t1", messageID: "msg-2", tool: "webfetch", status: "pending", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(200))

        // Tool running.
        eventStream.emit(eventWithToolPart(partID: "prt-t1", messageID: "msg-2", tool: "webfetch", status: "running", title: "Searching", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(200))

        // Tool completed.
        eventStream.emit(eventWithToolPart(partID: "prt-t1", messageID: "msg-2", tool: "webfetch", status: "completed", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(500))

        // Add a text part to it.
        let partEvent = eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "Hello from part", sessionID: "ses-1")
        eventStream.emit(partEvent)
        try? await Task.sleep(for: .milliseconds(500))
        task.cancel()

        let last = collector.latest()
        let msg2 = last?.first(where: { $0.id == "msg-2" })
        let toolParts = msg2?.parts.compactMap { part -> ToolPart? in if case .tool(let t) = part { t } else { nil } }
        #expect(toolParts?.count == 1)
        #expect(toolParts?.first?.toolName == "webfetch")
        #expect(toolParts?.first?.status == .completed)
        // Tool labels stay on structured parts; flattened text is TextPart-only (OPE-49).
        #expect(msg2?.text.contains("webfetch") != true)
        #expect(msg2?.text == "Hello from part")
    }

    @Test func messagePartDelta_appendsStreamingText() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Hel", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "lo", sessionID: "ses-1"))
        let updated = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Hello"
        }
        task.cancel()

        let msg2 = collector.latest()?.first(where: { $0.id == "msg-2" })
        #expect(updated)
        #expect(msg2?.text == "Hello")
        guard case .text(let textPart) = msg2?.parts.first else {
            Issue.record("Expected text part")
            return
        }
        #expect(textPart.isStreaming == true)
    }

    @Test func displayPipelinePublishesStreamingAndFinalStatesFromRealDeltaEvents() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)
        let pipeline = DisplayMessagePipeline(
            messageSync: sync,
            contentPreparer: MessageContentPreparer()
        )
        let collector = DisplayMessageCollector()
        let task = Task {
            await collector.collect(pipeline.observeDisplayMessages(sessionID: "ses-1", directory: "/repo"))
        }
        let loaded = await waitUntil { collector.latest() != nil }
        #expect(loaded)

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Hello", sessionID: "ses-1"))
        let streaming = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.isStreaming == true
        }
        #expect(streaming)

        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "Hello", sessionID: "ses-1"))
        let finalized = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.isStreaming == false
                && collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Hello"
        }
        task.cancel()
        #expect(finalized)
    }

    @Test func messagePartDelta_keepsReasoningSeparateFromText() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithReasoningPart(partID: "prt-reason", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-reason", messageID: "msg-2", delta: "hmm", field: "reasoning", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "Hi", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(200))
        task.cancel()

        let msg2 = collector.latest()?.first(where: { $0.id == "msg-2" })
        #expect(msg2?.text == "Hi")
        #expect(msg2?.parts.count == 2)
        guard case .reasoning(let reasoning) = msg2?.parts.first else {
            Issue.record("Expected reasoning part")
            return
        }
        #expect(reasoning.text == "hmm")
        guard case .text(let textPart) = msg2?.parts.last else {
            Issue.record("Expected text part")
            return
        }
        #expect(textPart.isStreaming == false)
    }

    @Test func messagePartDelta_ignoresMissingField() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "Hi", sessionID: "ses-1"))
        eventStream.emit(
            eventWithPartDelta(
                partID: "prt-1",
                messageID: "msg-2",
                delta: "!",
                field: nil,
                sessionID: "ses-1"
            )
        )
        try? await Task.sleep(for: .milliseconds(100))
        task.cancel()

        let msg2 = collector.latest()?.first(where: { $0.id == "msg-2" })
        #expect(msg2?.text == "Hi")
        guard case .text(let textPart) = msg2?.parts.first else {
            Issue.record("Expected text part")
            return
        }
        #expect(textPart.isStreaming == false)
    }

    @Test func messagePartDelta_keepsReasoningPartSeparateFromText() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithReasoningPart(partID: "prt-reason", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(
            eventWithPartDelta(
                partID: "prt-reason",
                messageID: "msg-2",
                delta: "The user said hello.",
                field: "reasoning",
                sessionID: "ses-1"
            )
        )
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Hello!", sessionID: "ses-1"))
        let updated = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Hello!"
        }
        task.cancel()

        #expect(updated)
        let msg2 = collector.latest()?.first(where: { $0.id == "msg-2" })
        #expect(msg2?.text == "Hello!")
        #expect(msg2?.parts.count == 2)
        guard case .reasoning(let reasoning) = msg2?.parts.first else {
            Issue.record("Expected reasoning part")
            return
        }
        #expect(reasoning.text == "The user said hello.")
    }

    @Test func sessionIdle_reloadClearsStreaming() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))
        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Hello", sessionID: "ses-1"))
        let streaming = await waitUntil {
            guard case .text(let part) = collector.latest()?.first(where: { $0.id == "msg-2" })?.parts.first else {
                return false
            }
            return part.isStreaming
        }
        #expect(streaming)

        repo.messages = [
            Message(
                id: "msg-2",
                role: .assistant,
                text: "Hello",
                parts: [.text(TextPart(text: "Hello", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        let finalized = await waitUntil {
            guard case .text(let part) = collector.latest()?.first(where: { $0.id == "msg-2" })?.parts.first else {
                return false
            }
            return part.text == "Hello" && !part.isStreaming
        }
        task.cancel()

        #expect(finalized)
        #expect(repo.listMessagesCallCount == 1)
    }

    @Test func sessionIdleReloadsWhenLastAssistantHasNoVisibleContent() async {
        let repo = FakeLiveMessageRepository()
        repo.messages = [
            Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(
                id: "msg-2",
                role: .assistant,
                text: "",
                parts: [.reasoning(ReasoningPart(text: "", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 2
            ),
        ]
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.contains(where: { $0.id == "msg-2" }) == true }
        #expect(loaded)
        let afterInitial = repo.listMessagesCallCount
        repo.messages = [
            Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(
                id: "msg-2",
                role: .assistant,
                text: "Done",
                parts: [.text(TextPart(text: "Done", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 2
            ),
        ]
        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        let reloaded = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Done"
        }
        task.cancel()
        #expect(reloaded)
        #expect(repo.listMessagesCallCount > afterInitial)
    }

    @Test func reconnectUnchangedIdentityDoesNotRepaint() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)
        #expect(collector.emissions.count == 1)
        let listening = await waitUntil { eventStream.hasCoordinatorSubscribers }
        #expect(listening)

        eventStream.setConnectionState(.connected)
        eventStream.openGap()
        eventStream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: "ses-1", rawProperties: nil))
        let reloaded = await waitUntil { repo.listMessagesCallCount >= 2 }
        try? await Task.sleep(for: .milliseconds(50))
        task.cancel()

        #expect(reloaded)
        #expect(collector.emissions.count == 1)
        #expect(collector.latest()?.first?.text == "Hello")
    }

    @Test func doesNotPollWhileEventStreamConnected() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 25)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)

        repo.messages = [
            Message(
                id: "msg-2",
                role: .assistant,
                text: "Polled",
                parts: [.text(TextPart(text: "Polled", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        try? await Task.sleep(for: .milliseconds(90))
        task.cancel()

        #expect(collector.emissions.count == 1)
        #expect(collector.latest()?.first?.text == "Hello")
        #expect(repo.listMessagesCallCount == 1)
    }

    @Test func silentStreamingStall_keepsLocalWhenRestIsBehind() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 25)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest() != nil }
        #expect(loaded)

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Longer local stream", sessionID: "ses-1"))
        let streaming = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Longer local stream"
        }
        #expect(streaming)

        repo.messages = [
            Message(
                id: "msg-2",
                role: .assistant,
                text: "Short",
                parts: [.text(TextPart(text: "Short", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        try? await Task.sleep(for: .milliseconds(80))
        task.cancel()
        #expect(collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Longer local stream")
    }

    @Test func partUpdatedSnapshot_thenDuplicateDeltas_shouldNotDoubleText() {
        // Repro shape from Session Chat flash (sim 2026-08-03): streamed
        // chars hit 28 while final REST text was 14 ("Test received." × 2).
        // Server emits part.updated with the full snapshot so far, then delayed
        // deltas that were already covered by that snapshot can still arrive.
        let store = InMemoryMessageStore()
        _ = store.apply(
            sessionID: "ses-1",
            patch: .infoUpdated([
                "id": .string("msg-2"),
                "role": .string("assistant"),
                "sessionID": .string("ses-1"),
            ])
        )
        _ = store.apply(
            sessionID: "ses-1",
            patch: .partUpdated(part: [
                "id": .string("prt-1"),
                "messageID": .string("msg-2"),
                "sessionID": .string("ses-1"),
                "type": .string("text"),
                "text": .string(""),
            ], delta: nil)
        )
        _ = store.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "msg-2", partID: "prt-1", delta: "Test")
        )
        _ = store.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "msg-2", partID: "prt-1", delta: " received.")
        )
        #expect(store.messages(sessionID: "ses-1").first(where: { $0.id == "msg-2" })?.text == "Test received.")

        // Authoritative snapshot of the same content (clears chunks).
        _ = store.apply(
            sessionID: "ses-1",
            patch: .partUpdated(part: [
                "id": .string("prt-1"),
                "messageID": .string("msg-2"),
                "sessionID": .string("ses-1"),
                "type": .string("text"),
                "text": .string("Test received."),
            ], delta: nil)
        )
        #expect(store.messages(sessionID: "ses-1").first(where: { $0.id == "msg-2" })?.text == "Test received.")

        // Late duplicate deltas already covered by the snapshot.
        _ = store.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "msg-2", partID: "prt-1", delta: "Test")
        )
        _ = store.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "msg-2", partID: "prt-1", delta: " received.")
        )

        let text = store.messages(sessionID: "ses-1").first(where: { $0.id == "msg-2" })?.text
        #expect(text == "Test received.")
    }

    @Test func replaceAll_clearsStreamingAndMakesRestAuthoritative() async {
        let store = InMemoryMessageStore()
        _ = store.apply(
            sessionID: "ses-1",
            patch: .infoUpdated([
                "id": .string("msg-2"),
                "role": .string("assistant"),
                "sessionID": .string("ses-1"),
            ])
        )
        _ = store.apply(
            sessionID: "ses-1",
            patch: .partUpdated(part: [
                "id": .string("prt-1"),
                "messageID": .string("msg-2"),
                "sessionID": .string("ses-1"),
                "type": .string("text"),
                "text": .string(""),
            ], delta: nil)
        )
        _ = store.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "msg-2", partID: "prt-1", delta: "Hel")
        )
        store.replaceAll(
            sessionID: "ses-1",
            messages: [
                Message(
                    id: "msg-2",
                    role: .assistant,
                    text: "Final",
                    parts: [.text(TextPart(text: "Final", isStreaming: false))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
        )
        _ = store.apply(
            sessionID: "ses-1",
            patch: .partDelta(messageID: "msg-2", partID: "prt-1", delta: "lo")
        )

        let msg2 = store.messages(sessionID: "ses-1").first(where: { $0.id == "msg-2" })
        #expect(msg2?.text == "Final")
        guard case .text(let textPart) = msg2?.parts.first else {
            Issue.record("Expected authoritative REST text part after replaceAll")
            return
        }
        #expect(textPart.isStreaming == false)
    }

    @Test func messagePartDelta_finalPartUpdatedClearsStreaming() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Hello", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "Hello", sessionID: "ses-1"))
        let cleared = await waitUntil {
            guard case .text(let part) = collector.latest()?.first(where: { $0.id == "msg-2" })?.parts.first else {
                return false
            }
            return part.text == "Hello" && part.isStreaming == false
        }
        task.cancel()

        #expect(cleared)
    }

    @Test func messagePartUpdated_preservesPartOrderAcrossReplacement() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(50))

        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "First", sessionID: "ses-1"))
        eventStream.emit(eventWithToolPart(partID: "prt-t1", messageID: "msg-2", tool: "bash", status: "pending", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-2", messageID: "msg-2", text: "Second", sessionID: "ses-1"))
        eventStream.emit(eventWithToolPart(partID: "prt-t1", messageID: "msg-2", tool: "bash", status: "completed", sessionID: "ses-1"))
        // Tool labels stay on structured parts; flattened text is TextPart-only (OPE-49).
        let expectedText = "First\nSecond"
        let updated = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == expectedText
        }
        task.cancel()

        let msg2 = collector.latest()?.first(where: { $0.id == "msg-2" })
        #expect(updated)
        #expect(msg2?.text == expectedText)
        #expect(msg2?.parts.count == 3)
        guard case .text(let first) = msg2?.parts[0] else {
            Issue.record("Expected first text part")
            return
        }
        guard case .tool(let tool) = msg2?.parts[1] else {
            Issue.record("Expected middle tool part")
            return
        }
        guard case .text(let second) = msg2?.parts[2] else {
            Issue.record("Expected last text part")
            return
        }
        #expect(first.text == "First")
        #expect(tool.toolName == "bash")
        #expect(tool.status == .completed)
        #expect(second.text == "Second")
    }

    @Test func messagePartUpdated_placeholderToolNameDoesNotOverwriteQuestion() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithToolPart(partID: "prt-q", messageID: "msg-2", tool: "question", status: "pending", sessionID: "ses-1"))
        eventStream.emit(eventWithToolPart(partID: "prt-q", messageID: "msg-2", tool: "tool", status: "completed", sessionID: "ses-1"))
        let updated = await waitUntil {
            guard case .tool(let tool) = collector.latest()?.first(where: { $0.id == "msg-2" })?.parts.first else {
                return false
            }
            return tool.toolName == "question" && tool.status == .completed
        }
        task.cancel()
        #expect(updated)
    }

    @Test func messagePartUpdated_namelessToolInfersWebsearchFromMergedQuery() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(100))

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithToolPart(partID: "prt-s", messageID: "msg-2", tool: "", status: "pending", sessionID: "ses-1"))
        eventStream.emit(
            eventWithToolPart(
                partID: "prt-s",
                messageID: "msg-2",
                tool: "",
                status: "completed",
                sessionID: "ses-1",
                inputJSON: "{\"query\":\"opencode mobile\"}"
            )
        )
        let updated = await waitUntil {
            guard case .tool(let tool) = collector.latest()?.first(where: { $0.id == "msg-2" })?.parts.first else {
                return false
            }
            return tool.toolName == "websearch" && tool.status == .completed
        }
        task.cancel()
        #expect(updated)
    }

    @Test func messagePartUpdated_forRestSeededMessageReloadsTimeline() async {
        let repo = FakeLiveMessageRepository()
        repo.messages = [
            Message(
                id: "msg-1",
                role: .assistant,
                text: "Old\nSibling",
                parts: [
                    .text(TextPart(text: "Old", isStreaming: false)),
                    .text(TextPart(text: "Sibling", isStreaming: false)),
                ],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))
        let callsAfterInitial = repo.listMessagesCallCount

        repo.messages = [
            Message(
                id: "msg-1",
                role: .assistant,
                text: "New\nSibling",
                parts: [
                    .text(TextPart(text: "New", isStreaming: false)),
                    .text(TextPart(text: "Sibling", isStreaming: false)),
                ],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        eventStream.emit(eventWithInfo(id: "msg-1", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-1", text: "New", sessionID: "ses-1"))
        let updated = await waitUntil {
            collector.latest()?.first?.text == "New\nSibling"
        }
        task.cancel()

        #expect(updated)
        #expect(collector.latest()?.first?.text == "New\nSibling")
        #expect(collector.latest()?.first?.parts.count == 2)
        #expect(repo.listMessagesCallCount == callsAfterInitial + 1)
    }

    @Test func restSeededPartIdentity_preservesSiblingsAndStreamsFollowingDelta() async {
        let repo = FakeLiveMessageRepository()
        repo.messages = [
            Message(
                id: "msg-1",
                role: .assistant,
                text: "Old\nSibling",
                parts: [
                    .text(TextPart(id: "prt-1", text: "Old", isStreaming: false)),
                    .text(TextPart(id: "prt-2", text: "Sibling", isStreaming: false)),
                ],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Old\nSibling" }
        #expect(loaded)
        let callsAfterInitial = repo.listMessagesCallCount

        eventStream.emit(eventWithInfo(id: "msg-1", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-1", text: "New", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-1", delta: " live", sessionID: "ses-1"))

        let streamed = await waitUntil { collector.latest()?.first?.text == "New live\nSibling" }
        task.cancel()
        #expect(streamed)
        #expect(collector.latest()?.first?.parts.count == 2)
        #expect(repo.listMessagesCallCount == callsAfterInitial)
    }

    @Test func messagePartUpdated_fileChangeEmitsWhenFlattenedTextIsUnchanged() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)

        let file = FilePart(
            id: "prt-file",
            filename: "notes.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "data:text/plain;base64,SGVsbG8="
        )
        repo.messages = [
            Message(
                id: "msg-1",
                role: .user,
                text: "Hello",
                parts: [.text(TextPart(text: "Hello", isStreaming: false)), .file(file)],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        eventStream.emit(eventWithFilePart(file, messageID: "msg-1", sessionID: "ses-1"))

        let updated = await waitUntil {
            collector.latest()?.first?.parts.contains(.file(file)) == true
        }
        task.cancel()
        #expect(updated)
    }

    @Test func messageRemoved_deletesSlotAndEmits() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))

        // Remove msg-1.
        let json = #"{"payload":{"type":"message.removed","properties":{"sessionID":"ses-1","messageID":"msg-1"}}}"#
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(dataLine: json)!
        eventStream.emit(event)
        let removed = await waitUntil {
            collector.latest()?.isEmpty == true
        }
        task.cancel()

        #expect(removed)
        #expect(collector.latest()?.isEmpty == true)
    }

    @Test func messagePartRemoved_updatesText() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))

        // Add msg-2 via SSE (incremental cache).
        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(200))

        // Add a text part to msg-2.
        eventStream.emit(eventWithTextPart(partID: "prt-2", messageID: "msg-2", text: "Part content", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(200))

        // Now remove that part.
        let json = #"{"payload":{"type":"message.part.removed","properties":{"sessionID":"ses-1","messageID":"msg-2","partID":"prt-2"}}}"#
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(dataLine: json)!
        eventStream.emit(event)
        try? await Task.sleep(for: .milliseconds(500))
        task.cancel()

        let last = collector.latest()
        let msg2 = last?.first(where: { $0.id == "msg-2" })
        // Brand-new SSE slots without renderable content stay hidden.
        #expect(msg2 == nil)
    }

    @Test func eventsForOtherSessionsAreIgnored() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil(timeoutMs: 5_000) {
            collector.latest() != nil
        }
        #expect(loaded)

        let countBefore = collector.latest()?.count

        // Emit an event for ses-2.
        let event = eventWithInfo(id: "msg-99", role: "user", sessionID: "ses-2")
        eventStream.emit(event)
        try? await Task.sleep(for: .milliseconds(500))

        let countAfter = collector.latest()?.count
        #expect(countBefore == countAfter)
        task.cancel()
    }

    // MARK: - Helpers

    private func hiddenReasoningTranscript() -> [Message] {
        [
            Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(
                id: "msg-2",
                role: .assistant,
                text: "",
                parts: [.reasoning(ReasoningPart(id: "prt-r", text: "", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 2
            ),
        ]
    }

    private func completedAssistantTranscript() -> [Message] {
        [
            Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(
                id: "msg-2",
                role: .assistant,
                text: "Done",
                parts: [.text(TextPart(text: "Done", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 2
            ),
        ]
    }

    private func visibleThoughtTranscript() -> [Message] {
        [
            Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(
                id: "msg-2",
                role: .assistant,
                text: "",
                parts: [.reasoning(ReasoningPart(id: "prt-r", text: "Considering the request.", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 2
            ),
        ]
    }

    private func completedTurnWithExtraAssistant() -> [Message] {
        visibleThoughtTranscript() + [
            Message(
                id: "msg-3",
                role: .assistant,
                text: "Done",
                parts: [.text(TextPart(text: "Done", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 3
            ),
        ]
    }

    private func waitUntil(
        timeoutMs: Int = 5_000,
        predicate: @MainActor () -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
        while Date() < deadline {
            if predicate() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return predicate()
    }

    private func eventWithInfo(id: String, role: String, sessionID: String) -> OpencodeEvent {
        let json = """
        {"payload":{"type":"message.updated","properties":{"info":{"id":"\(id)","role":"\(role)","sessionID":"\(sessionID)"}}}}
        """
        return EventDomainAdapter().parseEvent(dataLine: json)!
    }

    private func eventWithTextPart(partID: String, messageID: String, text: String, sessionID: String) -> OpencodeEvent {
        let json = """
        {"payload":{"type":"message.part.updated","properties":{"part":{"id":"\(partID)","messageID":"\(messageID)","sessionID":"\(sessionID)","type":"text","text":"\(text)"}}}}
        """
        return EventDomainAdapter().parseEvent(dataLine: json)!
    }

    private func eventWithReasoningPart(partID: String, messageID: String, text: String, sessionID: String) -> OpencodeEvent {
        let json = """
        {"payload":{"type":"message.part.updated","properties":{"part":{"id":"\(partID)","messageID":"\(messageID)","sessionID":"\(sessionID)","type":"reasoning","text":"\(text)"}}}}
        """
        return EventDomainAdapter().parseEvent(dataLine: json)!
    }

    private func eventWithFilePart(_ file: FilePart, messageID: String, sessionID: String) -> OpencodeEvent {
        let json = """
        {"payload":{"type":"message.part.updated","properties":{"part":{"id":"\(file.id)","messageID":"\(messageID)","sessionID":"\(sessionID)","type":"file","mime":"\(file.mime)","filename":"\(file.filename)","url":"\(file.dataURL)"}}}}
        """
        return EventDomainAdapter().parseEvent(dataLine: json)!
    }

    private func eventWithPartDelta(
        partID: String,
        messageID: String,
        delta: String,
        field: String? = "text",
        sessionID: String
    ) -> OpencodeEvent {
        let fieldJSON = field.map { ",\"field\":\"\($0)\"" } ?? ""
        let json = """
        {"payload":{"type":"message.part.delta","properties":{"sessionID":"\(sessionID)","messageID":"\(messageID)","partID":"\(partID)"\(fieldJSON),"delta":"\(delta)"}}}
        """
        return EventDomainAdapter().parseEvent(dataLine: json)!
    }

    private func eventWithToolPart(
        partID: String,
        messageID: String,
        tool: String,
        status: String,
        title: String? = nil,
        sessionID: String,
        inputJSON: String? = nil
    ) -> OpencodeEvent {
        let titleJson = title.map { ", \"title\": \"\($0)\"" } ?? ""
        let inputState = inputJSON.map { ", \"input\": \($0)" } ?? ""
        let json = """
        {"payload":{"type":"message.part.updated","properties":{"part":{"id":"\(partID)","messageID":"\(messageID)","sessionID":"\(sessionID)","type":"tool","tool":"\(tool)","state":{"status":"\(status)"\(titleJson)\(inputState)}}}}}
        """
        return EventDomainAdapter().parseEvent(dataLine: json)!
    }
}

// MARK: - Helpers

private final class MainThreadFlag: @unchecked Sendable {
    private let lock = NSLock()
    private(set) var sawMain = false
    private(set) var calls = 0

    func note(_ isMain: Bool) {
        lock.lock()
        defer { lock.unlock() }
        calls += 1
        if isMain { sawMain = true }
    }
}

private final class CacheConfirmAwareCollector: @unchecked Sendable {
    private(set) var emissions: [[Message]] = []
    private(set) var sawCacheSyncConfirmed = false

    func collect(_ stream: AsyncStream<MessageListUpdate>) async {
        for await update in stream {
            switch update {
            case .messages(let messages, _):
                emissions.append(messages)
            case .cacheSyncConfirmed:
                sawCacheSyncConfirmed = true
            case .loadFailed:
                break
            }
        }
    }

    func latest() -> [Message]? {
        emissions.last
    }
}

private final class MessageCollector: @unchecked Sendable {
    private(set) var emissions: [[Message]] = []
    private(set) var isFinished = false

    func collect(_ stream: AsyncStream<MessageListUpdate>) async {
        for await update in stream {
            if case .messages(let messages, _) = update {
                emissions.append(messages)
            }
        }
        isFinished = true
    }

    func latest() -> [Message]? {
        emissions.last
    }
}

private final class DisplayMessageCollector: @unchecked Sendable {
    private(set) var emissions: [[DisplayMessage]] = []

    func collect(_ stream: AsyncStream<DisplayMessageListUpdate>) async {
        for await update in stream {
            if case .messages(let messages, _) = update {
                emissions.append(messages)
            }
        }
    }

    func latest() -> [DisplayMessage]? {
        emissions.last
    }
}

@MainActor
private func makeMessageSync(
    repo: FakeLiveMessageRepository,
    eventStream: FakeForegroundEventStream,
    pollIntervalMs: Int = LiveStoreRefreshPolicy.workingPollIntervalMs,
    eventStagingCapacity: Int = messageEventStagingCapacity,
    connectionProfileID: String = "local",
    worldSignals: any WorldSignals = SilentWorldSignals.shared
) -> MessageSync {
    eventStream.setConnectionState(.connected)
    return MessageSync(
        listMessages: { sessionID, directory in
            try await repo.listMessages(sessionID: sessionID, directory: directory)
        },
        store: SessionMessageEngine(store: InMemoryMessageStore()),
        eventStream: eventStream,
        worldSignals: worldSignals,
        pollIntervalMs: pollIntervalMs,
        eventStagingCapacity: eventStagingCapacity,
        connectionProfileIDProvider: { connectionProfileID },
        recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) }
    )
}

private final class FakeLiveMessageRepository: MessageRepository, @unchecked Sendable {
    var messages: [Message] = [
        Message(id: "msg-1", role: .user, text: "Hello", parts: [
            .text(TextPart(text: "Hello", isStreaming: false)),
        ], model: nil, mode: nil, sentAtEpochMs: nil),
    ]
    var messagesBySession: [String: [Message]] = [:]
    var listMessagesCallCount = 0
    var shouldFailList = false
    private var holdNextList = false
    private var blockedListContinuation: CheckedContinuation<Void, Never>?
    private let lock = NSLock()

    private var _listHoldsEntered = 0

    var listHoldsEntered: Int {
        lock.lock()
        defer { lock.unlock() }
        return _listHoldsEntered
    }

    var isListBlocked: Bool {
        lock.lock()
        defer { lock.unlock() }
        return blockedListContinuation != nil
    }

    func holdNextListMessages() {
        holdNextList = true
    }

    func resumeListMessages() {
        lock.lock()
        let continuation = blockedListContinuation
        blockedListContinuation = nil
        lock.unlock()
        continuation?.resume()
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        AsyncStream { _ in }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { _ in }
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await listMessages(sessionID: sessionID, directory: directory)
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        let messages = try await refreshMessages(sessionID: sessionID, directory: directory)
        return messages.map { DisplayMessage(message: $0) }
    }

    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage {
        DisplayMessage(
            message: Message(id: id, role: .user, text: text, model: nil, mode: nil, sentAtEpochMs: nil)
        )
    }

    func clearMessageCache() {}

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        listMessagesCallCount += 1
        if holdNextList {
            holdNextList = false
            await withCheckedContinuation { continuation in
                lock.lock()
                _listHoldsEntered += 1
                blockedListContinuation = continuation
                lock.unlock()
            }
        }
        if shouldFailList {
            throw URLError(.cannotConnectToHost)
        }
        return messagesBySession[sessionID] ?? messages
    }
    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message { fatalError() }
    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message { fatalError() }
    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {}
}

private final class SeedingSessionCache: SessionCache, @unchecked Sendable {
    private let messages: [Message]

    init(messages: [Message]) {
        self.messages = messages
    }

    func seedSessions(worktree: String) async -> [Session]? { nil }
    func rememberSessions(worktree: String, sessions: [Session]) async {}
    func openMessages(sessionID: String, worktree: String) -> any SessionMessageCacheHandle {
        SeedingSessionMessageCacheHandle(messages: messages)
    }
    func purgeWorktree(serverBaseURL: String, worktree: String) async {}
    func clearAll() async {}
    func estimatedSizeBytes() async -> Int64 { 0 }
}

private final class SeedingSessionMessageCacheHandle: SessionMessageCacheHandle, @unchecked Sendable {
    private let messages: [Message]

    init(messages: [Message]) {
        self.messages = messages
    }

    func seed() async -> [Message]? { messages }
    func note(messages: [Message]) {}
    func flush() async {}
    func close() {}
}
