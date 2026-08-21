import Foundation
import OSLog

typealias MessageListFetcher = @Sendable (String, String?) async throws -> [Message]

/// Explicitly nonisolated: app default actor isolation is MainActor, but
/// ingress/reduction must stay off the UI thread (Android parity; send freezes).
nonisolated final class MessageSync: @unchecked Sendable {
    private let listMessages: MessageListFetcher
    private let store: SessionMessageEngine
    private let eventStream: any ForegroundEventStream
    private let worldSignals: any WorldSignals
    private let pollIntervalMs: Int
    private let eventStagingCapacity: Int
    private let eventStagingByteCapacity: Int
    private let connectionProfileIDProvider: @Sendable () -> String
    private let recoveryDelay: @Sendable (Int) async -> Void
    private let nowMs: @Sendable () -> Int64
    private let connectionEpoch: ConnectionEpoch
    private let sessionCache: any SessionCache
    private let lock = NSLock()

    private var observationCache: [MessageSessionKey: SharedLiveObservation<MessageListUpdate>] = [:]
    private var lastFingerprint: [String: String] = [:]
    private var lastPublishedFrame: [String: [Message]] = [:]
    private var lastPublishedRevision: [String: UInt64] = [:]
    private var healthByKey: [MessageSessionKey: MessageSyncHealth] = [:]
    private var messageCacheHandles: [MessageSessionKey: any SessionMessageCacheHandle] = [:]
    /// Store slots that painted from cache and still need network confirmation.
    private var awaitingCacheSyncConfirm: Set<String> = []
    private var observationGeneration = 0
    private var terminalSessionKeys: Set<MessageSessionKey> = []
    private var startedGenerationByKey: [MessageSessionKey: Int] = [:]
    private var lastMessageEventAtMs: [String: Int64] = [:]
    private let invalidationBus = StoreInvalidationBus()

    init(
        listMessages: @escaping MessageListFetcher,
        store: SessionMessageEngine,
        eventStream: any ForegroundEventStream,
        worldSignals: any WorldSignals = SilentWorldSignals.shared,
        pollIntervalMs: Int = LiveStoreRefreshPolicy.workingPollIntervalMs,
        eventStagingCapacity: Int = messageEventStagingCapacity,
        eventStagingByteCapacity: Int = messageEventStagingByteCapacity,
        connectionProfileIDProvider: @escaping @Sendable () -> String = { "local" },
        recoveryDelay: @escaping @Sendable (Int) async -> Void = { ms in
            try? await Task.sleep(for: .milliseconds(ms))
        },
        nowMs: @escaping @Sendable () -> Int64 = {
            Int64(Date().timeIntervalSince1970 * 1000)
        },
        connectionEpoch: ConnectionEpoch = .shared,
        sessionCache: any SessionCache = NoOpSessionCache.shared
    ) {
        self.listMessages = listMessages
        self.store = store
        self.eventStream = eventStream
        self.worldSignals = worldSignals
        self.pollIntervalMs = pollIntervalMs
        self.eventStagingCapacity = eventStagingCapacity
        self.eventStagingByteCapacity = eventStagingByteCapacity
        self.connectionProfileIDProvider = connectionProfileIDProvider
        self.recoveryDelay = recoveryDelay
        self.nowMs = nowMs
        self.connectionEpoch = connectionEpoch
        self.sessionCache = sessionCache
    }

    /// Convenience for call sites that still construct an in-memory store.
    convenience init(
        listMessages: @escaping MessageListFetcher,
        store: InMemoryMessageStore,
        eventStream: any ForegroundEventStream,
        worldSignals: any WorldSignals = SilentWorldSignals.shared,
        pollIntervalMs: Int = LiveStoreRefreshPolicy.workingPollIntervalMs,
        eventStagingCapacity: Int = messageEventStagingCapacity,
        eventStagingByteCapacity: Int = messageEventStagingByteCapacity,
        connectionProfileIDProvider: @escaping @Sendable () -> String = { "local" },
        recoveryDelay: @escaping @Sendable (Int) async -> Void = { ms in
            try? await Task.sleep(for: .milliseconds(ms))
        },
        nowMs: @escaping @Sendable () -> Int64 = {
            Int64(Date().timeIntervalSince1970 * 1000)
        },
        connectionEpoch: ConnectionEpoch = .shared,
        sessionCache: any SessionCache = NoOpSessionCache.shared
    ) {
        self.init(
            listMessages: listMessages,
            store: SessionMessageEngine(store: store),
            eventStream: eventStream,
            worldSignals: worldSignals,
            pollIntervalMs: pollIntervalMs,
            eventStagingCapacity: eventStagingCapacity,
            eventStagingByteCapacity: eventStagingByteCapacity,
            connectionProfileIDProvider: connectionProfileIDProvider,
            recoveryDelay: recoveryDelay,
            nowMs: nowMs,
            connectionEpoch: connectionEpoch,
            sessionCache: sessionCache
        )
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        let key = sessionKey(sessionID: sessionID, directory: directory)
        lock.lock()
        if observationCache[key] == nil {
            observationCache[key] = SharedLiveObservation { yield in
                // Detach from caller isolation (often MainActor via DisplayMessagePipeline).
                // Ingress/reduction must not run on the main thread — matches Android
                // MessageSync.assertOffMainThread and avoids send-path MainActor deadlock.
                Task.detached {
                    await self.runMessageLoop(key: key, yield: yield)
                }
            }
        }
        let observation = observationCache[key]!
        lock.unlock()
        return observation.stream()
    }

    func refreshMessages(
        sessionID: String,
        directory: String?,
        reason: String = StoreRefreshReason.pullToRefresh
    ) async throws -> [Message] {
        let key = sessionKey(sessionID: sessionID, directory: directory)
        return try await fullReloadOrThrow(key: key, reason: reason)
    }

    func invalidate(
        sessionID: String,
        directory: String?,
        reason: String = StoreRefreshReason.pullToRefresh
    ) {
        invalidationBus.invalidate(
            key: sessionKey(sessionID: sessionID, directory: directory).storeSlot,
            reason: reason
        )
    }

    func clearCache() {
        lock.lock()
        observationGeneration += 1
        terminalSessionKeys.formUnion(observationCache.keys)
        startedGenerationByKey.removeAll()
        observationCache.removeAll()
        lastFingerprint.removeAll()
        lastPublishedFrame.removeAll()
        lastPublishedRevision.removeAll()
        healthByKey.removeAll()
        awaitingCacheSyncConfirm.removeAll()
        lock.unlock()
        store.clear()
    }

    func peekStoreMessages(sessionID: String, directory: String? = nil) -> [Message] {
        let key = sessionKey(sessionID: sessionID, directory: directory)
        return store.messages(sessionID: key.storeSlot)
    }

    /// Test/observability seam for the recovery/fault matrix.
    func syncHealth(sessionID: String, directory: String?) -> MessageSyncHealth {
        let key = sessionKey(sessionID: sessionID, directory: directory)
        lock.lock()
        defer { lock.unlock() }
        return healthByKey[key] ?? .healthy
    }

    private func setHealth(_ health: MessageSyncHealth, for key: MessageSessionKey) {
        lock.lock()
        healthByKey[key] = health
        lock.unlock()
    }

    private func fingerprint(for storeSlot: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return lastFingerprint[storeSlot]
    }

    private func setFingerprint(_ value: String, for storeSlot: String) {
        lock.lock()
        lastFingerprint[storeSlot] = value
        lock.unlock()
    }

    private func isAwaitingCacheSyncConfirm(for storeSlot: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return awaitingCacheSyncConfirm.contains(storeSlot)
    }

    private func setAwaitingCacheSyncConfirm(_ awaiting: Bool, for storeSlot: String) {
        lock.lock()
        if awaiting {
            awaitingCacheSyncConfirm.insert(storeSlot)
        } else {
            awaitingCacheSyncConfirm.remove(storeSlot)
        }
        lock.unlock()
    }

    private func publishedFrame(for storeSlot: String) -> [Message]? {
        lock.lock()
        defer { lock.unlock() }
        return lastPublishedFrame[storeSlot]
    }

    private func setPublishedFrame(_ messages: [Message], revision: UInt64, for storeSlot: String) {
        lock.lock()
        lastPublishedFrame[storeSlot] = messages
        lastPublishedRevision[storeSlot] = revision
        lock.unlock()
    }

    private func sessionKey(sessionID: String, directory: String?) -> MessageSessionKey {
        MessageSessionKey(
            connectionProfileID: connectionProfileIDProvider(),
            directory: directory,
            sessionID: sessionID
        )
    }

    private func nextObservationGeneration() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return observationGeneration
    }

    private func observationStillCurrent(key: MessageSessionKey) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let startedGeneration = startedGenerationByKey[key] else { return false }
        return observationGeneration == startedGeneration && !terminalSessionKeys.contains(key)
    }

    private func markTerminal(key: MessageSessionKey) {
        lock.lock()
        terminalSessionKeys.insert(key)
        lock.unlock()
    }

    private func registerObservation(key: MessageSessionKey, generation: Int) {
        lock.lock()
        startedGenerationByKey[key] = generation
        lock.unlock()
    }

    private func noteMessageActivity(for storeSlot: String) {
        let timestamp = nowMs()
        lock.lock()
        lastMessageEventAtMs[storeSlot] = timestamp
        lock.unlock()
    }

    private func isStreamingStalled(storeSlot: String) -> Bool {
        lock.lock()
        let last = lastMessageEventAtMs[storeSlot]
        lock.unlock()
        guard let last else { return true }
        return nowMs() - last >= Int64(pollIntervalMs)
    }

    private func runMessageLoop(
        key: MessageSessionKey,
        yield: @escaping (MessageListUpdate) -> Void
    ) async {
        let startedGeneration = nextObservationGeneration()
        registerObservation(key: key, generation: startedGeneration)
        noteMessageActivity(for: key.storeSlot)
        let observationEpoch = ObservationEpoch(connectionEpoch.current())
        // Register before the initial REST request. Bounded staging closes the
        // part-shell/delta loss window without an unbounded observation buffer.
        let staging = MessageEventStaging(
            capacity: eventStagingCapacity,
            byteCapacity: eventStagingByteCapacity
        )
        let cacheHandle = openMessageCacheHandle(for: key)
        let resumeState = ObservationResumeState()
        func requestResumeCatchUp(reason: String = StoreRefreshReason.streamReconnected) -> Bool {
            guard resumeState.hasSeenConnected else { return false }
            let health = self.syncHealth(
                sessionID: key.sessionID,
                directory: key.directory.isEmpty ? nil : key.directory
            )
            let needsCatchUp: Bool = {
                if case .stale = health { return true }
                return self.connectionEpoch.current() != observationEpoch.current
            }()
            if !needsCatchUp {
                DiagnosticLog.info(.chat, "fullReload skip session=\(key.sessionID) reason=\(reason)")
                return false
            }
            guard resumeState.claimResume(epoch: connectionEpoch.current()) else {
                DiagnosticLog.info(.chat, "fullReload skip session=\(key.sessionID) reason=\(reason)")
                return false
            }
            staging.requestTransportResume(reason: reason)
            return true
        }
        let producer = Task { [eventStream] in
            for await event in eventStream.events() {
                staging.enqueue(event, sessionID: key.sessionID, epoch: observationEpoch.current)
            }
            staging.finish()
        }
        let connectionWatcher = Task { [eventStream, weak self] in
            guard let self else { return }
            for await state in eventStream.connectionStateUpdates() {
                switch state {
                case .connected:
                    // Cold resume after stop() reaches Connected/Live without
                    // stream.reconnected. If server.connected is missed, still
                    // adopt the bumped epoch and fullReload — otherwise a
                    // latched streaming turn blocks the poll watchdog forever.
                    let shouldRecover = resumeState.hasSeenConnected && {
                        let health = self.syncHealth(
                            sessionID: key.sessionID,
                            directory: key.directory.isEmpty ? nil : key.directory
                        )
                        if case .stale = health { return true }
                        return self.connectionEpoch.current() != observationEpoch.current
                    }()
                    resumeState.markSeenConnected()
                    if shouldRecover {
                        _ = requestResumeCatchUp()
                    }
                case .disconnected, .reconnecting, .idle:
                    guard resumeState.hasSeenConnected else { continue }
                    guard self.publishedFrame(for: key.storeSlot) != nil else { continue }
                    // Unlatch streaming so the poll watchdog can catch up even
                    // before Connected triggers transport-resumed recovery.
                    self.store.finishStreamingText(sessionID: key.storeSlot)
                    guard self.connectionEpoch.current() == observationEpoch.current else { continue }
                    let current = self.syncHealth(
                        sessionID: key.sessionID,
                        directory: key.directory.isEmpty ? nil : key.directory
                    )
                    guard current.isHealthy else { continue }
                    staging.enterStale(.transportUnavailable)
                    self.setHealth(.stale(.transportUnavailable), for: key)
                case .connecting:
                    // OPE-165: catch up as soon as cold resume occupies the stream
                    // (Connecting after stop cleared wasConnected), before live.
                    _ = requestResumeCatchUp(reason: StoreRefreshReason.streamReconnected)
                }
            }
        }
        let worldWatcher = Task { [worldSignals, weak self] in
            guard let self else { return }
            for await signal in worldSignals.signals() {
                if signal.marksStale {
                    guard resumeState.hasSeenConnected else { continue }
                    guard self.publishedFrame(for: key.storeSlot) != nil else { continue }
                    let current = self.syncHealth(
                        sessionID: key.sessionID,
                        directory: key.directory.isEmpty ? nil : key.directory
                    )
                    guard current.isHealthy else { continue }
                    staging.enterStale(.transportUnavailable)
                    self.setHealth(.stale(.transportUnavailable), for: key)
                } else if let reason = signal.catchUpReason {
                    _ = requestResumeCatchUp(reason: reason)
                }
            }
        }
        let invalidationWatcher = Task { [invalidationBus, weak self] in
            guard let self else { return }
            for await invalidation in invalidationBus.events() {
                if let slot = invalidation.key, slot != key.storeSlot { continue }
                staging.enterStale(.transportUnavailable)
                self.setHealth(.stale(.transportUnavailable), for: key)
                _ = requestResumeCatchUp(reason: invalidation.reason)
            }
        }
        defer {
            producer.cancel()
            connectionWatcher.cancel()
            worldWatcher.cancel()
            invalidationWatcher.cancel()
            staging.finish()
            closeMessageCacheHandle(for: key)
        }

        let publishMessages: @Sendable ([Message], Bool) -> Void = { [weak self] messages, isFromCache in
            guard let self else { return }
            guard self.connectionEpoch.current() == observationEpoch.current else { return }
            self.setPublishedFrame(
                messages,
                revision: self.store.revision(sessionID: key.storeSlot),
                for: key.storeSlot
            )
            if isFromCache {
                self.setAwaitingCacheSyncConfirm(true, for: key.storeSlot)
            } else {
                self.setAwaitingCacheSyncConfirm(false, for: key.storeSlot)
                cacheHandle.note(messages: messages)
            }
            yield(.messages(messages, isFromCache: isFromCache))
            // Fingerprint remains the poll / REST authority comparison only.
            self.setFingerprint(self.messageFingerprint(messages), for: key.storeSlot)
            MessagePipelineProbe.emit(.displayFramePublished)
        }

        if let seeded = await cacheHandle.seed() {
            let seedRevision = store.revision(sessionID: key.storeSlot)
            _ = store.replaceAllIfRevision(
                sessionID: key.storeSlot,
                messages: seeded,
                expectedRevision: seedRevision
            )
            let seededMerged = store.messages(sessionID: key.storeSlot)
            publishMessages(seededMerged, true)
        }

        // Poll in parallel with the initial REST so a hung first request cannot
        // leave Syncing stuck after the network returns (Android merge parity).
        async let pollLoop: Void = runTimelinePollLoop(
            key: key,
            staging: staging,
            observationEpoch: observationEpoch,
            publishMessages: { publishMessages($0, false) },
            confirmCacheSync: {
                self.setAwaitingCacheSyncConfirm(false, for: key.storeSlot)
                yield(.cacheSyncConfirmed)
            }
        )

        // Skip an identical REST republish after cache seed or warm replay so
        // DisplayMessagePipeline does not cancel in-flight enrich / plain-first.
        let fingerprintBeforeReload = fingerprint(for: key.storeSlot)
        let expectedRevision = store.revision(sessionID: key.storeSlot)
        let initialStarted = Date()
        do {
            SessionPerformanceProbe.emit("history_request_started")
            let messages = try await listMessages(key.sessionID, key.directory.isEmpty ? nil : key.directory)
            SessionPerformanceProbe.emit("history_request_finished")
            if staging.needsAuthoritativeRecovery {
                OpenCodeLog.messageContent.info(
                    "eventStagingRejectStaleAuthority session=\(key.sessionID, privacy: .public) highWater=\(staging.highWater)"
                )
                await performClosedLoopRecovery(
                    key: key,
                    staging: staging,
                    observationEpoch: observationEpoch,
                    reason: .overflow,
                    publishMessages: { publishMessages($0, false) }
                )
            } else {
                _ = store.replaceAllIfRevision(
                    sessionID: key.storeSlot,
                    messages: messages,
                    expectedRevision: expectedRevision
                )
                let merged = store.messages(sessionID: key.storeSlot)
                let fingerprint = messageFingerprint(merged)
                setPublishedFrame(merged, revision: store.revision(sessionID: key.storeSlot), for: key.storeSlot)
                setFingerprint(fingerprint, for: key.storeSlot)
                setHealth(.healthy, for: key)
                DiagnosticLog.info(
                    .chat,
                    "fullReload session=\(key.sessionID) reason=\(StoreRefreshReason.initialSubscribe) count=\(merged.count) ms=\(elapsedMs(since: initialStarted))"
                )
                if fingerprintBeforeReload != fingerprint {
                    publishMessages(merged, false)
                } else {
                    // Confirm disk without a second UI paint. Also clears the
                    // Syncing indicator on warm in-memory reopens where no disk
                    // seed ran this loop but REST agrees with the store.
                    cacheHandle.note(messages: merged)
                    setAwaitingCacheSyncConfirm(false, for: key.storeSlot)
                    yield(.cacheSyncConfirmed)
                }
            }
        } catch {
            SessionPerformanceProbe.emit("history_request_finished")
            if staging.needsAuthoritativeRecovery {
                await performClosedLoopRecovery(
                    key: key,
                    staging: staging,
                    observationEpoch: observationEpoch,
                    reason: .overflow,
                    publishMessages: { publishMessages($0, false) }
                )
            } else if fingerprint(for: key.storeSlot) != nil {
                setHealth(.recovering(.reloadFailed), for: key)
                await performClosedLoopRecovery(
                    key: key,
                    staging: staging,
                    observationEpoch: observationEpoch,
                    reason: .reloadFailed,
                    publishMessages: { publishMessages($0, false) }
                )
            } else {
                yield(.loadFailed(error))
                DiagnosticLog.warning(
                    .chat,
                    "fullReload failed session=\(key.sessionID) reason=\(StoreRefreshReason.initialSubscribe) ms=\(elapsedMs(since: initialStarted))"
                )
            }
        }

        async let eventLoop: Void = runEventRefreshLoop(
            key: key,
            staging: staging,
            observationEpoch: observationEpoch,
            publishMessages: { publishMessages($0, false) }
        )
        _ = await (eventLoop, pollLoop)
    }

    private func openMessageCacheHandle(for key: MessageSessionKey) -> any SessionMessageCacheHandle {
        lock.lock()
        defer { lock.unlock() }
        if let existing = messageCacheHandles[key] {
            return existing
        }
        let handle = sessionCache.openMessages(sessionID: key.sessionID, worktree: key.directory)
        messageCacheHandles[key] = handle
        return handle
    }

    private func closeMessageCacheHandle(for key: MessageSessionKey) {
        lock.lock()
        let handle = messageCacheHandles.removeValue(forKey: key)
        lock.unlock()
        handle?.close()
    }

    private func performClosedLoopRecovery(
        key: MessageSessionKey,
        staging: MessageEventStaging,
        observationEpoch: ObservationEpoch,
        reason: MessageSyncRecoveryReason,
        fetchReason: String = "overflow-recovery",
        publishMessages: @escaping @Sendable ([Message]) -> Void
    ) async {
        guard observationStillCurrent(key: key),
              connectionEpoch.current() == observationEpoch.current else {
            staging.clearRecoveringLatch()
            setHealth(.healthy, for: key)
            return
        }
        if !staging.needsAuthoritativeRecovery {
            staging.enterRecovering(reason)
        }
        setHealth(staging.currentHealth, for: key)
        var backoffMs = MessageSyncRecoveryPolicy.initialBackoffMs

        while !Task.isCancelled, observationStillCurrent(key: key) {
            staging.drain()
            staging.beginAuthorityWindow()
            let fingerprintBefore = fingerprint(for: key.storeSlot)
            let result = await fullReloadResult(key: key, reason: fetchReason, mergeExisting: true)
            switch result {
            case .success(let messages):
                let becameHealthy = staging.completeAuthorityWindow(reloadSucceeded: true)
                setHealth(staging.currentHealth, for: key)
                // Publish even if SSE start / dual v1 reconnect bumped
                // ConnectionEpoch mid-GET. Gating on epoch match left the REST
                // snapshot in the store and never pushed a frame.
                let published = observationStillCurrent(key: key)
                if published {
                    if fingerprintBefore != messageFingerprint(messages) {
                        publishMessages(messages)
                    }
                    observationEpoch.adopt(connectionEpoch.current())
                }
                if becameHealthy {
                    if published {
                        OpenCodeLog.messageContent.info(
                            "eventStagingRecovery session=\(key.sessionID, privacy: .public) highWater=\(staging.highWater)"
                        )
                    }
                    return
                }
            case .failure(let error):
                _ = staging.completeAuthorityWindow(reloadSucceeded: false)
                setHealth(staging.currentHealth, for: key)
                if isMissingSessionError(error) {
                    markTerminal(key: key)
                    OpenCodeLog.messageContent.error(
                        "fullReload missing session=\(key.sessionID, privacy: .public) stopping recovery"
                    )
                    return
                }
                // Keep last published frame; do not emit LoadFailed.
            }

            MessagePipelineProbe.emit(.recoveryRetry)
            await recoveryDelay(backoffMs)
            if Task.isCancelled { return }
            backoffMs = min(backoffMs * 2, MessageSyncRecoveryPolicy.maxBackoffMs)
        }
    }

    private enum ReloadResult {
        case success([Message])
        case failure(Error)
    }

    private func fullReloadOrThrow(key: MessageSessionKey, reason: String) async throws -> [Message] {
        SessionPerformanceProbe.emit("history_request_started")
        let expectedRevision = store.revision(sessionID: key.storeSlot)
        let directory = key.directory.isEmpty ? nil : key.directory
        let started = Date()
        let messages = try await listMessages(key.sessionID, directory)
        SessionPerformanceProbe.emit("history_request_finished")
        _ = store.replaceAllIfRevision(
            sessionID: key.storeSlot,
            messages: messages,
            expectedRevision: expectedRevision
        )
        let merged = store.messages(sessionID: key.storeSlot)
        setPublishedFrame(merged, revision: store.revision(sessionID: key.storeSlot), for: key.storeSlot)
        setFingerprint(messageFingerprint(merged), for: key.storeSlot)
        DiagnosticLog.info(
            .chat,
            "fullReload session=\(key.sessionID) reason=\(reason) count=\(merged.count) ms=\(elapsedMs(since: started))"
        )
        return merged
    }

    private func fullReloadResult(
        key: MessageSessionKey,
        reason: String,
        mergeExisting: Bool = false
    ) async -> ReloadResult {
        SessionPerformanceProbe.emit("history_request_started")
        let expectedRevision = store.revision(sessionID: key.storeSlot)
        let directory = key.directory.isEmpty ? nil : key.directory
        let started = Date()
        do {
            let messages = try await listMessages(key.sessionID, directory)
            if mergeExisting {
                _ = store.mergeAuthoritativeIfRevision(
                    sessionID: key.storeSlot,
                    messages: messages,
                    expectedRevision: expectedRevision
                )
            } else {
                _ = store.replaceAllIfRevision(
                    sessionID: key.storeSlot,
                    messages: messages,
                    expectedRevision: expectedRevision
                )
            }
            let merged = store.messages(sessionID: key.storeSlot)
            setPublishedFrame(merged, revision: store.revision(sessionID: key.storeSlot), for: key.storeSlot)
            setFingerprint(messageFingerprint(merged), for: key.storeSlot)
            SessionPerformanceProbe.emit("history_request_finished")
            MessagePipelineProbe.emit(.reducerCommitted)
            DiagnosticLog.info(
                .chat,
                "fullReload session=\(key.sessionID) reason=\(reason) count=\(merged.count) ms=\(elapsedMs(since: started))"
            )
            return .success(merged)
        } catch {
            SessionPerformanceProbe.emit("history_request_finished")
            DiagnosticLog.warning(
                .chat,
                "fullReload failed session=\(key.sessionID) reason=\(reason) ms=\(elapsedMs(since: started))"
            )
            return .failure(error)
        }
    }

    private func fullReloadSilent(
        key: MessageSessionKey,
        reason: String,
        staging: MessageEventStaging?,
        observationEpoch: ObservationEpoch,
        publishMessages: @escaping @Sendable ([Message]) -> Void
    ) async {
        guard connectionEpoch.current() == observationEpoch.current else { return }
        if let staging {
            staging.beginAuthorityWindow()
        }
        switch await fullReloadResult(key: key, reason: reason) {
        case .success(let messages):
            if let staging {
                let healthy = staging.completeAuthorityWindow(reloadSucceeded: true)
                setHealth(staging.currentHealth, for: key)
                publishMessages(messages)
                if !healthy {
                    await performClosedLoopRecovery(
                        key: key,
                        staging: staging,
                        observationEpoch: observationEpoch,
                        reason: .racedAuthority,
                        publishMessages: publishMessages
                    )
                }
            } else {
                publishMessages(messages)
                setHealth(.healthy, for: key)
            }
        case .failure:
            if let staging {
                _ = staging.completeAuthorityWindow(reloadSucceeded: false)
                setHealth(staging.currentHealth, for: key)
                await performClosedLoopRecovery(
                    key: key,
                    staging: staging,
                    observationEpoch: observationEpoch,
                    reason: .reloadFailed,
                    publishMessages: publishMessages
                )
            }
            // else: retain last good; no LoadFailed
        }
    }

    private func runEventRefreshLoop(
        key: MessageSessionKey,
        staging: MessageEventStaging,
        observationEpoch: ObservationEpoch,
        publishMessages: @escaping @Sendable ([Message]) -> Void
    ) async {
        var debounceTask: Task<Void, Never>?
        // One authoritative reconnect catch-up per ConnectionEpoch (OPE-165).
        // Coalesces TransportResumed, server.connected, and stream.reconnected.
        let catchUpGate = ReconnectCatchUpGate()
        defer { debounceTask?.cancel() }

        while let item = await staging.dequeue() {
            guard observationStillCurrent(key: key) else { return }
            switch item {
            case .overflow, .recoveryRequired:
                await performClosedLoopRecovery(
                    key: key,
                    staging: staging,
                    observationEpoch: observationEpoch,
                    reason: {
                        if case .recovering(let reason) = staging.currentHealth { return reason }
                        return .overflow
                    }(),
                    publishMessages: publishMessages
                )
                continue
            case .transportResumed(let reason):
                debounceTask?.cancel()
                debounceTask = nil
                guard catchUpGate.claim(connectionEpoch.current()) else {
                    DiagnosticLog.info(.chat, "fullReload skip session=\(key.sessionID) reason=\(reason)")
                    continue
                }
                observationEpoch.adopt(connectionEpoch.current())
                staging.enterRecovering(.reconnect, wakeConsumer: false)
                setHealth(staging.currentHealth, for: key)
                await performClosedLoopRecovery(
                    key: key,
                    staging: staging,
                    observationEpoch: observationEpoch,
                    reason: .reconnect,
                    fetchReason: reason,
                    publishMessages: publishMessages
                )
                continue
            case .event(let event, let eventEpoch, _):
                guard eventEpoch == observationEpoch.current else { continue }
                if staging.needsAuthoritativeRecovery {
                    await performClosedLoopRecovery(
                        key: key,
                        staging: staging,
                        observationEpoch: observationEpoch,
                        reason: {
                            if case .recovering(let reason) = staging.currentHealth { return reason }
                            return .overflow
                        }(),
                        publishMessages: publishMessages
                    )
                    continue
                }
                MessagePipelineProbe.emit(.eventAccepted)
                await handleStagedEvent(
                    event: event,
                    key: key,
                    staging: staging,
                    observationEpoch: observationEpoch,
                    debounceTask: &debounceTask,
                    catchUpGate: catchUpGate,
                    publishMessages: publishMessages
                )
            }
        }
    }

    private func handleStagedEvent(
        event: OpencodeEvent,
        key: MessageSessionKey,
        staging: MessageEventStaging,
        observationEpoch: ObservationEpoch,
        debounceTask: inout Task<Void, Never>?,
        catchUpGate: ReconnectCatchUpGate,
        publishMessages: @escaping @Sendable ([Message]) -> Void
    ) async {
        if event.type == "stream.reconnected" {
            debounceTask?.cancel()
            debounceTask = nil
            guard catchUpGate.claim(connectionEpoch.current()) else {
                DiagnosticLog.info(
                    .chat,
                    "fullReload skip session=\(key.sessionID) reason=\(StoreRefreshReason.streamReconnected)"
                )
                return
            }
            observationEpoch.adopt(connectionEpoch.current())
            staging.enterRecovering(.reconnect, wakeConsumer: false)
            setHealth(staging.currentHealth, for: key)
            await performClosedLoopRecovery(
                key: key,
                staging: staging,
                observationEpoch: observationEpoch,
                reason: .reconnect,
                fetchReason: StoreRefreshReason.streamReconnected,
                publishMessages: publishMessages
            )
            return
        }

        if event.type == "server.connected" {
            debounceTask?.cancel()
            debounceTask = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.messageDebounceMs))
                guard !Task.isCancelled, let self else { return }
                guard catchUpGate.claim(self.connectionEpoch.current()) else {
                    DiagnosticLog.info(
                        .chat,
                        "fullReload skip session=\(key.sessionID) reason=\(StoreRefreshReason.serverConnected)"
                    )
                    return
                }
                observationEpoch.adopt(self.connectionEpoch.current())
                staging.enterRecovering(.reconnect, wakeConsumer: false)
                self.setHealth(staging.currentHealth, for: key)
                await self.performClosedLoopRecovery(
                    key: key,
                    staging: staging,
                    observationEpoch: observationEpoch,
                    reason: .reconnect,
                    fetchReason: StoreRefreshReason.serverConnected,
                    publishMessages: publishMessages
                )
            }
            return
        }

        guard connectionEpoch.current() == observationEpoch.current else { return }

        guard event.sessionID == key.sessionID else { return }

        switch event.type {
        case "message.updated", "message.part.updated", "message.part.delta",
             "message.removed", "message.part.removed", "session.idle":
            noteMessageActivity(for: key.storeSlot)
        default:
            break
        }

        switch event.type {
        case "message.updated":
            handleMessageUpdated(event: event, key: key, publishMessages: publishMessages)
        case "message.part.updated":
            if requiresFullReloadForRestSeededPartUpdate(event: event, key: key) {
                await fullReloadSilent(
                    key: key,
                    reason: "message.part.updated-rest-seeded",
                    staging: staging,
                    observationEpoch: observationEpoch,
                    publishMessages: publishMessages
                )
                return
            }
            handleMessagePartUpdated(event: event, key: key, publishMessages: publishMessages)
        case "message.part.delta":
            handleMessagePartDelta(event: event, key: key, publishMessages: publishMessages)
        case "message.removed":
            handleMessageRemoved(event: event, key: key, publishMessages: publishMessages)
        case "message.part.removed":
            if requiresFullReloadForRestSeededPartRemoval(event: event, key: key) {
                await fullReloadSilent(
                    key: key,
                    reason: "message.part.removed-rest-seeded",
                    staging: staging,
                    observationEpoch: observationEpoch,
                    publishMessages: publishMessages
                )
                return
            }
            handleMessagePartRemoved(event: event, key: key, publishMessages: publishMessages)
        case "session.idle":
            // TUI v2 only flips footer idle on execution.* — it does not
            // REST-reload the transcript. Finalize local streaming parts.
            store.finishStreamingText(sessionID: key.storeSlot)
            let recovered = store.messages(sessionID: key.storeSlot)
            setHealth(.healthy, for: key)
            guard markChanged(key: key, messages: recovered) else { return }
            publishMessages(recovered)
        default:
            return
        }
    }

    private func handleMessageUpdated(
        event: OpencodeEvent,
        key: MessageSessionKey,
        publishMessages: @escaping @Sendable ([Message]) -> Void
    ) {
        guard let props = event.rawProperties,
              let infoDict = props["info"]?.dictionaryValue
        else { return }

        guard case .changed(let changeSet) = store.apply(sessionID: key.storeSlot, patch: .infoUpdated(infoDict)) else { return }
        MessagePipelineProbe.emit(.reducerCommitted)
        emitLiveMessages(key: key, changeSet: changeSet, publishMessages: publishMessages)
    }

    private func handleMessagePartUpdated(
        event: OpencodeEvent,
        key: MessageSessionKey,
        publishMessages: @escaping @Sendable ([Message]) -> Void
    ) {
        guard let props = event.rawProperties,
              let partDict = props["part"]?.dictionaryValue
        else { return }

        guard case .changed(let changeSet) = store.apply(
            sessionID: key.storeSlot,
            patch: .partUpdated(part: partDict, delta: props["delta"]?.stringValue)
        ) else { return }
        MessagePipelineProbe.emit(.reducerCommitted)
        emitLiveMessages(key: key, changeSet: changeSet, publishMessages: publishMessages)
    }

    private func handleMessagePartDelta(
        event: OpencodeEvent,
        key: MessageSessionKey,
        publishMessages: @escaping @Sendable ([Message]) -> Void
    ) {
        guard let props = event.rawProperties else { return }
        let field = props["field"]?.stringValue
        guard field == "text" || field == "reasoning" || field == "input" else { return }
        guard let messageID = props["messageID"]?.stringValue,
              let partID = props["partID"]?.stringValue,
              let delta = props["delta"]?.stringValue
        else { return }

        guard case .changed(let changeSet) = store.apply(
            sessionID: key.storeSlot,
            patch: .partDelta(messageID: messageID, partID: partID, delta: delta)
        ) else { return }
        MessagePipelineProbe.emit(.reducerCommitted)
        emitLiveMessages(key: key, changeSet: changeSet, publishMessages: publishMessages)
    }

    private func handleMessageRemoved(
        event: OpencodeEvent,
        key: MessageSessionKey,
        publishMessages: @escaping @Sendable ([Message]) -> Void
    ) {
        guard let messageID = event.rawProperties?["messageID"]?.stringValue else { return }
        guard case .changed(let changeSet) = store.apply(sessionID: key.storeSlot, patch: .removed(messageID: messageID)) else { return }
        MessagePipelineProbe.emit(.reducerCommitted)
        emitLiveMessages(key: key, changeSet: changeSet, publishMessages: publishMessages)
    }

    private func handleMessagePartRemoved(
        event: OpencodeEvent,
        key: MessageSessionKey,
        publishMessages: @escaping @Sendable ([Message]) -> Void
    ) {
        guard let props = event.rawProperties,
              let messageID = props["messageID"]?.stringValue,
              let partID = props["partID"]?.stringValue
        else { return }

        guard case .changed(let changeSet) = store.apply(
            sessionID: key.storeSlot,
            patch: .partRemoved(messageID: messageID, partID: partID)
        ) else { return }
        MessagePipelineProbe.emit(.reducerCommitted)
        emitLiveMessages(key: key, changeSet: changeSet, publishMessages: publishMessages)
    }

    private func requiresFullReloadForRestSeededPartUpdate(event: OpencodeEvent, key: MessageSessionKey) -> Bool {
        guard let partDict = event.rawProperties?["part"]?.dictionaryValue,
              let messageID = partDict["messageID"]?.stringValue
        else { return false }
        return store.isRestSeededMessage(sessionID: key.storeSlot, messageID: messageID)
    }

    private func requiresFullReloadForRestSeededPartRemoval(event: OpencodeEvent, key: MessageSessionKey) -> Bool {
        guard let messageID = event.rawProperties?["messageID"]?.stringValue else { return false }
        return store.isRestSeededMessage(sessionID: key.storeSlot, messageID: messageID)
    }

    private func emitLiveMessages(
        key: MessageSessionKey,
        changeSet: SessionChangeSet,
        publishMessages: @escaping @Sendable ([Message]) -> Void
    ) {
        // Info-only / empty-slot inserts carry no part, removal, or streaming signal.
        guard store.shouldPublishLive(changeSet) else { return }
        let previous = publishedFrame(for: key.storeSlot)
        let frame = store.frameAfterChange(
            sessionID: key.storeSlot,
            changeSet: changeSet,
            previous: previous
        )
        // No whole-history fingerprint gate on the live SSE path.
        publishMessages(frame)
    }

    private func runTimelinePollLoop(
        key: MessageSessionKey,
        staging: MessageEventStaging,
        observationEpoch: ObservationEpoch,
        publishMessages: @escaping @Sendable ([Message]) -> Void,
        confirmCacheSync: @escaping @Sendable () -> Void
    ) async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(pollIntervalMs))
            guard !Task.isCancelled else { return }
            guard observationStillCurrent(key: key) else { return }
            if staging.needsAuthoritativeRecovery { continue }
            guard connectionEpoch.current() == observationEpoch.current else { continue }
            // Polling REST mid-stream publishes a behind snapshot and looks like the
            // assistant message clearing then reprinting from the start.
            let streaming = store.hasStreamingText(sessionID: key.storeSlot)
            let stalled = streaming && isStreamingStalled(storeSlot: key.storeSlot)
            if streaming && !stalled { continue }
            // TUI does not poll GET …/message while the event iterator is live.
            // Keep the watchdog for transport drop and stalled streaming only.
            if eventStream.connectionState == .connected && !stalled { continue }
            let expectedRevision = store.revision(sessionID: key.storeSlot)
            let directory = key.directory.isEmpty ? nil : key.directory
            do {
                let messages = try await listMessages(key.sessionID, directory)
                if streaming {
                    let local = store.messages(sessionID: key.storeSlot)
                    guard MessageSnapshotAuthority.isAheadOrEqual(remote: messages, local: local) else {
                        continue
                    }
                    DiagnosticLog.info(.chat, "streaming stall catch-up session=\(key.sessionID)")
                }
                let fp = messageFingerprint(messages)
                if fp != fingerprint(for: key.storeSlot) {
                    guard store.replaceAllIfRevision(
                        sessionID: key.storeSlot,
                        messages: messages,
                        expectedRevision: expectedRevision
                    ) else { continue }
                    let merged = store.messages(sessionID: key.storeSlot)
                    noteMessageActivity(for: key.storeSlot)
                    publishMessages(merged)
                    continue
                }
                guard isAwaitingCacheSyncConfirm(for: key.storeSlot) else { continue }
                // Initial REST may still be hung after reconnect; poll already
                // agrees with the cache — clear Syncing without a second paint.
                openMessageCacheHandle(for: key).note(messages: messages)
                confirmCacheSync()
            } catch {
                if isMissingSessionError(error) {
                    markTerminal(key: key)
                    return
                }
                continue
            }
        }
    }

    private func messageFingerprint(_ messages: [Message]) -> String {
        messages.map { message in
            let parts = message.parts.map(partFingerprint).joined(separator: ",")
            return "\(message.id):\(message.text.count):\(message.text.hashValue):\(parts)"
        }.joined(separator: "|")
    }

    private func partFingerprint(_ part: MessagePart) -> String {
        switch part {
        case .text(let text):
            "text:\(text.text.count):\(text.text.hashValue):\(text.isStreaming)"
        case .file(let file):
            "file:\(file.id):\(file.mime):\(file.filename):\(file.dataURL.count):\(file.dataURL.hashValue)"
        case .tool(let tool):
            "tool:\(tool.toolName):\(tool.status.rawValue):\(tool.title ?? ""):\(tool.error ?? "")"
        case .compaction(let part):
            "compaction:\(part.id ?? ""):\(part.auto):\(part.completed)"
        case .reasoning(let part):
            "reasoning:\(part.text.count):\(part.text.hashValue):\(part.isStreaming)"
        }
    }

    private func markChanged(key: MessageSessionKey, messages: [Message]) -> Bool {
        let fp = messageFingerprint(messages)
        if fp == fingerprint(for: key.storeSlot) { return false }
        setFingerprint(fp, for: key.storeSlot)
        return true
    }

    private func isMissingSessionError(_ error: Error) -> Bool {
        let text = String(describing: error) + " " + error.localizedDescription
        return text.localizedCaseInsensitiveContains("NotFoundError")
            || text.localizedCaseInsensitiveContains("Session not found")
    }

    private func elapsedMs(since start: Date) -> Int {
        Int((Date().timeIntervalSince(start) * 1000).rounded())
    }
}

private nonisolated final class ObservationResumeState: @unchecked Sendable {
    private let lock = NSLock()
    private var seenConnected = false
    private var resumeCatchUpRequestedEpoch: UInt64?

    var hasSeenConnected: Bool {
        lock.lock()
        defer { lock.unlock() }
        return seenConnected
    }

    func markSeenConnected() {
        lock.lock()
        seenConnected = true
        lock.unlock()
    }

    func claimResume(epoch: UInt64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if resumeCatchUpRequestedEpoch == epoch { return false }
        resumeCatchUpRequestedEpoch = epoch
        return true
    }
}
