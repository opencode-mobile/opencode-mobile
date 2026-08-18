import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct MessageSyncTests {
    private let decoder = JSONDecoder()

    @Test func warmReopen_unchangedFingerprint_confirmsCacheSyncWithoutSeed() async {
        // Warm reopen: the in-memory store still holds the previous session's
        // rows, but no disk seed runs this loop (NoOpSessionCache) and REST
        // returns an identical fingerprint. The Syncing indicator must clear
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

        // First observation publishes and records the fingerprint.
        let first = MessageCollector()
        let firstTask = Task { await first.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        _ = await waitUntil { first.latest()?.first?.text == "Hello" }
        firstTask.cancel()
        try? await Task.sleep(for: .milliseconds(20))

        // Warm reopen: same store rows, REST unchanged, no disk seed. The
        // unchanged fingerprint must still yield cacheSyncConfirmed.
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
        let repo = FakeLiveMessageRepository()
        repo.holdNextListMessages()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let blocked = await waitUntil { repo.isListBlocked }
        #expect(blocked)

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Hello", sessionID: "ses-1"))
        repo.resumeListMessages()

        let streamed = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Hello"
        }
        task.cancel()
        #expect(streamed)
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
        let blocked = await waitUntil { repo.isListBlocked }
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

        repo.shouldFailList = true
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

    @Test func eventDuringRecovery_keepsRecoveringUntilQuietAuthority() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 60_000)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)

        // Enter recovering via failed reconnect (no REST hold / MainActor deadlock).
        repo.shouldFailList = true
        eventStream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: "ses-1", rawProperties: nil))
        let recovering = await waitUntil {
            if case .recovering = sync.syncHealth(sessionID: "ses-1", directory: "/repo") {
                return true
            }
            return false
        }
        #expect(recovering)

        // Session-relevant event while recovering must keep authority open.
        eventStream.emit(eventWithInfo(id: "msg-race", role: "assistant", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(20))

        repo.messages = [
            Message(
                id: "msg-final",
                role: .assistant,
                text: "final",
                parts: [.text(TextPart(text: "final", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        repo.shouldFailList = false

        let healthy = await waitUntil {
            collector.latest()?.first?.text == "final"
                && sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy
        }
        task.cancel()
        _ = await waitUntil { collector.isFinished }
        #expect(healthy)
    }

    @Test func repeatedOverflowCycles_recoverToHealthyOnlyAfterValidatedReplace() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            pollIntervalMs: 60_000,
            eventStagingCapacity: 64,
            eventStagingByteCapacity: 200,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) }
        )
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)

        for cycle in 0..<2 {
            let recoveredText = "recovered-\(cycle)"
            // Block the event consumer on REST so staging can accumulate. A waiting
            // consumer otherwise handoffs each enqueue without buffering, so byte
            // overflow never trips under real scheduling (CI flake).
            repo.holdNextListMessages()
            eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1", rawProperties: nil))
            let blocked = await waitUntil { repo.isListBlocked }
            #expect(blocked)

            repo.shouldFailList = true
            for _ in 0..<8 {
                eventStream.emit(
                    eventWithPartDelta(
                        partID: "prt-\(cycle)",
                        messageID: "msg-1",
                        delta: String(repeating: "x", count: 80),
                        sessionID: "ses-1"
                    )
                )
            }
            // Let the producer drain AsyncStream into staging while the consumer
            // remains blocked on the idle reload.
            await Task.yield()
            try? await Task.sleep(for: .milliseconds(20))
            repo.resumeListMessages()

            let recovering = await waitUntil {
                if case .recovering = sync.syncHealth(sessionID: "ses-1", directory: "/repo") {
                    return true
                }
                return false
            }
            #expect(recovering)
            let expectedRetained = cycle == 0 ? "Hello" : "recovered-\(cycle - 1)"
            #expect(collector.latest()?.first?.text == expectedRetained)

            repo.messages = [
                Message(
                    id: "msg-recovered-\(cycle)",
                    role: .assistant,
                    text: recoveredText,
                    parts: [.text(TextPart(text: recoveredText, isStreaming: false))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]
            repo.shouldFailList = false
            let healthy = await waitUntil {
                collector.latest()?.first?.text == recoveredText
                    && sync.syncHealth(sessionID: "ses-1", directory: "/repo") == .healthy
            }
            #expect(healthy)
        }

        task.cancel()
        _ = await waitUntil { collector.isFinished }
    }

    @Test func rapidSessionSwitch_AtoBtoA_noCrossSessionPublish() async {
        let repo = FakeLiveMessageRepository()
        repo.messagesBySession = [
            "ses-A": [
                Message(
                    id: "msg-a",
                    role: .assistant,
                    text: "session-A",
                    parts: [.text(TextPart(text: "session-A", isStreaming: false))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ],
            "ses-B": [
                Message(
                    id: "msg-b",
                    role: .assistant,
                    text: "session-B",
                    parts: [.text(TextPart(text: "session-B", isStreaming: false))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ],
        ]
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 60_000)

        let collectorA1 = MessageCollector()
        let taskA1 = Task {
            await collectorA1.collect(sync.observeMessages(sessionID: "ses-A", directory: "/repo"))
        }
        let loadedA1 = await waitUntil { collectorA1.latest()?.first?.text == "session-A" }
        #expect(loadedA1)
        #expect(collectorA1.emissions.contains { frame in frame.contains { $0.id == "msg-b" } } == false)
        taskA1.cancel()
        _ = await waitUntil { collectorA1.isFinished }

        let collectorB = MessageCollector()
        let taskB = Task {
            await collectorB.collect(sync.observeMessages(sessionID: "ses-B", directory: "/repo"))
        }
        let loadedB = await waitUntil { collectorB.latest()?.first?.text == "session-B" }
        #expect(loadedB)
        #expect(collectorB.emissions.contains { frame in frame.contains { $0.id == "msg-a" } } == false)

        eventStream.emit(eventWithInfo(id: "msg-a-foreign", role: "assistant", sessionID: "ses-A"))
        try? await Task.sleep(for: .milliseconds(100))
        #expect(collectorB.emissions.contains { frame in
            frame.contains { $0.id == "msg-a" || $0.id == "msg-a-foreign" }
        } == false)
        #expect(collectorB.latest()?.first?.text == "session-B")

        taskB.cancel()
        _ = await waitUntil { collectorB.isFinished }

        let collectorA2 = MessageCollector()
        let taskA2 = Task {
            await collectorA2.collect(sync.observeMessages(sessionID: "ses-A", directory: "/repo"))
        }
        let loadedA2 = await waitUntil { collectorA2.latest()?.first?.text == "session-A" }
        #expect(loadedA2)
        #expect(collectorA2.emissions.contains { frame in frame.contains { $0.id == "msg-b" } } == false)
        taskA2.cancel()
        _ = await waitUntil { collectorA2.isFinished }
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
        task.cancel()
        #expect(stale)
        #expect(collector.latest()?.first?.text == "Hello")
    }

    @Test func backgroundResumeToLiveWithoutReconnect_refreshesAfterStreamingLatch() async {
        // Mid-stream background stop clears wasConnected so cold start reaches
        // Connected/Live without stream.reconnected. Transport-resumed recovery
        // must still fullReload when server.connected is missed.
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let epoch = ConnectionEpoch()
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            pollIntervalMs: LiveStoreRefreshPolicy.workingPollIntervalMs,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            connectionEpoch: epoch
        )
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)

        eventStream.setConnectionState(.connected)
        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Partial", sessionID: "ses-1"))
        let streaming = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Partial"
        }
        #expect(streaming)

        eventStream.setConnectionState(.idle)
        let stale = await waitUntil {
            if case .stale(.transportUnavailable) = sync.syncHealth(sessionID: "ses-1", directory: "/repo") {
                return true
            }
            return false
        }
        #expect(stale)

        repo.messages = [
            Message(id: "msg-1", role: .user, text: "Hello", parts: [], model: nil, mode: nil, sentAtEpochMs: nil),
            Message(
                id: "msg-2",
                role: .assistant,
                text: "Finished while away",
                parts: [.text(TextPart(text: "Finished while away", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]

        _ = epoch.bump()
        eventStream.setConnectionState(.connected)
        let refreshed = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Finished while away"
        }
        task.cancel()
        #expect(refreshed)
    }

    @Test func backgroundResumeToLiveWithoutReconnect_refreshesWhenNotStreaming() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let epoch = ConnectionEpoch()
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            pollIntervalMs: LiveStoreRefreshPolicy.workingPollIntervalMs,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            connectionEpoch: epoch
        )
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)

        eventStream.setConnectionState(.connected)
        eventStream.setConnectionState(.idle)
        let stale = await waitUntil {
            if case .stale(.transportUnavailable) = sync.syncHealth(sessionID: "ses-1", directory: "/repo") {
                return true
            }
            return false
        }
        #expect(stale)

        repo.messages = [
            Message(
                id: "msg-2",
                role: .assistant,
                text: "Updated while away",
                parts: [],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        _ = epoch.bump()
        eventStream.setConnectionState(.connected)
        let refreshed = await waitUntil {
            collector.latest()?.first?.text == "Updated while away"
        }
        task.cancel()
        #expect(refreshed)
    }

    @Test func resumeCatchUp_startsOnConnectingAndCoalescesServerConnected() async {
        // OPE-165 snapshot: cached messages stay, one list fetch before live,
        // TransportResumed + server.connected share one catch-up.
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let epoch = ConnectionEpoch()
        var listCallTimestamps: [Date] = []
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                listCallTimestamps.append(Date())
                return try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            pollIntervalMs: 60_000,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            connectionEpoch: epoch
        )
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)
        let callsAfterInitial = repo.listMessagesCallCount

        eventStream.setConnectionState(.connected)
        eventStream.setConnectionState(.idle)
        let stale = await waitUntil {
            if case .stale(.transportUnavailable) = sync.syncHealth(sessionID: "ses-1", directory: "/repo") {
                return true
            }
            return false
        }
        #expect(stale)
        #expect(collector.latest()?.first?.text == "Hello")

        repo.messages = [
            Message(
                id: "msg-2",
                role: .assistant,
                text: "Caught up while connecting",
                parts: [],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        _ = epoch.bump()
        eventStream.setConnectionState(.connecting)
        let refreshedBeforeLive = await waitUntil {
            collector.latest()?.first?.text == "Caught up while connecting"
        }
        #expect(refreshedBeforeLive)
        #expect(repo.listMessagesCallCount == callsAfterInitial + 1)

        eventStream.setConnectionState(.connected)
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil, rawProperties: nil))
        try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.messageDebounceMs + 250))
        task.cancel()

        #expect(repo.listMessagesCallCount == callsAfterInitial + 1)
        #expect(collector.latest()?.first?.text == "Caught up while connecting")
        #expect(!listCallTimestamps.isEmpty)
    }

    @Test func staleEpoch_eventDoesNotMutateStore() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let epoch = ConnectionEpoch()
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            pollIntervalMs: 60_000,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            connectionEpoch: epoch
        )
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)
        let before = collector.emissions.count

        _ = epoch.bump()
        eventStream.emit(
            eventWithPartDelta(
                partID: "prt-1",
                messageID: "msg-1",
                delta: " should-not-apply",
                sessionID: "ses-1"
            )
        )
        try? await Task.sleep(for: .milliseconds(150))
        task.cancel()
        #expect(collector.emissions.count == before)
        #expect(collector.latest()?.first?.text == "Hello")
    }

    @Test func serverConnected_afterEpochBump_recoversAndAppliesLiveDeltas() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let epoch = ConnectionEpoch()
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            pollIntervalMs: 60_000,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            connectionEpoch: epoch
        )
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)

        _ = epoch.bump()
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil, rawProperties: nil))
        // Wait for debounced adopt + recovery; isHealthy alone is true before reconnect.
        try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.messageDebounceMs + 250))
        #expect(sync.syncHealth(sessionID: "ses-1", directory: "/repo").isHealthy)

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(
            eventWithPartDelta(
                partID: "prt-1",
                messageID: "msg-2",
                delta: "Live after reconnect",
                sessionID: "ses-1"
            )
        )
        let streamed = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Live after reconnect"
        }
        task.cancel()
        #expect(streamed)
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

    @Test func pollSurvivesEpochBumpUntilAdopt() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let epoch = ConnectionEpoch()
        let sync = MessageSync(
            listMessages: { sessionID, directory in
                try await repo.listMessages(sessionID: sessionID, directory: directory)
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            pollIntervalMs: 40,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            connectionEpoch: epoch
        )
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)

        _ = epoch.bump()
        try? await Task.sleep(for: .milliseconds(90))

        eventStream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: nil, rawProperties: nil))
        try? await Task.sleep(for: .milliseconds(80))

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
        let polled = await waitUntil {
            collector.latest()?.first?.text == "From poll after reconnect"
        }
        task.cancel()
        #expect(polled)
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
        #expect(repo.listMessagesCallCount == 2)
    }

    @Test func failedIdleReloadEndsStreamingAndPollingRecovers() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 25)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest() != nil }
        #expect(loaded)

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Partial", sessionID: "ses-1"))
        let streaming = await waitUntil {
            guard case .text(let part) = collector.latest()?.first(where: { $0.id == "msg-2" })?.parts.first else {
                return false
            }
            return part.isStreaming
        }
        #expect(streaming)

        repo.shouldFailList = true
        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        let recovered = await waitUntil {
            guard case .text(let part) = collector.latest()?.first(where: { $0.id == "msg-2" })?.parts.first else {
                return false
            }
            return !part.isStreaming
        }
        #expect(recovered)

        repo.shouldFailList = false
        repo.messages = [
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
        let finalized = await waitUntil {
            collector.latest()?.first?.text == "Final"
        }
        task.cancel()
        #expect(finalized)
    }

    @Test func silentStreamingStall_appliesAheadRestSnapshot() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 25)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest() != nil }
        #expect(loaded)

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Partial", sessionID: "ses-1"))
        let streaming = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Partial"
        }
        #expect(streaming)

        repo.messages = [
            Message(id: "msg-1", role: .user, text: "Hello", parts: [
                .text(TextPart(text: "Hello", isStreaming: false)),
            ], model: nil, mode: nil, sentAtEpochMs: nil),
            Message(
                id: "msg-2",
                role: .assistant,
                text: "Finished while silent",
                parts: [.text(TextPart(text: "Finished while silent", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        let caughtUp = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Finished while silent"
        }
        task.cancel()
        #expect(caughtUp)
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

    @Test func pollResponseIsDiscardedWhenStreamingStartsInFlight() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream, pollIntervalMs: 25)
        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest() != nil }
        #expect(loaded)

        repo.holdNextListMessages()
        let pollBlocked = await waitUntil { repo.isListBlocked }
        #expect(pollBlocked)

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Live", sessionID: "ses-1"))
        let streamed = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Live"
        }
        repo.resumeListMessages()
        try? await Task.sleep(for: .milliseconds(50))
        task.cancel()

        #expect(streamed)
        #expect(collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Live")
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

    @Test func streamReconnected_triggersFullReload() async {
        let repo = FakeLiveMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeMessageSync(repo: repo, eventStream: eventStream)

        let collector = MessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        try? await Task.sleep(for: .milliseconds(300))

        repo.messages = [
            Message(id: "msg-1", role: .user, text: "After reconnect", parts: [], model: nil, mode: nil, sentAtEpochMs: nil),
        ]

        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(dataLine: #"{"payload":{"type":"stream.reconnected","properties":{}}}"#)!
        eventStream.emit(event)
        let reloaded = await waitUntil {
            collector.latest()?.first?.text == "After reconnect"
        }
        task.cancel()

        #expect(reloaded)
        #expect(collector.latest()?.first?.text == "After reconnect")
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

    private func eventWithToolPart(partID: String, messageID: String, tool: String, status: String, title: String? = nil, sessionID: String) -> OpencodeEvent {
        let titleJson = title.map { ", \"title\": \"\($0)\"" } ?? ""
        let json = """
        {"payload":{"type":"message.part.updated","properties":{"part":{"id":"\(partID)","messageID":"\(messageID)","sessionID":"\(sessionID)","type":"tool","tool":"\(tool)","state":{"status":"\(status)"\(titleJson)}}}}}
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
    connectionProfileID: String = "local"
) -> MessageSync {
    MessageSync(
        listMessages: { sessionID, directory in
            try await repo.listMessages(sessionID: sessionID, directory: directory)
        },
        store: SessionMessageEngine(store: InMemoryMessageStore()),
        eventStream: eventStream,
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
