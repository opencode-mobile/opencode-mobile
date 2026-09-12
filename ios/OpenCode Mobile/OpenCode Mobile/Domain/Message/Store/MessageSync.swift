import Foundation

typealias MessageListFetcher = @Sendable (String, String?) async throws -> [Message]

/// Explicitly nonisolated: app default actor isolation is MainActor, but
/// ingress/reduction must stay off the UI thread (Android parity; send freezes).
nonisolated final class MessageSync: @unchecked Sendable {
    private let listMessages: MessageListFetcher
    private let listMessagePage: MessagePageFetcher
    private let store: SessionMessageEngine
    private let eventStream: any ForegroundEventStream
    private var liveConnection: any LiveConnection { eventStream.asLiveConnection() }
    private let worldSignals: any WorldSignals
    private let pollIntervalMs: Int
    private let eventStagingCapacity: Int
    private let eventStagingByteCapacity: Int
    private let connectionProfileIDProvider: @Sendable () -> String
    private let recoveryDelay: @Sendable (Int) async -> Void
    private let nowMs: @Sendable () -> Int64
    private let sessionCache: any SessionCache
    private let lock = NSLock()

    private var observationCache: [MessageSessionKey: SharedLiveObservation<MessageListUpdate>] = [:]
    private var lastFingerprint: [String: String] = [:]
    private var lastPublishedFrame: [String: [Message]] = [:]
    private var lastPublishedRevision: [String: UInt64] = [:]
    private var healthByKey: [MessageSessionKey: MessageSyncHealth] = [:]
    private var catchUpSlots: Set<String> = []
    private var catchUpChromeSubscribers: [UUID: (slot: String?, continuation: AsyncStream<Bool>.Continuation)] = [:]
    private let catchUpChromeQueue = DispatchQueue(label: "dev.opencode.mobile.message-sync.catch-up-chrome")
    private var messageCacheHandles: [MessageSessionKey: any SessionMessageCacheHandle] = [:]
    /// Store slots that painted from cache and still need network confirmation.
    private var awaitingCacheSyncConfirm: Set<String> = []
    private var observationGeneration = 0
    private var terminalSessionKeys: Set<MessageSessionKey> = []
    private var startedGenerationByKey: [MessageSessionKey: Int] = [:]
    private var lastMessageEventAtMs: [String: Int64] = [:]
    private var lastPublishedProfileID: String?
    private var restAttempts: [String] = []
    private var authorities: [MessageSessionKey: GapAuthority] = [:]
    private let invalidationBus = StoreInvalidationBus()
    private var olderCursorBySlot: [String: String] = [:]
    private var hasOlderBySlot: [String: Bool] = [:]
    private var olderLoadInFlight: Set<String> = []

    init(
        listMessages: @escaping MessageListFetcher,
        listMessagePage: MessagePageFetcher? = nil,
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
        sessionCache: any SessionCache = NoOpSessionCache.shared
    ) {
        self.listMessages = listMessages
        self.listMessagePage = listMessagePage ?? { sessionID, directory, query in
            switch query {
            case .newest:
                return .complete(try await listMessages(sessionID, directory))
            case .older:
                return MessageHistoryPage(messages: [], olderCursor: nil, hasOlder: false)
            }
        }
        self.store = store
        self.eventStream = eventStream
        self.worldSignals = worldSignals
        self.pollIntervalMs = pollIntervalMs
        self.eventStagingCapacity = eventStagingCapacity
        self.eventStagingByteCapacity = eventStagingByteCapacity
        self.connectionProfileIDProvider = connectionProfileIDProvider
        self.recoveryDelay = recoveryDelay
        self.nowMs = nowMs
        self.sessionCache = sessionCache
    }

    /// Convenience for call sites that still construct an in-memory store.
    convenience init(
        listMessages: @escaping MessageListFetcher,
        listMessagePage: MessagePageFetcher? = nil,
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
        sessionCache: any SessionCache = NoOpSessionCache.shared
    ) {
        self.init(
            listMessages: listMessages,
            listMessagePage: listMessagePage,
            store: SessionMessageEngine(store: store),
            eventStream: eventStream,
            worldSignals: worldSignals,
            pollIntervalMs: pollIntervalMs,
            eventStagingCapacity: eventStagingCapacity,
            eventStagingByteCapacity: eventStagingByteCapacity,
            connectionProfileIDProvider: connectionProfileIDProvider,
            recoveryDelay: recoveryDelay,
            nowMs: nowMs,
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
        let authority = gapAuthority(for: key)
        authority.invalidate(liveConnection.snapshot)
        var begun = authority.captureBegin(liveConnection.snapshot)
        while true {
            switch await fullReloadResult(key: key, reason: reason) {
            case .accepted(let messages):
                let newerUnpaid = acknowledgeExactGeneration(
                    authority,
                    begun: begun,
                    snapshot: liveConnection.snapshot
                )
                if !newerUnpaid {
                    setHealth(.healthy, for: key)
                    return messages
                }
                guard let next = authority.tryBegin(liveConnection.snapshot) else {
                    return messages
                }
                begun = next
            case .discarded(let messages):
                authority.fail(begun)
                return messages
            case .failure(let error):
                authority.fail(begun)
                setHealth(.recovering(.reloadFailed), for: key)
                throw error
            }
        }
    }

    /// Production REST attempt reasons, recorded before the fetch succeeds or throws.
    func recordedRestAttempts() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return restAttempts
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
        startedGenerationByKey.removeAll()
        let stale = Array(observationCache.values)
        observationCache.removeAll()
        lastFingerprint.removeAll()
        lastPublishedFrame.removeAll()
        lastPublishedRevision.removeAll()
        healthByKey.removeAll()
        catchUpSlots.removeAll()
        awaitingCacheSyncConfirm.removeAll()
        lastPublishedProfileID = nil
        authorities.removeAll()
        olderCursorBySlot.removeAll()
        hasOlderBySlot.removeAll()
        olderLoadInFlight.removeAll()
        lock.unlock()
        stale.forEach { $0.finish() }
        store.clear()
        publishCatchUpChrome()
    }

    /// Last observation that published a transcript frame.
    func publishedProfileID() -> String? {
        lock.lock()
        defer { lock.unlock() }
        return lastPublishedProfileID
    }

    func peekStoreMessages(sessionID: String, directory: String? = nil) -> [Message] {
        let key = sessionKey(sessionID: sessionID, directory: directory)
        return store.messages(sessionID: key.storeSlot)
    }

    func hasOlderMessages(sessionID: String, directory: String? = nil) -> Bool {
        let key = sessionKey(sessionID: sessionID, directory: directory)
        lock.lock()
        defer { lock.unlock() }
        return hasOlderBySlot[key.storeSlot] ?? false
    }

    func loadOlderMessages(sessionID: String, directory: String?) async throws -> [Message] {
        let key = sessionKey(sessionID: sessionID, directory: directory)
        lock.lock()
        let cursor = olderCursorBySlot[key.storeSlot]
        let hasOlder = hasOlderBySlot[key.storeSlot] ?? false
        let alreadyLoading = olderLoadInFlight.contains(key.storeSlot)
        if hasOlder, let cursor, !alreadyLoading {
            olderLoadInFlight.insert(key.storeSlot)
        }
        lock.unlock()
        guard hasOlder, let cursor, !alreadyLoading else {
            return store.messages(sessionID: key.storeSlot)
        }
        defer {
            lock.lock()
            olderLoadInFlight.remove(key.storeSlot)
            lock.unlock()
        }
        let directory = key.directory.isEmpty ? nil : key.directory
        let page = try await fetchHistoryPage(
            sessionID: key.sessionID,
            directory: directory,
            query: .older(cursor: cursor, limit: MessageHistoryPaging.olderPageLimit),
            reason: "load-older"
        )
        var applied = false
        while !applied {
            applied = store.prependOlderIfRevision(
                sessionID: key.storeSlot,
                older: page.messages,
                expectedRevision: store.revision(sessionID: key.storeSlot)
            )
        }
        rememberWindow(page, for: key.storeSlot, update: .replace)
        let result = store.messages(sessionID: key.storeSlot)
        setPublishedFrame(result, revision: store.revision(sessionID: key.storeSlot), for: key.storeSlot)
        setFingerprint(messageFingerprint(result), for: key.storeSlot)
        return result
    }

    /// Test/observability seam for the recovery/fault matrix.
    func syncHealth(sessionID: String, directory: String?) -> MessageSyncHealth {
        let key = sessionKey(sessionID: sessionID, directory: directory)
        lock.lock()
        defer { lock.unlock() }
        return healthByKey[key] ?? .healthy
    }

    func observeCatchUpChrome() -> AsyncStream<Bool> {
        catchUpChromeStream(slot: nil)
    }

    func observeCatchUpChrome(sessionID: String, directory: String?) -> AsyncStream<Bool> {
        catchUpChromeStream(slot: sessionKey(sessionID: sessionID, directory: directory).storeSlot)
    }

    private func catchUpChromeStream(slot: String?) -> AsyncStream<Bool> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            catchUpChromeSubscribers[id] = (slot, continuation)
            lock.unlock()
            publishCatchUpChrome()
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.catchUpChromeSubscribers.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    private func publishCatchUpChrome() {
        catchUpChromeQueue.async { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let subscribers = Array(self.catchUpChromeSubscribers.values)
            self.lock.unlock()
            for subscriber in subscribers {
                self.lock.lock()
                let value =
                    if let slot = subscriber.slot {
                        self.catchUpSlots.contains(slot)
                    } else {
                        !self.catchUpSlots.isEmpty
                    }
                self.lock.unlock()
                subscriber.continuation.yield(value)
            }
        }
    }

    private func setHealth(_ health: MessageSyncHealth, for key: MessageSessionKey) {
        lock.lock()
        healthByKey[key] = health
        if health.showsCatchUpChrome {
            catchUpSlots.insert(key.storeSlot)
        } else {
            catchUpSlots.remove(key.storeSlot)
        }
        lock.unlock()
        publishCatchUpChrome()
    }

    private func clearCatchUp(for key: MessageSessionKey, startedGeneration: Int) {
        lock.lock()
        let stillOwner = startedGenerationByKey[key] == startedGeneration
        if stillOwner {
            healthByKey.removeValue(forKey: key)
            catchUpSlots.remove(key.storeSlot)
        }
        lock.unlock()
        if stillOwner {
            publishCatchUpChrome()
        }
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

    private func observationStillCurrent(key: MessageSessionKey, startedGeneration: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return startedGenerationByKey[key] == startedGeneration
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
        // Register before the initial REST request. Bounded staging closes the
        // part-shell/delta loss window without an unbounded observation buffer.
        let staging = MessageEventStaging(
            capacity: eventStagingCapacity,
            byteCapacity: eventStagingByteCapacity
        )
        let cacheHandle = openMessageCacheHandle(for: key)
        let gapState = ObservationGapState(
            liveConnection: liveConnection,
            authority: gapAuthority(for: key)
        )
        let authority = gapState.authority
        let liveConnection = liveConnection
        defer { clearCatchUp(for: key, startedGeneration: startedGeneration) }
        func confirmCacheSyncIfAwaiting() {
            guard isAwaitingCacheSyncConfirm(for: key.storeSlot) else { return }
            setAwaitingCacheSyncConfirm(false, for: key.storeSlot)
            yield(.cacheSyncConfirmed)
        }
        func wakeGapEvaluation(reason: String) {
            staging.requestEvaluate(reason: reason)
        }
        // Subscribe before seed/REST so events that arrive during those awaits
        // are buffered. Lazy AsyncStream builders only register on first
        // `for await`, which can lose the producer race against fetchMessages.
        let liveEvents = eventStream.events()
        let producer = Task {
            for await event in liveEvents {
                staging.enqueue(event, sessionID: key.sessionID)
            }
            staging.finish()
        }
        var snapshotWatcher: Task<Void, Never>?
        let worldWatcher = Task { [worldSignals, weak self] in
            guard let self else { return }
            for await signal in worldSignals.signals() {
                if signal.marksStale {
                    authority.invalidate(liveConnection.snapshot)
                    guard self.publishedFrame(for: key.storeSlot) != nil else { continue }
                    let current = self.syncHealth(
                        sessionID: key.sessionID,
                        directory: key.directory.isEmpty ? nil : key.directory
                    )
                    guard current.isHealthy else { continue }
                    staging.enterStale(.transportUnavailable)
                    self.setHealth(.stale(.transportUnavailable), for: key)
                } else if let reason = signal.catchUpReason {
                    wakeGapEvaluation(reason: reason)
                }
            }
        }
        let invalidationWatcher = Task { [invalidationBus] in
            for await invalidation in invalidationBus.events() {
                if let slot = invalidation.key, slot != key.storeSlot { continue }
                authority.invalidate(liveConnection.snapshot)
                wakeGapEvaluation(reason: invalidation.reason)
            }
        }
        defer {
            producer.cancel()
            snapshotWatcher?.cancel()
            worldWatcher.cancel()
            invalidationWatcher.cancel()
            staging.finish()
            if observationStillCurrent(key: key, startedGeneration: startedGeneration) {
                closeMessageCacheHandle(for: key)
            }
        }

        let publishMessages: @Sendable ([Message], Bool) -> Void = { [weak self] messages, isFromCache in
            guard let self else { return }
            guard self.observationStillCurrent(key: key, startedGeneration: startedGeneration) else { return }
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
            self.lock.lock()
            self.lastPublishedProfileID = key.connectionProfileID
            self.lock.unlock()
            yield(.messages(messages, isFromCache: isFromCache))
            // Fingerprint remains the poll / REST authority comparison only.
            self.setFingerprint(self.messageFingerprint(messages), for: key.storeSlot)
            MessagePipelineProbe.emit(.displayFramePublished)
        }

        snapshotWatcher = Task { [liveConnection] in
            for await _ in liveConnection.snapshotUpdates() {
                wakeGapEvaluation(reason: StoreRefreshReason.connectionSnapshot)
            }
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
        let begun = authority.captureBegin(liveConnection.snapshot)
        let initialStarted = Date()
        do {
            SessionPerformanceProbe.emit("history_request_started")
            let page = try await fetchTailPage(
                sessionID: key.sessionID,
                directory: key.directory.isEmpty ? nil : key.directory,
                reason: StoreRefreshReason.initialSubscribe
            )
            rememberTailWindow(page, for: key.storeSlot)
            SessionPerformanceProbe.emit("history_request_finished")
            await applyQueuedIncrementalEvents(
                staging.takeQueuedIncrementalEvents(),
                key: key,
                publishMessages: { publishMessages($0, false) }
            )
            if staging.needsAuthoritativeRecovery {
                authority.fail(begun)
                OpenCodeLog.messageContent.info(
                    "eventStagingRejectStaleAuthority session=\(key.sessionID) highWater=\(staging.highWater)"
                )
                await performClosedLoopRecovery(
                    key: key,
                    staging: staging,
                    reason: .overflow,
                    publishMessages: { publishMessages($0, false) },
                    gapState: gapState
                )
            } else {
                let applied =
                    store.revision(sessionID: key.storeSlot) == expectedRevision &&
                    applyTailMessages(
                        sessionID: key.storeSlot,
                        tail: page.messages,
                        expectedRevision: expectedRevision,
                        mergeExisting: false
                    )
                let merged = store.messages(sessionID: key.storeSlot)
                let fingerprint = messageFingerprint(merged)
                setPublishedFrame(merged, revision: store.revision(sessionID: key.storeSlot), for: key.storeSlot)
                setFingerprint(fingerprint, for: key.storeSlot)
                if applied {
                    authority.acknowledge(begun)
                } else {
                    authority.fail(begun)
                }
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
                if authority.needsReconcile(liveConnection.snapshot) || !applied {
                    await reconcileGap(
                        key: key,
                        reason: StoreRefreshReason.streamReconnected,
                        staging: staging,
                        publishMessages: { publishMessages($0, false) },
                        confirmCacheSync: confirmCacheSyncIfAwaiting,
                        gapState: gapState
                    )
                } else {
                    setHealth(.healthy, for: key)
                }
            }
        } catch {
            authority.fail(begun)
            SessionPerformanceProbe.emit("history_request_finished")
            if staging.needsAuthoritativeRecovery {
                await performClosedLoopRecovery(
                    key: key,
                    staging: staging,
                    reason: .overflow,
                    publishMessages: { publishMessages($0, false) },
                    gapState: gapState
                )
            } else if fingerprint(for: key.storeSlot) != nil {
                setHealth(.recovering(.reloadFailed), for: key)
                await performClosedLoopRecovery(
                    key: key,
                    staging: staging,
                    reason: .reloadFailed,
                    publishMessages: { publishMessages($0, false) },
                    gapState: gapState
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
            publishMessages: { publishMessages($0, false) },
            confirmCacheSync: confirmCacheSyncIfAwaiting,
            gapState: gapState
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
        reason: MessageSyncRecoveryReason,
        fetchReason: String = "overflow-recovery",
        publishMessages: @escaping @Sendable ([Message]) -> Void,
        confirmCacheSync: @escaping @Sendable () -> Void = {},
        gapState: ObservationGapState
    ) async {
        guard observationStillCurrent(key: key) else {
            gapState.authority.fail(gapState.recoveryGeneration)
            return
        }
        if !staging.needsAuthoritativeRecovery {
            staging.enterRecovering(reason)
        }
        setHealth(staging.currentHealth, for: key)
        var backoffMs = MessageSyncRecoveryPolicy.initialBackoffMs

        while !Task.isCancelled, observationStillCurrent(key: key) {
            await applyQueuedIncrementalEvents(
                staging.takeQueuedIncrementalEvents(),
                key: key,
                publishMessages: publishMessages
            )
            staging.beginAuthorityWindow()
            let fingerprintBefore = fingerprint(for: key.storeSlot)
            let result = await fullReloadResult(
                key: key,
                reason: fetchReason,
                mergeExisting: true,
                beforeCommit: {
                    await self.applyQueuedIncrementalEvents(
                        staging.takeQueuedIncrementalEvents(),
                        key: key,
                        publishMessages: publishMessages
                    )
                }
            )
            switch result {
            case .accepted(let messages):
                let becameHealthy = staging.completeAuthorityWindow(.accepted)
                setHealth(staging.currentHealth, for: key)
                let published = observationStillCurrent(key: key)
                if published {
                    if fingerprintBefore != messageFingerprint(messages) {
                        publishMessages(messages)
                    } else {
                        confirmCacheSync()
                    }
                }
                let newerUnpaid = acknowledgeExactGeneration(
                    gapState.authority,
                    begun: gapState.recoveryGeneration,
                    snapshot: gapState.liveConnection.snapshot
                )
                if !newerUnpaid {
                    if !becameHealthy {
                        setHealth(.healthy, for: key)
                    }
                    staging.clearRecoveringLatch()
                    if published {
                        OpenCodeLog.messageContent.info(
                            "eventStagingRecovery session=\(key.sessionID) highWater=\(staging.highWater)"
                        )
                    }
                    return
                }
                if let next = gapState.authority.tryBegin(gapState.liveConnection.snapshot) {
                    gapState.recoveryGeneration = next
                    continue
                }
            case .discarded:
                _ = staging.completeAuthorityWindow(.discarded)
                setHealth(staging.currentHealth, for: key)
            case .failure(let error):
                _ = staging.completeAuthorityWindow(.failed)
                setHealth(staging.currentHealth, for: key)
                if isMissingSessionError(error) {
                    markTerminal(key: key)
                    staging.clearRecoveringLatch()
                    setHealth(.healthy, for: key)
                    OpenCodeLog.messageContent.error(
                        "fullReload missing session=\(key.sessionID) stopping recovery"
                    )
                    return
                }
                // Keep last published frame; do not emit LoadFailed.
            }

            MessagePipelineProbe.emit(.recoveryRetry)
            await recoveryDelay(backoffMs)
            if Task.isCancelled {
                gapState.authority.fail(gapState.recoveryGeneration)
                return
            }
            backoffMs = min(backoffMs * 2, MessageSyncRecoveryPolicy.maxBackoffMs)
        }
        gapState.authority.fail(gapState.recoveryGeneration)
    }

    private enum ReloadResult {
        case accepted([Message])
        case discarded([Message])
        case failure(Error)
    }

    private func fetchMessages(
        sessionID: String,
        directory: String?,
        reason: String
    ) async throws -> [Message] {
        try await fetchTailPage(sessionID: sessionID, directory: directory, reason: reason).messages
    }

    private func fetchHistoryPage(
        sessionID: String,
        directory: String?,
        query: MessageHistoryQuery,
        reason: String
    ) async throws -> MessageHistoryPage {
        lock.lock()
        restAttempts.append(reason)
        lock.unlock()
        return try await listMessagePage(sessionID, directory, query)
    }

    private func fetchTailPage(
        sessionID: String,
        directory: String?,
        reason: String
    ) async throws -> MessageHistoryPage {
        var page = try await fetchHistoryPage(
            sessionID: sessionID,
            directory: directory,
            query: .newest(limit: MessageHistoryPaging.tailLimit),
            reason: reason
        )
        var backfills = 0
        while MessageHistoryMerge.beginsMidTurn(page.messages),
              page.hasOlder,
              let cursor = page.olderCursor,
              backfills < MessageHistoryPaging.maxSplitTurnBackfillPages
        {
            let older = try await fetchHistoryPage(
                sessionID: sessionID,
                directory: directory,
                query: .older(cursor: cursor, limit: MessageHistoryPaging.splitTurnPageLimit),
                reason: "\(reason)-split-turn"
            )
            page = MessageHistoryPage(
                messages: MessageHistoryMerge.applyOlder(local: page.messages, older: older.messages),
                olderCursor: older.olderCursor,
                hasOlder: older.hasOlder
            )
            backfills += 1
        }
        return page
    }

    private enum HistoryWindowUpdate {
        case replace
        case preserveExisting
    }

    private func rememberTailWindow(_ page: MessageHistoryPage, for storeSlot: String) {
        let local = store.messages(sessionID: storeSlot)
        let isReset = !page.messages.isEmpty && !MessageHistoryMerge.hasOverlap(local: local, tail: page.messages)
        rememberWindow(page, for: storeSlot, update: isReset ? .replace : .preserveExisting)
    }

    private func rememberWindow(
        _ page: MessageHistoryPage,
        for storeSlot: String,
        update: HistoryWindowUpdate
    ) {
        lock.lock()
        defer { lock.unlock() }
        if update == .preserveExisting, hasOlderBySlot[storeSlot] != nil {
            return
        }
        if let cursor = page.olderCursor {
            olderCursorBySlot[storeSlot] = cursor
        } else {
            olderCursorBySlot.removeValue(forKey: storeSlot)
        }
        hasOlderBySlot[storeSlot] = page.hasOlder
    }

    private func applyTailMessages(
        sessionID: String,
        tail: [Message],
        expectedRevision: UInt64,
        mergeExisting: Bool
    ) -> Bool {
        let local = store.messages(sessionID: sessionID)
        guard MessageHistoryMerge.shouldAcceptTail(remote: tail, local: local) else {
            return false
        }
        let merged = MessageHistoryMerge.applyTail(local: local, tail: tail)
        if mergeExisting {
            return store.mergeAuthoritativeIfRevision(
                sessionID: sessionID,
                messages: merged,
                expectedRevision: expectedRevision
            )
        }
        return store.replaceAllIfRevision(
            sessionID: sessionID,
            messages: merged,
            expectedRevision: expectedRevision
        )
    }

    func gapAuthority(sessionID: String, directory: String?) -> GapAuthority {
        gapAuthority(for: sessionKey(sessionID: sessionID, directory: directory))
    }

    /// Pays only the generation this REST began. A newer gap that opened
    /// during the GET stays unpaid so `needsReconcile` can start a sequential
    /// follow-up instead of pinning inFlight to the old revision.
    private func acknowledgeExactGeneration(
        _ authority: GapAuthority,
        begun: UInt64,
        snapshot: LiveConnectionSnapshot
    ) -> Bool {
        authority.acknowledge(begun)
        return authority.needsReconcile(snapshot)
    }

    private func gapAuthority(for key: MessageSessionKey) -> GapAuthority {
        lock.lock()
        defer { lock.unlock() }
        if let existing = authorities[key] {
            return existing
        }
        let created = GapAuthority()
        authorities[key] = created
        return created
    }

    private func fullReloadResult(
        key: MessageSessionKey,
        reason: String,
        mergeExisting: Bool = false,
        beforeCommit: @escaping @Sendable () async -> Void = {}
    ) async -> ReloadResult {
        SessionPerformanceProbe.emit("history_request_started")
        let expectedRevision = store.revision(sessionID: key.storeSlot)
        let directory = key.directory.isEmpty ? nil : key.directory
        let started = Date()
        do {
            let page = try await fetchTailPage(
                sessionID: key.sessionID,
                directory: directory,
                reason: reason
            )
            rememberTailWindow(page, for: key.storeSlot)
            await beforeCommit()
            let applied =
                store.revision(sessionID: key.storeSlot) == expectedRevision &&
                applyTailMessages(
                    sessionID: key.storeSlot,
                    tail: page.messages,
                    expectedRevision: expectedRevision,
                    mergeExisting: mergeExisting
                )
            let merged = store.messages(sessionID: key.storeSlot)
            setPublishedFrame(merged, revision: store.revision(sessionID: key.storeSlot), for: key.storeSlot)
            setFingerprint(messageFingerprint(merged), for: key.storeSlot)
            SessionPerformanceProbe.emit("history_request_finished")
            MessagePipelineProbe.emit(.reducerCommitted)
            DiagnosticLog.info(
                .chat,
                "fullReload session=\(key.sessionID) reason=\(reason) count=\(merged.count) ms=\(elapsedMs(since: started))"
            )
            return applied ? .accepted(merged) : .discarded(merged)
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
        publishMessages: @escaping @Sendable ([Message]) -> Void,
        gapState: ObservationGapState
    ) async {
        if let staging {
            staging.beginAuthorityWindow()
        }
        switch await fullReloadResult(key: key, reason: reason) {
        case .accepted(let messages):
            if let staging {
                let healthy = staging.completeAuthorityWindow(.accepted)
                setHealth(staging.currentHealth, for: key)
                publishMessages(messages)
                if !healthy {
                    await performClosedLoopRecovery(
                        key: key,
                        staging: staging,
                        reason: .racedAuthority,
                        publishMessages: publishMessages,
                        gapState: gapState
                    )
                }
            } else {
                publishMessages(messages)
                setHealth(.healthy, for: key)
            }
        case .discarded(let messages):
            if let staging {
                _ = staging.completeAuthorityWindow(.discarded)
                setHealth(staging.currentHealth, for: key)
                publishMessages(messages)
                await performClosedLoopRecovery(
                    key: key,
                    staging: staging,
                    reason: .racedAuthority,
                    publishMessages: publishMessages,
                    gapState: gapState
                )
            }
        case .failure:
            if let staging {
                _ = staging.completeAuthorityWindow(.failed)
                setHealth(staging.currentHealth, for: key)
                await performClosedLoopRecovery(
                    key: key,
                    staging: staging,
                    reason: .reloadFailed,
                    publishMessages: publishMessages,
                    gapState: gapState
                )
            }
            // else: retain last good; no LoadFailed
        }
    }

    private func reconcileGap(
        key: MessageSessionKey,
        reason: String,
        staging: MessageEventStaging,
        publishMessages: @escaping @Sendable ([Message]) -> Void,
        confirmCacheSync: @escaping @Sendable () -> Void,
        gapState: ObservationGapState
    ) async {
        guard observationStillCurrent(key: key) else { return }
        guard gapState.shouldEvaluate(
            lastAssistantNeedsAuthority: SessionTranscriptAuthority.lastAssistantNeedsAuthority(
                store.messages(sessionID: key.storeSlot)
            )
        ) else {
            if gapState.authority.isInFlight() {
                return
            }
            if case .recovering(.reconnect) = staging.currentHealth {
                staging.clearRecoveringLatch()
                setHealth(.healthy, for: key)
                confirmCacheSync()
                return
            }
            if staging.needsAuthoritativeRecovery {
                return
            }
            DiagnosticLog.info(.chat, "fullReload skip session=\(key.sessionID) reason=\(reason)")
            confirmCacheSync()
            return
        }
        let snapshot = gapState.liveConnection.snapshot
        guard canCatchUp(occupancy: worldSignals.occupancy, snapshot: snapshot) else {
            if !snapshot.isReachable {
                guard publishedFrame(for: key.storeSlot) != nil else { return }
                store.finishStreamingText(sessionID: key.storeSlot)
                let current = syncHealth(
                    sessionID: key.sessionID,
                    directory: key.directory.isEmpty ? nil : key.directory
                )
                guard current.isHealthy else { return }
                staging.enterStale(.transportUnavailable)
                setHealth(.stale(.transportUnavailable), for: key)
            }
            return
        }
        guard gapState.begin(
            lastAssistantNeedsAuthority: SessionTranscriptAuthority.lastAssistantNeedsAuthority(
                store.messages(sessionID: key.storeSlot)
            )
        ) != nil else { return }
        staging.enterRecovering(.reconnect, wakeConsumer: false)
        setHealth(staging.currentHealth, for: key)
        await performClosedLoopRecovery(
            key: key,
            staging: staging,
            reason: .reconnect,
            fetchReason: reason,
            publishMessages: publishMessages,
            confirmCacheSync: confirmCacheSync,
            gapState: gapState
        )
    }

    private func runEventRefreshLoop(
        key: MessageSessionKey,
        staging: MessageEventStaging,
        publishMessages: @escaping @Sendable ([Message]) -> Void,
        confirmCacheSync: @escaping @Sendable () -> Void,
        gapState: ObservationGapState
    ) async {
        while let item = await staging.dequeue() {
            guard observationStillCurrent(key: key) else { return }
            switch item {
            case .overflow, .recoveryRequired:
                await performClosedLoopRecovery(
                    key: key,
                    staging: staging,
                    reason: {
                        if case .recovering(let reason) = staging.currentHealth { return reason }
                        return .overflow
                    }(),
                    publishMessages: publishMessages,
                    confirmCacheSync: confirmCacheSync,
                    gapState: gapState
                )
                continue
            case .evaluate(let reason):
                await reconcileGap(
                    key: key,
                    reason: reason,
                    staging: staging,
                    publishMessages: publishMessages,
                    confirmCacheSync: confirmCacheSync,
                    gapState: gapState
                )
                continue
            case .event(let event, _):
                if staging.needsAuthoritativeRecovery {
                    await performClosedLoopRecovery(
                        key: key,
                        staging: staging,
                        reason: {
                            if case .recovering(let reason) = staging.currentHealth { return reason }
                            return .overflow
                        }(),
                        publishMessages: publishMessages,
                        confirmCacheSync: confirmCacheSync,
                        gapState: gapState
                    )
                    continue
                }
                MessagePipelineProbe.emit(.eventAccepted)
                await handleStagedEvent(
                    event: event,
                    key: key,
                    staging: staging,
                    publishMessages: publishMessages,
                    confirmCacheSync: confirmCacheSync,
                    gapState: gapState
                )
            }
        }
    }

    private func handleStagedEvent(
        event: OpencodeEvent,
        key: MessageSessionKey,
        staging: MessageEventStaging,
        publishMessages: @escaping @Sendable ([Message]) -> Void,
        confirmCacheSync: @escaping @Sendable () -> Void,
        gapState: ObservationGapState
    ) async {
        if event.type == "sse.parse.failed" {
            guard publishedFrame(for: key.storeSlot) != nil else { return }
            if staging.needsAuthoritativeRecovery {
                staging.noteRelevantEventWhileRecovering()
                return
            }
            gapState.authority.invalidate(gapState.liveConnection.snapshot)
            await reconcileGap(
                key: key,
                reason: StoreRefreshReason.streamReconnected,
                staging: staging,
                publishMessages: publishMessages,
                confirmCacheSync: confirmCacheSync,
                gapState: gapState
            )
            return
        }

        if event.type == "stream.reconnected" {
            await reconcileGap(
                key: key,
                reason: StoreRefreshReason.streamReconnected,
                staging: staging,
                publishMessages: publishMessages,
                confirmCacheSync: confirmCacheSync,
                gapState: gapState
            )
            return
        }

        if event.type == "server.connected" {
            await reconcileGap(
                key: key,
                reason: StoreRefreshReason.serverConnected,
                staging: staging,
                publishMessages: publishMessages,
                confirmCacheSync: confirmCacheSync,
                gapState: gapState
            )
            return
        }

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
                    publishMessages: publishMessages,
                    gapState: gapState
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
                    publishMessages: publishMessages,
                    gapState: gapState
                )
                return
            }
            handleMessagePartRemoved(event: event, key: key, publishMessages: publishMessages)
        case "session.idle":
            // TUI v2 only flips footer idle on execution.* — it does not
            // REST-reload the transcript. Finalize local streaming parts.
            store.finishStreamingText(sessionID: key.storeSlot)
            let recovered = store.messages(sessionID: key.storeSlot)
            if markChanged(key: key, messages: recovered) {
                publishMessages(recovered)
            }
            guard SessionTranscriptAuthority.lastAssistantNeedsAuthority(recovered) else {
                setHealth(.healthy, for: key)
                return
            }
            gapState.invalidateIfCurrentObligationPaid()
            await reconcileGap(
                key: key,
                reason: "session.idle",
                staging: staging,
                publishMessages: publishMessages,
                confirmCacheSync: confirmCacheSync,
                gapState: gapState
            )
        default:
            return
        }
    }

    private func applyQueuedIncrementalEvents(
        _ events: [OpencodeEvent],
        key: MessageSessionKey,
        publishMessages: @escaping @Sendable ([Message]) -> Void
    ) async {
        for event in events {
            switch event.type {
            case "message.updated":
                handleMessageUpdated(event: event, key: key, publishMessages: publishMessages)
            case "message.part.updated":
                handleMessagePartUpdated(event: event, key: key, publishMessages: publishMessages)
            case "message.part.delta":
                handleMessagePartDelta(event: event, key: key, publishMessages: publishMessages)
            case "message.removed":
                handleMessageRemoved(event: event, key: key, publishMessages: publishMessages)
            case "message.part.removed":
                handleMessagePartRemoved(event: event, key: key, publishMessages: publishMessages)
            default:
                break
            }
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
        publishMessages: @escaping @Sendable ([Message]) -> Void,
        confirmCacheSync: @escaping @Sendable () -> Void
    ) async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(pollIntervalMs))
            guard !Task.isCancelled else { return }
            guard observationStillCurrent(key: key) else { return }
            if staging.needsAuthoritativeRecovery { continue }
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
                let page = try await fetchTailPage(
                    sessionID: key.sessionID,
                    directory: directory,
                    reason: "poll"
                )
                rememberTailWindow(page, for: key.storeSlot)
                let local = store.messages(sessionID: key.storeSlot)
                if streaming {
                    guard MessageHistoryMerge.shouldAcceptTail(remote: page.messages, local: local) else {
                        continue
                    }
                    DiagnosticLog.info(.chat, "streaming stall catch-up session=\(key.sessionID)")
                }
                let merged = MessageHistoryMerge.applyTail(local: local, tail: page.messages)
                let fp = messageFingerprint(merged)
                if fp != fingerprint(for: key.storeSlot) {
                    guard applyTailMessages(
                        sessionID: key.storeSlot,
                        tail: page.messages,
                        expectedRevision: expectedRevision,
                        mergeExisting: false
                    ) else { continue }
                    let applied = store.messages(sessionID: key.storeSlot)
                    noteMessageActivity(for: key.storeSlot)
                    publishMessages(applied)
                    continue
                }
                guard isAwaitingCacheSyncConfirm(for: key.storeSlot) else { continue }
                // Initial REST may still be hung after reconnect; poll already
                // agrees with the cache — clear Syncing without a second paint.
                openMessageCacheHandle(for: key).note(messages: merged)
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
private nonisolated final class ObservationGapState: @unchecked Sendable {
    let authority: GapAuthority
    let liveConnection: any LiveConnection
    private let lock = NSLock()
    private var _recoveryGeneration: UInt64 = 0

    init(liveConnection: any LiveConnection, authority: GapAuthority) {
        self.liveConnection = liveConnection
        self.authority = authority
    }

    var recoveryGeneration: UInt64 {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _recoveryGeneration
        }
        set {
            lock.lock()
            _recoveryGeneration = newValue
            lock.unlock()
        }
    }

    func shouldEvaluate(lastAssistantNeedsAuthority _: Bool) -> Bool {
        authority.needsReconcile(liveConnection.snapshot)
    }

    func begin(lastAssistantNeedsAuthority _: Bool) -> UInt64? {
        guard liveConnection.snapshot.isReachable else { return nil }
        guard let generation = authority.tryBegin(liveConnection.snapshot) else { return nil }
        recoveryGeneration = generation
        return generation
    }

    func invalidateIfCurrentObligationPaid() {
        let snapshot = liveConnection.snapshot
        if authority.obligation(snapshot) <= authority.acknowledgedGeneration() {
            authority.invalidate(snapshot)
        }
    }
}
