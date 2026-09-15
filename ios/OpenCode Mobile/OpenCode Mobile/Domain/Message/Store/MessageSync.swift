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
    private let eventStagingCapacity: Int
    private let eventStagingByteCapacity: Int
    private let connectionProfileIDProvider: @Sendable () -> String
    private let nowMs: @Sendable () -> Int64
    private let sessionCache: any SessionCache
    private let syncCoordinator: any SyncCoordinator
    private let lock = NSLock()

    private var observationCache: [MessageSessionKey: SharedLiveObservation<MessageListUpdate>] = [:]
    private var lastIdentity: [String: MessageFrameIdentity] = [:]
    private var lastPublishedFrame: [String: [Message]] = [:]
    private var lastPublishedRevision: [String: UInt64] = [:]
    private var healthByKey: [MessageSessionKey: MessageSyncHealth] = [:]
    private var catchUpSlots: Set<String> = []
    private var catchUpChromeSubscribers: [UUID: (slot: String?, continuation: AsyncStream<Bool>.Continuation)] = [:]
    private let catchUpChromeQueue = DispatchQueue(label: "dev.opencode.mobile.message-sync.catch-up-chrome")
    private var messageCacheHandles: [MessageSessionKey: any SessionMessageCacheHandle] = [:]
    /// Store slots that painted from cache and still need network confirmation.
    private var awaitingCacheSyncConfirm: Set<String> = []
    /// Live send/pull `refreshMessages` hops. Coordinator still goes stale→syncing;
    /// catch-up chrome stays off (OPE-253).
    private var quietRefreshSlots: Set<String> = []
    private var lastCoordinatorState: [MessageSessionKey: ProjectionState] = [:]
    private var observationGeneration = 0
    private var terminalSessionKeys: Set<MessageSessionKey> = []
    private var startedGenerationByKey: [MessageSessionKey: Int] = [:]
    private var lastMessageEventAtMs: [String: Int64] = [:]
    private var lastPublishedProfileID: String?
    private var restAttempts: [String] = []
    private var lastLoadErrorBySlot: [String: Error] = [:]
    private var olderCursorBySlot: [String: String] = [:]
    private var hasOlderBySlot: [String: Bool] = [:]
    private var olderLoadInFlight: Set<String> = []

    init(
        listMessages: @escaping MessageListFetcher,
        listMessagePage: MessagePageFetcher? = nil,
        store: SessionMessageEngine,
        eventStream: any ForegroundEventStream,
        worldSignals: any WorldSignals = SilentWorldSignals.shared,
        pollIntervalMs _: Int = LiveStoreRefreshPolicy.workingPollIntervalMs,
        eventStagingCapacity: Int = messageEventStagingCapacity,
        eventStagingByteCapacity: Int = messageEventStagingByteCapacity,
        connectionProfileIDProvider: @escaping @Sendable () -> String = { "local" },
        recoveryDelay _: @escaping @Sendable (Int) async -> Void = { ms in
            try? await Task.sleep(for: .milliseconds(ms))
        },
        nowMs: @escaping @Sendable () -> Int64 = {
            Int64(Date().timeIntervalSince1970 * 1000)
        },
        sessionCache: any SessionCache = NoOpSessionCache.shared,
        syncCoordinator: (any SyncCoordinator)? = nil
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
        self.eventStagingCapacity = eventStagingCapacity
        self.eventStagingByteCapacity = eventStagingByteCapacity
        self.connectionProfileIDProvider = connectionProfileIDProvider
        self.nowMs = nowMs
        self.sessionCache = sessionCache
        self.syncCoordinator = syncCoordinator ?? DefaultSyncCoordinator(
            connection: eventStream.asLiveConnection(),
            worldSignals: worldSignals,
            eventStream: eventStream
        )
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
        sessionCache: any SessionCache = NoOpSessionCache.shared,
        syncCoordinator: (any SyncCoordinator)? = nil
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
            sessionCache: sessionCache,
            syncCoordinator: syncCoordinator
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
        let projection = transcriptKey(key)
        lock.lock()
        let observing = observationCache[key] != nil
        lock.unlock()
        if observing {
            setQuietRefresh(true, for: key.storeSlot)
            defer { finishQuietRefresh(for: key) }
            syncCoordinator.invalidate(projection)
            await syncCoordinator.ensureFresh(projection)
            lock.lock()
            let error = lastLoadErrorBySlot[key.storeSlot]
            lock.unlock()
            if let error { throw error }
        } else {
            switch await fullReloadResult(key: key, reason: reason) {
            case .accepted, .discarded:
                await backfillSplitTurnIfNeeded(key: key, publishMessages: { _ in })
            case .failure(let error):
                throw error
            }
        }
        if worldSignals.occupancy != .active, publishedFrame(for: key.storeSlot) != nil,
           !canCatchUp(occupancy: worldSignals.occupancy, snapshot: liveConnection.snapshot) {
            setHealth(.stale(.transportUnavailable), for: key)
        }
        return store.messages(sessionID: key.storeSlot)
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
        let key = sessionKey(sessionID: sessionID, directory: directory)
        _ = reason
        syncCoordinator.invalidate(transcriptKey(key))
    }

    func clearCache() {
        lock.lock()
        observationGeneration += 1
        startedGenerationByKey.removeAll()
        let stale = Array(observationCache.values)
        observationCache.removeAll()
        lastIdentity.removeAll()
        lastPublishedFrame.removeAll()
        lastPublishedRevision.removeAll()
        healthByKey.removeAll()
        catchUpSlots.removeAll()
        awaitingCacheSyncConfirm.removeAll()
        lastPublishedProfileID = nil
        olderCursorBySlot.removeAll()
        hasOlderBySlot.removeAll()
        olderLoadInFlight.removeAll()
        lock.unlock()
        stale.forEach { $0.finish() }
        store.clear()
        syncCoordinator.invalidate(nil)
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
        setIdentity(
            MessageFrameIdentity.of(messages: result, engineRevision: store.revision(sessionID: key.storeSlot)),
            for: key.storeSlot
        )
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

    private func frameIdentity(for storeSlot: String) -> MessageFrameIdentity? {
        lock.lock()
        defer { lock.unlock() }
        return lastIdentity[storeSlot]
    }

    private func setIdentity(_ value: MessageFrameIdentity, for storeSlot: String) {
        lock.lock()
        lastIdentity[storeSlot] = value
        lock.unlock()
    }

    /// Reconnect and cache-confirm raise Syncing. A live send/pull
    /// `refreshMessages` also goes stale→syncing→fresh; keep that hop
    /// quiet so the pill does not flash (OPE-253).
    private func applyCoordinatorHealth(_ state: ProjectionState, for key: MessageSessionKey) {
        lock.lock()
        lastCoordinatorState[key] = state
        let quiet = quietRefreshSlots.contains(key.storeSlot)
        lock.unlock()
        if quiet, state != .fresh, !isAwaitingCacheSyncConfirm(for: key.storeSlot) {
            return
        }
        let catchingUp = canCatchUp(
            occupancy: worldSignals.occupancy,
            snapshot: liveConnection.snapshot
        )
        switch state {
        case .fresh:
            if isAwaitingCacheSyncConfirm(for: key.storeSlot) {
                setHealth(.recovering(.reconnect), for: key)
            } else {
                setHealth(.healthy, for: key)
            }
        case .syncing:
            setHealth(.recovering(.reconnect), for: key)
        case .stale:
            if catchingUp {
                setHealth(.recovering(.reconnect), for: key)
            } else if publishedFrame(for: key.storeSlot) != nil {
                setHealth(.stale(.transportUnavailable), for: key)
            } else {
                setHealth(.recovering(.reconnect), for: key)
            }
        }
    }

    private func setQuietRefresh(_ quiet: Bool, for storeSlot: String) {
        lock.lock()
        if quiet {
            quietRefreshSlots.insert(storeSlot)
        } else {
            quietRefreshSlots.remove(storeSlot)
        }
        lock.unlock()
    }

    private func finishQuietRefresh(for key: MessageSessionKey) {
        setQuietRefresh(false, for: key.storeSlot)
        lock.lock()
        let pending = lastCoordinatorState[key]
        lock.unlock()
        if let pending {
            applyCoordinatorHealth(pending, for: key)
        }
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

    private func transcriptKey(_ key: MessageSessionKey) -> ProjectionKey {
        .transcript(directory: key.directory, sessionID: key.sessionID)
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

    private func runMessageLoop(
        key: MessageSessionKey,
        yield: @escaping @Sendable (MessageListUpdate) -> Void
    ) async {
        let startedGeneration = nextObservationGeneration()
        registerObservation(key: key, generation: startedGeneration)
        noteMessageActivity(for: key.storeSlot)
        let staging = MessageEventStaging(
            capacity: eventStagingCapacity,
            byteCapacity: eventStagingByteCapacity
        )
        let cacheHandle = openMessageCacheHandle(for: key)
        let projection = transcriptKey(key)
        defer { clearCatchUp(for: key, startedGeneration: startedGeneration) }

        func confirmCacheSyncIfAwaiting() {
            guard isAwaitingCacheSyncConfirm(for: key.storeSlot) else { return }
            setAwaitingCacheSyncConfirm(false, for: key.storeSlot)
            yield(.cacheSyncConfirmed)
        }

        let liveEvents = eventStream.events()
        let producer = Task {
            for await event in liveEvents {
                staging.enqueue(event, sessionID: key.sessionID)
            }
            staging.finish()
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
                self.setHealth(.recovering(.reconnect), for: key)
            } else {
                self.setAwaitingCacheSyncConfirm(false, for: key.storeSlot)
                cacheHandle.note(messages: messages)
            }
            self.lock.lock()
            self.lastPublishedProfileID = key.connectionProfileID
            self.lock.unlock()
            yield(.messages(messages, isFromCache: isFromCache))
            self.setIdentity(
                MessageFrameIdentity.of(
                    messages: messages,
                    engineRevision: self.store.revision(sessionID: key.storeSlot)
                ),
                for: key.storeSlot
            )
            MessagePipelineProbe.emit(.displayFramePublished)
        }

        @Sendable func loadTail() async throws {
            let generation = syncCoordinator.profileGeneration
            staging.beginAuthorityWindow()
            do {
                let identityBeforeReload = frameIdentity(for: key.storeSlot)
                let expectedRevision = store.revision(sessionID: key.storeSlot)
                let started = Date()
                SessionPerformanceProbe.emit("history_request_started")
                let page = try await fetchTailPage(
                    sessionID: key.sessionID,
                    directory: key.directory.isEmpty ? nil : key.directory,
                    reason: StoreRefreshReason.initialSubscribe
                )
                guard syncCoordinator.profileGeneration == generation else {
                    _ = staging.completeAuthorityWindow(.accepted)
                    return
                }
                rememberTailWindow(page, for: key.storeSlot)
                SessionPerformanceProbe.emit("history_request_finished")

                let awaitingCache = isAwaitingCacheSyncConfirm(for: key.storeSlot)
                if awaitingCache, !staging.needsAuthoritativeRecovery {
                    let cached = store.messages(sessionID: key.storeSlot)
                    let cachedTail = Array(cached.suffix(page.messages.count))
                    if MessageTailIdentity.matches(cached: cachedTail, fetched: page.messages) {
                        await applyQueuedIncrementalEvents(
                            staging.takeQueuedIncrementalEvents(),
                            key: key,
                            publishMessages: { publishMessages($0, false) }
                        )
                        cacheHandle.note(messages: store.messages(sessionID: key.storeSlot))
                        setAwaitingCacheSyncConfirm(false, for: key.storeSlot)
                        yield(.cacheSyncConfirmed)
                        setHealth(.healthy, for: key)
                        DiagnosticLog.info(
                            .chat,
                            "fullReload session=\(key.sessionID) reason=\(StoreRefreshReason.initialSubscribe) count=\(cached.count) ms=\(elapsedMs(since: started)) confirm=tail-identity"
                        )
                        await backfillSplitTurnIfNeeded(key: key, publishMessages: { publishMessages($0, false) })
                        _ = staging.completeAuthorityWindow(.accepted)
                        lock.lock()
                        lastLoadErrorBySlot.removeValue(forKey: key.storeSlot)
                        lock.unlock()
                        return
                    }
                }

                await applyQueuedIncrementalEvents(
                    staging.takeQueuedIncrementalEvents(),
                    key: key,
                    publishMessages: { publishMessages($0, false) }
                )
                guard syncCoordinator.profileGeneration == generation else {
                    _ = staging.completeAuthorityWindow(.accepted)
                    return
                }
                let overflowed = staging.needsAuthoritativeRecovery
                if overflowed {
                    OpenCodeLog.messageContent.info(
                        "eventStagingRejectStaleAuthority session=\(key.sessionID) highWater=\(staging.highWater)"
                    )
                    for _ in 0..<8 {
                        if applyTailMessages(
                            sessionID: key.storeSlot,
                            tail: page.messages,
                            expectedRevision: store.revision(sessionID: key.storeSlot),
                            mergeExisting: false
                        ) {
                            break
                        }
                    }
                } else {
                    _ = store.revision(sessionID: key.storeSlot) == expectedRevision &&
                        applyTailMessages(
                            sessionID: key.storeSlot,
                            tail: page.messages,
                            expectedRevision: expectedRevision,
                            mergeExisting: identityBeforeReload != nil
                        )
                }
                let merged = store.messages(sessionID: key.storeSlot)
                let nextIdentity = MessageFrameIdentity.of(
                    messages: merged,
                    engineRevision: store.revision(sessionID: key.storeSlot)
                )
                setIdentity(nextIdentity, for: key.storeSlot)
                DiagnosticLog.info(
                    .chat,
                    "fullReload session=\(key.sessionID) reason=\(StoreRefreshReason.initialSubscribe) count=\(merged.count) ms=\(elapsedMs(since: started))"
                )
                let previous = publishedFrame(for: key.storeSlot)
                if let previous, previous == merged, !awaitingCache {
                    setPublishedFrame(merged, revision: store.revision(sessionID: key.storeSlot), for: key.storeSlot)
                    cacheHandle.note(messages: merged)
                    setAwaitingCacheSyncConfirm(false, for: key.storeSlot)
                    yield(.cacheSyncConfirmed)
                    setHealth(.healthy, for: key)
                } else {
                    publishMessages(merged, false)
                }
                await backfillSplitTurnIfNeeded(key: key, publishMessages: { publishMessages($0, false) })
                _ = staging.completeAuthorityWindow(.accepted)
                lock.lock()
                lastLoadErrorBySlot.removeValue(forKey: key.storeSlot)
                lock.unlock()
            } catch {
                _ = staging.completeAuthorityWindow(.failed)
                lock.lock()
                lastLoadErrorBySlot[key.storeSlot] = error
                lock.unlock()
                yield(.loadFailed(error))
                throw error
            }
        }

        let registration = syncCoordinator.register(key: projection, loader: loadTail)
        syncCoordinator.visible(projection, true)
        syncCoordinator.invalidate(projection)
        let chromeTask = Task { [weak self] in
            guard let self else { return }
            for await state in self.syncCoordinator.observe(projection) {
                guard self.observationStillCurrent(key: key, startedGeneration: startedGeneration) else { return }
                self.applyCoordinatorHealth(state, for: key)
            }
        }
        defer {
            producer.cancel()
            chromeTask.cancel()
            staging.finish()
            syncCoordinator.visible(projection, false)
            syncCoordinator.unregister(registration)
            if observationStillCurrent(key: key, startedGeneration: startedGeneration) {
                closeMessageCacheHandle(for: key)
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
            ResumeTiming.shared.markCachePainted()
            publishMessages(seededMerged, true)
        }

        await syncCoordinator.ensureFresh(projection)
        if frameIdentity(for: key.storeSlot) == nil, publishedFrame(for: key.storeSlot) == nil {
            do {
                try await loadTail()
            } catch {
                if !canCatchUp(occupancy: worldSignals.occupancy, snapshot: liveConnection.snapshot) {
                    yield(.loadFailed(error))
                }
            }
        }

        await runEventRefreshLoop(
            key: key,
            staging: staging,
            publishMessages: { publishMessages($0, false) },
            confirmCacheSync: confirmCacheSyncIfAwaiting,
            projection: projection
        )
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
        try await fetchHistoryPage(
            sessionID: sessionID,
            directory: directory,
            query: .newest(limit: MessageHistoryPaging.tailLimit),
            reason: reason
        )
    }

    private func backfillSplitTurnIfNeeded(
        key: MessageSessionKey,
        publishMessages: @escaping @Sendable ([Message]) -> Void
    ) async {
        let directory = key.directory.isEmpty ? nil : key.directory
        var extra = 0
        while extra < MessageHistoryPaging.maxSplitTurnBackfillPages {
            let local = store.messages(sessionID: key.storeSlot)
            guard MessageHistoryMerge.beginsMidTurn(local) else { return }
            lock.lock()
            let hasOlder = hasOlderBySlot[key.storeSlot] ?? false
            let cursor = olderCursorBySlot[key.storeSlot]
            lock.unlock()
            guard hasOlder, let cursor else { return }
            do {
                let older = try await fetchHistoryPage(
                    sessionID: key.sessionID,
                    directory: directory,
                    query: .older(cursor: cursor, limit: MessageHistoryPaging.splitTurnPageLimit),
                    reason: "split-turn"
                )
                if older.messages.isEmpty { return }
                var applied = false
                while !applied {
                    applied = store.prependOlderIfRevision(
                        sessionID: key.storeSlot,
                        older: older.messages,
                        expectedRevision: store.revision(sessionID: key.storeSlot)
                    )
                }
                rememberWindow(older, for: key.storeSlot, update: .replace)
                let merged = store.messages(sessionID: key.storeSlot)
                setPublishedFrame(merged, revision: store.revision(sessionID: key.storeSlot), for: key.storeSlot)
                setIdentity(
                    MessageFrameIdentity.of(
                        messages: merged,
                        engineRevision: store.revision(sessionID: key.storeSlot)
                    ),
                    for: key.storeSlot
                )
                DiagnosticLog.info(
                    .chat,
                    "splitTurnBackfill session=\(key.sessionID) extra=\(extra + 1) count=\(merged.count)"
                )
                publishMessages(merged)
                extra += 1
            } catch {
                DiagnosticLog.warning(.chat, "splitTurnBackfill failed session=\(key.sessionID)")
                return
            }
        }
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
            setIdentity(
                MessageFrameIdentity.of(
                    messages: merged,
                    engineRevision: store.revision(sessionID: key.storeSlot)
                ),
                for: key.storeSlot
            )
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

    private func resyncTranscript(_ key: MessageSessionKey) async {
        let projection = transcriptKey(key)
        syncCoordinator.invalidate(projection)
        await syncCoordinator.ensureFresh(projection)
    }

    private func fullReloadSilent(
        key: MessageSessionKey,
        reason: String,
        staging: MessageEventStaging?,
        publishMessages: @escaping @Sendable ([Message]) -> Void
    ) async {
        if let staging {
            staging.beginAuthorityWindow()
        }
        switch await fullReloadResult(key: key, reason: reason) {
        case .accepted(let messages):
            if let staging {
                _ = staging.completeAuthorityWindow(.accepted)
            }
            publishMessages(messages)
            await backfillSplitTurnIfNeeded(key: key, publishMessages: publishMessages)
        case .discarded(let messages):
            if let staging {
                _ = staging.completeAuthorityWindow(.discarded)
            }
            publishMessages(messages)
        case .failure:
            if let staging {
                _ = staging.completeAuthorityWindow(.failed)
            }
        }
    }

    private func runEventRefreshLoop(
        key: MessageSessionKey,
        staging: MessageEventStaging,
        publishMessages: @escaping @Sendable ([Message]) -> Void,
        confirmCacheSync: @escaping @Sendable () -> Void,
        projection: ProjectionKey
    ) async {
        while let item = await staging.dequeue() {
            guard observationStillCurrent(key: key) else { return }
            switch item {
            case .overflow, .recoveryRequired:
                await resyncTranscript(key)
                continue
            case .evaluate:
                continue
            case .event(let event, _):
                if staging.needsAuthoritativeRecovery {
                    await resyncTranscript(key)
                    continue
                }
                MessagePipelineProbe.emit(.eventAccepted)
                await handleStagedEvent(
                    event: event,
                    key: key,
                    staging: staging,
                    publishMessages: publishMessages,
                    confirmCacheSync: confirmCacheSync
                )
            }
        }
        _ = projection
        _ = confirmCacheSync
    }

    private func handleStagedEvent(
        event: OpencodeEvent,
        key: MessageSessionKey,
        staging: MessageEventStaging,
        publishMessages: @escaping @Sendable ([Message]) -> Void,
        confirmCacheSync: @escaping @Sendable () -> Void
    ) async {
        switch event.type {
        case "sse.parse.failed", "stream.reconnected", "server.connected":
            return
        default:
            break
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
                    publishMessages: publishMessages
                )
                return
            }
            handleMessagePartRemoved(event: event, key: key, publishMessages: publishMessages)
        case "session.idle":
            store.finishStreamingText(sessionID: key.storeSlot)
            let recovered = store.messages(sessionID: key.storeSlot)
            if markChanged(key: key, messages: recovered) {
                publishMessages(recovered)
            }
            guard SessionTranscriptAuthority.lastAssistantNeedsAuthority(recovered) else {
                setHealth(.healthy, for: key)
                return
            }
            await resyncTranscript(key)
            confirmCacheSync()
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
        // No whole-history identity gate on the live SSE path.
        publishMessages(frame)
    }

    private func markChanged(key: MessageSessionKey, messages: [Message]) -> Bool {
        let identity = MessageFrameIdentity.of(
            messages: messages,
            engineRevision: store.revision(sessionID: key.storeSlot)
        )
        if identity == frameIdentity(for: key.storeSlot) { return false }
        setIdentity(identity, for: key.storeSlot)
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
