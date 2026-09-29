import Foundation

/// One foreground sync authority. Disconnect invalidates; `server.connected`
/// (and `stream.reconnected`) run one bootstrap batch; everything else loads
/// lazily when observed. Lock-protected so SSE/sync work stays off MainActor.
nonisolated final class DefaultSyncCoordinator: SyncCoordinator, @unchecked Sendable {
    private let lock = NSLock()
    private let connection: any LiveConnection
    private let worldSignals: any WorldSignals
    private let eventStream: any ForegroundEventStream
    private let clock: any SyncClock

    private var records: [ProjectionKey: KeyRecord] = [:]
    private var focused: ProjectionKey?
    private var lastBootstrapOrActivityMs: Int64
    private var lastConnectionState: EventStreamConnectionState
    private var lastCouldCatchUp = false
    private var batchRunning = false
    private var pendingBatch: SyncBootstrapReason?
    private var coalesceTask: Task<Void, Never>?
    private var collectors: [Task<Void, Never>] = []
    private var _profileGeneration: UInt64 = 0

    var profileGeneration: UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return _profileGeneration
    }

    init(
        connection: any LiveConnection,
        worldSignals: any WorldSignals,
        eventStream: any ForegroundEventStream,
        clock: any SyncClock = SystemSyncClock()
    ) {
        self.connection = connection
        self.worldSignals = worldSignals
        self.eventStream = eventStream
        self.clock = clock
        lastBootstrapOrActivityMs = clock.nowMs()
        lastConnectionState = connection.connectionState
        lastCouldCatchUp = canCatchUpNow()
        startCollectors()
    }

    deinit {
        lock.lock()
        let tasks = collectors
        let coalesce = coalesceTask
        let records = Array(self.records.values)
        collectors.removeAll()
        coalesceTask = nil
        lock.unlock()
        coalesce?.cancel()
        tasks.forEach { $0.cancel() }
        for record in records {
            record.retryTask?.cancel()
            record.loadTask?.cancel()
        }
    }

    func beginProfileSwitch(generation: UInt64) {
        lock.lock()
        _profileGeneration = generation
        focused = nil
        pendingBatch = nil
        coalesceTask?.cancel()
        coalesceTask = nil
        var waiters: [CheckedContinuation<Void, Never>] = []
        let keys = Array(records.keys)
        for key in keys {
            guard var record = records[key] else { continue }
            record.staleSerial &+= 1
            record.retryTask?.cancel()
            record.retryTask = nil
            record.retryAttempt = 0
            record.loadTask?.cancel()
            record.loadTask = nil
            record.inFlight = false
            record.state = .stale
            waiters.append(contentsOf: record.takeWaiters())
            records[key] = record
        }
        lock.unlock()
        resume(waiters)
        for key in keys {
            publishState(key, .stale)
        }
    }

    func register(
        key: ProjectionKey,
        loader: @escaping @Sendable () async throws -> Void
    ) -> SyncRegistration {
        let id = UUID()
        lock.lock()
        if var existing = records[key] {
            existing.loader = loader
            existing.registrationID = id
            records[key] = existing
            lock.unlock()
            return SyncRegistration(id: id, key: key)
        }
        records[key] = KeyRecord(loader: loader, registrationID: id)
        lock.unlock()
        publishState(key, .stale)
        return SyncRegistration(id: id, key: key)
    }

    func unregister(_ registration: SyncRegistration) {
        lock.lock()
        guard var record = records[registration.key],
              record.registrationID == registration.id
        else {
            lock.unlock()
            return
        }
        record.retryTask?.cancel()
        record.retryTask = nil
        record.retryAttempt = 0
        let waiters = record.takeWaiters()
        let continuations = Array(record.stateContinuations.values)
        records.removeValue(forKey: registration.key)
        if focused == registration.key {
            focused = nil
        }
        lock.unlock()
        resume(waiters)
        for continuation in continuations {
            continuation.finish()
        }
    }

    func observe(_ key: ProjectionKey) -> AsyncStream<ProjectionState> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            let current = records[key]?.state ?? .stale
            if records[key] != nil {
                records[key]?.stateContinuations[id] = continuation
            }
            lock.unlock()
            continuation.yield(current)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.records[key]?.stateContinuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    func ensureFresh(_ key: ProjectionKey) async {
        lock.lock()
        guard records[key] != nil else {
            lock.unlock()
            return
        }
        if records[key]?.state == .fresh {
            lock.unlock()
            return
        }
        if !canCatchUpLocked() {
            lock.unlock()
            return
        }
        if records[key]?.inFlight == true {
            lock.unlock()
            await waitForFlight(key)
            return
        }
        lock.unlock()
        await runLoader(key)
    }

    func invalidate(_ key: ProjectionKey?) {
        lock.lock()
        let keys: [ProjectionKey]
        if let key {
            keys = records[key] == nil ? [] : [key]
        } else {
            keys = Array(records.keys)
        }
        for item in keys {
            guard var record = records[item] else { continue }
            record.staleSerial &+= 1
            record.retryTask?.cancel()
            record.retryTask = nil
            record.retryAttempt = 0
            record.state = .stale
            records[item] = record
        }
        lock.unlock()
        for item in keys {
            publishState(item, .stale)
        }
    }

    func bootstrap(_ reason: SyncBootstrapReason) async {
        lock.lock()
        if batchRunning {
            pendingBatch = reason
            lock.unlock()
            return
        }
        batchRunning = true
        lock.unlock()
        await runBatch(reason)
        while true {
            lock.lock()
            let next = pendingBatch
            pendingBatch = nil
            if next == nil {
                batchRunning = false
                lock.unlock()
                return
            }
            lock.unlock()
            await runBatch(next!)
        }
    }

    func markFocused(_ sessionKey: ProjectionKey?) {
        lock.lock()
        if let sessionKey {
            switch sessionKey {
            case .transcript:
                focused = sessionKey
            default:
                focused = nil
            }
        } else {
            focused = nil
        }
        lock.unlock()
    }

    func visible(_ key: ProjectionKey, _ isVisible: Bool) {
        lock.lock()
        guard var record = records[key] else {
            lock.unlock()
            return
        }
        if isVisible {
            record.visibleCount += 1
        } else {
            record.visibleCount = max(0, record.visibleCount - 1)
        }
        records[key] = record
        lock.unlock()
    }

    private func startCollectors() {
        let connection = connection
        let worldSignals = worldSignals
        let eventStream = eventStream
        collectors.append(Task.detached { [weak self] in
            for await state in connection.connectionStateUpdates() {
                self?.onConnectionState(state)
            }
        })
        collectors.append(Task.detached { [weak self] in
            for await _ in connection.snapshotUpdates() {
                self?.onCatchUpMayHaveChanged()
            }
        })
        collectors.append(Task.detached { [weak self] in
            for await occupancy in worldSignals.occupancyUpdates() {
                self?.onOccupancy(occupancy)
            }
        })
        collectors.append(Task.detached { [weak self] in
            for await signal in worldSignals.signals() {
                self?.onWorldSignal(signal)
            }
        })
        collectors.append(Task.detached { [weak self] in
            for await event in eventStream.events() {
                self?.onEvent(event)
            }
        })
    }

    private func onConnectionState(_ state: EventStreamConnectionState) {
        lock.lock()
        let previous = lastConnectionState
        lastConnectionState = state
        let dropped = previous == .connected && state != .connected
        if dropped {
            // The drop may already have recovered by the time it is handled
            // (`canCatchUpNow` reads the live snapshot, not this state), so
            // forget that catch-up was possible: the next evaluation must
            // reload the projections this drop just made stale.
            lastCouldCatchUp = false
        }
        lock.unlock()
        if dropped {
            invalidate(nil)
        }
        onCatchUpMayHaveChanged()
    }

    private func onWorldSignal(_ signal: WorldSignal) {
        switch signal {
        case .backgroundStop, .pathLost:
            invalidate(nil)
        case .profileSwitched:
            lock.lock()
            focused = nil
            lock.unlock()
            invalidate(nil)
        case .foreground:
            onForeground()
        case .pathReady:
            let generation = profileGeneration
            Task.detached { [weak self] in
                guard let self, self.profileGeneration == generation else { return }
                await self.waitUntilCanCatchUp()
                guard self.profileGeneration == generation, self.canCatchUpNow() else { return }
                await self.bootstrap(.serverConnected)
            }
        case .background:
            break
        }
    }

    private func onEvent(_ event: OpencodeEvent) {
        switch event.type {
        case "server.connected", "stream.reconnected":
            ResumeTiming.shared.markConnected(nowMs: clock.nowMs())
            scheduleCoalescedBootstrap()
        case "sse.parse.failed":
            invalidate(nil)
            scheduleCoalescedBootstrap()
        default:
            break
        }
    }

    private func onForeground() {
        let now = clock.nowMs()
        lock.lock()
        let last = lastBootstrapOrActivityMs
        let generation = _profileGeneration
        lock.unlock()
        guard now - last >= SyncRetryBackoff.foregroundQuietMs else { return }
        Task.detached { [weak self] in
            guard let self, self.profileGeneration == generation else { return }
            await self.waitUntilCanCatchUp()
            guard self.profileGeneration == generation, self.canCatchUpNow() else { return }
            await self.bootstrap(.foregroundQuiet)
        }
    }

    private func scheduleCoalescedBootstrap() {
        lock.lock()
        if coalesceTask != nil {
            lock.unlock()
            return
        }
        let generation = _profileGeneration
        coalesceTask = Task.detached { [weak self] in
            guard let self else { return }
            await self.clock.sleep(milliseconds: SyncRetryBackoff.coalesceMs)
            self.lock.lock()
            self.coalesceTask = nil
            let stillCurrent = self._profileGeneration == generation
            self.lock.unlock()
            guard stillCurrent else { return }
            await self.bootstrap(.serverConnected)
        }
        lock.unlock()
    }

    private func onOccupancy(_ occupancy: StreamOccupancy) {
        if occupancy == .stopped {
            invalidate(nil)
        }
        let can = occupancy == .active && connection.snapshot.canCatchUp
        lock.lock()
        let was = lastCouldCatchUp
        lastCouldCatchUp = can
        let hasStaleVisible = records.contains { _, record in
            record.visibleCount > 0 && record.state != .fresh
        }
        lock.unlock()
        guard can, !was, hasStaleVisible else { return }
        let generation = profileGeneration
        Task.detached { [weak self] in
            guard let self, self.profileGeneration == generation else { return }
            await self.bootstrap(.serverConnected)
        }
    }

    private func onCatchUpMayHaveChanged() {
        let can = canCatchUpNow()
        lock.lock()
        let was = lastCouldCatchUp
        lastCouldCatchUp = can
        let hasStaleVisible = records.contains { _, record in
            record.visibleCount > 0 && record.state != .fresh
        }
        lock.unlock()
        guard can, !was, hasStaleVisible else { return }
        let generation = profileGeneration
        Task.detached { [weak self] in
            guard let self, self.profileGeneration == generation else { return }
            await self.bootstrap(.serverConnected)
        }
    }

    private func waitUntilCanCatchUp() async {
        if canCatchUpNow() { return }
        for await _ in connection.snapshotUpdates() {
            if canCatchUpNow() { return }
        }
    }

    private func runBatch(_ reason: SyncBootstrapReason) async {
        lastBootstrapOrActivityMs = clock.nowMs()
        let forceFresh = reason == .foregroundQuiet || reason == .manual
        lock.lock()
        let visibleKeys = records.compactMap { key, record -> ProjectionKey? in
            record.visibleCount > 0 ? key : nil
        }
        let focusedKey = focused
        lock.unlock()

        let directories = Set(visibleKeys.map(\.directory))
        let statuses = directories.map { ProjectionKey.sessionStatuses(directory: $0) }
            .filter { shouldLoad($0, forceFresh: forceFresh) }
        var firstWave = statuses
        if let focusedKey, shouldLoad(focusedKey, forceFresh: forceFresh) {
            firstWave.append(focusedKey)
        }
        await runParallel(firstWave)

        let lists = visibleKeys.filter {
            if case .sessionList = $0 { return shouldLoad($0, forceFresh: forceFresh) }
            return false
        }
        await runParallel(lists)

        let visibleTranscripts = visibleKeys.filter { key in
            if case .transcript = key, key != focusedKey {
                return shouldLoad(key, forceFresh: forceFresh)
            }
            return false
        }
        await runParallel(visibleTranscripts)

        let others = visibleKeys.filter { key in
            switch key {
            case .todos, .permissions, .questions:
                shouldLoad(key, forceFresh: forceFresh)
            default:
                false
            }
        }
        await runParallel(others)
        lastBootstrapOrActivityMs = clock.nowMs()
        if reason == .serverConnected {
            ResumeTiming.shared.markBootstrapDone(nowMs: lastBootstrapOrActivityMs)
        }
    }

    private func shouldLoad(_ key: ProjectionKey, forceFresh: Bool) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let record = records[key], record.visibleCount > 0 else { return false }
        if record.inFlight { return false }
        if forceFresh { return true }
        return record.state != .fresh
    }

    private func runParallel(_ keys: [ProjectionKey]) async {
        await withTaskGroup(of: Void.self) { group in
            for key in keys {
                group.addTask { await self.runLoader(key) }
            }
        }
    }

    private func runLoader(_ key: ProjectionKey) async {
        lock.lock()
        guard var record = records[key] else {
            lock.unlock()
            return
        }
        if record.inFlight {
            lock.unlock()
            await waitForFlight(key)
            return
        }
        if !canCatchUpLocked() {
            lock.unlock()
            return
        }
        let serial = record.staleSerial
        let generation = _profileGeneration
        let loader = record.loader
        record.inFlight = true
        record.state = .syncing
        let loadTask = Task.detached { [weak self] in
            guard let self else { return }
            await self.performLoad(key: key, serial: serial, generation: generation, loader: loader)
        }
        record.loadTask = loadTask
        records[key] = record
        lock.unlock()
        publishState(key, .syncing)
        await loadTask.value
    }

    private func performLoad(
        key: ProjectionKey,
        serial: UInt64,
        generation: UInt64,
        loader: @escaping @Sendable () async throws -> Void
    ) async {
        do {
            try await loader()
            finishLoad(key: key, serial: serial, generation: generation, succeeded: true)
        } catch {
            finishLoad(key: key, serial: serial, generation: generation, succeeded: false)
        }
    }

    private func finishLoad(key: ProjectionKey, serial: UInt64, generation: UInt64, succeeded: Bool) {
        lock.lock()
        guard var record = records[key] else {
            lock.unlock()
            return
        }
        record.inFlight = false
        record.loadTask = nil
        let waiters = record.takeWaiters()
        let currentSerial = record.staleSerial
        let currentGeneration = _profileGeneration
        let generationMatches = currentGeneration == generation
        if succeeded {
            record.retryAttempt = 0
            record.retryTask?.cancel()
            record.retryTask = nil
            if currentSerial == serial, generationMatches {
                record.state = .fresh
                lastBootstrapOrActivityMs = clock.nowMs()
            } else {
                record.state = .stale
            }
            records[key] = record
            let nextState = record.state
            let shouldReload = record.state == .stale && generationMatches && canCatchUpLocked()
            lock.unlock()
            publishState(key, nextState)
            resume(waiters)
            if shouldReload {
                Task.detached { [weak self] in
                    await self?.runLoader(key)
                }
            }
            return
        }
        record.state = .stale
        record.retryAttempt += 1
        let attempt = record.retryAttempt
        let shouldRetry = generationMatches && canCatchUpLocked()
        records[key] = record
        lock.unlock()
        publishState(key, .stale)
        resume(waiters)
        if shouldRetry {
            scheduleRetry(key: key, serial: currentSerial, generation: generation, attempt: attempt)
        }
    }

    private func scheduleRetry(key: ProjectionKey, serial: UInt64, generation: UInt64, attempt: Int) {
        let delay = SyncRetryBackoff.delayMs(attempt: attempt)
        lock.lock()
        guard records[key]?.staleSerial == serial, _profileGeneration == generation else {
            lock.unlock()
            return
        }
        records[key]?.retryTask?.cancel()
        records[key]?.retryTask = Task.detached { [weak self] in
            guard let self else { return }
            await self.clock.sleep(milliseconds: delay)
            guard !Task.isCancelled else { return }
            self.lock.lock()
            let stillSame = self.records[key]?.staleSerial == serial
                && self._profileGeneration == generation
            self.lock.unlock()
            guard stillSame, self.canCatchUpNow() else { return }
            await self.runLoader(key)
        }
        lock.unlock()
    }

    private func waitForFlight(_ key: ProjectionKey) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            lock.lock()
            guard var record = records[key], record.inFlight else {
                lock.unlock()
                continuation.resume()
                return
            }
            record.waiters.append(continuation)
            records[key] = record
            lock.unlock()
        }
    }

    private func publishState(_ key: ProjectionKey, _ state: ProjectionState) {
        lock.lock()
        let continuations: [AsyncStream<ProjectionState>.Continuation]
        if let record = records[key] {
            continuations = Array(record.stateContinuations.values)
        } else {
            continuations = []
        }
        lock.unlock()
        for continuation in continuations {
            continuation.yield(state)
        }
    }

    private func resume(_ waiters: [CheckedContinuation<Void, Never>]) {
        for waiter in waiters {
            waiter.resume()
        }
    }

    private func canCatchUpNow() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return canCatchUpLocked()
    }

    private func canCatchUpLocked() -> Bool {
        canCatchUp(occupancy: worldSignals.occupancy, snapshot: connection.snapshot)
    }
}

private struct KeyRecord {
    var loader: @Sendable () async throws -> Void
    var state: ProjectionState = .stale
    var staleSerial: UInt64 = 1
    var visibleCount = 0
    var registrationID: UUID
    var retryAttempt = 0
    var retryTask: Task<Void, Never>?
    var loadTask: Task<Void, Never>?
    var inFlight = false
    var waiters: [CheckedContinuation<Void, Never>] = []
    var stateContinuations: [UUID: AsyncStream<ProjectionState>.Continuation] = [:]

    mutating func takeWaiters() -> [CheckedContinuation<Void, Never>] {
        let pending = waiters
        waiters.removeAll()
        return pending
    }
}
