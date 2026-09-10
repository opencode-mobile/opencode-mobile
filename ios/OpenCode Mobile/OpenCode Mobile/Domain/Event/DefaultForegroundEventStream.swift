import Foundation

/// Nonisolated broadcaster: byte read, parse, and event yield stay off MainActor.
/// LiveStores filter before hopping back for UI-owned work (OPE-140 Stage 2).
nonisolated final class DefaultForegroundEventStream: LiveConnection, @unchecked Sendable {
    private let lock = NSLock()
    private var _connectionState: EventStreamConnectionState = .idle
    private var _snapshot = LiveConnectionSnapshot()

    var connectionState: EventStreamConnectionState {
        lock.lock()
        defer { lock.unlock() }
        return _connectionState
    }

    var snapshot: LiveConnectionSnapshot {
        lock.lock()
        defer { lock.unlock() }
        return _snapshot
    }

    private let api: EventsAPIClient
    private let adapter: EventDomainAdapter
    private let deviceNetworkPath: any DeviceNetworkPath
    private let instanceDirectories: @Sendable () async -> [String]
    private let configurationRevisions: @Sendable () -> AsyncStream<Void>
    private let profileID: @Sendable () async -> String
    private let connectionIdentity: @Sendable () async -> String
    private let protocolHint: @Sendable () -> ServerProtocol
    private let protocolProbe: (@Sendable () async -> ServerProtocol)?
    private let connectionEpoch: ConnectionEpoch
    private var occupancyTask: Task<Void, Never>?
    private var revisionTask: Task<Void, Never>?
    private var protocolProbeTask: Task<Void, Never>?
    /// Invalidates in-flight cancel/error handlers after `stop()` or a newer `start()`.
    private var streamGeneration: UInt64 = 0
    private var appliedConfig: LiveConnectionConfig?
    private var pendingStartConfig: LiveConnectionConfig?
    private var inFlightStartApplies = 0
    private var wasFullyConnected = false
    private var occupyingConnected = false
    private var emittedReconnectForCurrentGap = false
    private var emittedConnectedForCurrentGeneration = false
    private var pendingResumeBarrier = false
    private var suppressReconnectForRequiredGrowth = false
    private var currentGap: LiveGapRevision?
    private var gapClock: UInt64 = 0
    private var lastAllLive = false
    private var lastRequiredTargets: [String] = []
    private var lastLiveTargets: Set<String> = []
    private let eventDeduper = SseEventDeduper()
    private var leases: [String: Lease] = [:]
    private var subscriberContinuations: [UUID: AsyncStream<OpencodeEvent>.Continuation] = [:]
    private var connectionStateContinuations: [UUID: AsyncStream<EventStreamConnectionState>.Continuation] = [:]
    private var snapshotContinuations: [UUID: AsyncStream<LiveConnectionSnapshot>.Continuation] = [:]

    private let idleTimeout: Duration

    init(
        api: EventsAPIClient,
        adapter: EventDomainAdapter,
        deviceNetworkPath: any DeviceNetworkPath = ReadyDeviceNetworkPath(),
        idleTimeout: Duration = .seconds(45),
        instanceDirectories: @escaping @Sendable () async -> [String] = { [] },
        configurationRevisions: @escaping @Sendable () -> AsyncStream<Void> = { AsyncStream { $0.finish() } },
        connectionEpoch: ConnectionEpoch = .shared,
        profileID: @escaping @Sendable () async -> String = { "" },
        connectionIdentity: (@Sendable () async -> String)? = nil,
        protocolHint: (@Sendable () -> ServerProtocol)? = nil,
        protocolProbe: (@Sendable () async -> ServerProtocol)? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.deviceNetworkPath = deviceNetworkPath
        self.idleTimeout = idleTimeout
        self.instanceDirectories = instanceDirectories
        self.configurationRevisions = configurationRevisions
        self.connectionEpoch = connectionEpoch
        self.profileID = profileID
        self.connectionIdentity = connectionIdentity ?? profileID
        self.protocolHint = protocolHint ?? { api.eventProtocol }
        self.protocolProbe = protocolProbe
    }

    func events() -> AsyncStream<OpencodeEvent> {
        let subscriberID = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: OpencodeEvent.self)
        lock.lock()
        subscriberContinuations[subscriberID] = continuation
        lock.unlock()
        continuation.onTermination = { [weak self] _ in
            guard let self else { return }
            self.lock.lock()
            self.subscriberContinuations.removeValue(forKey: subscriberID)
            self.lock.unlock()
        }
        return stream
    }

    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        let subscriberID = UUID()
        return AsyncStream { continuation in
            self.lock.lock()
            let current = self._connectionState
            self.connectionStateContinuations[subscriberID] = continuation
            self.lock.unlock()
            continuation.yield(current)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.connectionStateContinuations.removeValue(forKey: subscriberID)
                self.lock.unlock()
            }
        }
    }

    func snapshotUpdates() -> AsyncStream<LiveConnectionSnapshot> {
        let subscriberID = UUID()
        return AsyncStream { continuation in
            self.lock.lock()
            let current = self._snapshot
            self.snapshotContinuations[subscriberID] = continuation
            self.lock.unlock()
            continuation.yield(current)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.snapshotContinuations.removeValue(forKey: subscriberID)
                self.lock.unlock()
            }
        }
    }

    func start() {
        lock.lock()
        if occupancyTask != nil {
            inFlightStartApplies += 1
            lock.unlock()
            Task { [weak self] in
                defer { self?.endInFlightStartApply() }
                await self?.applyStartWhileOccupying()
            }
            return
        }
        DiagnosticLog.info(.sse, "foreground SSE stream starting")
        streamGeneration += 1
        let generation = streamGeneration
        openGapLocked(.occupancyStart)
        occupancyTask = Task(priority: .userInitiated) { [weak self] in
            await self?.beginOccupancy(generation: generation)
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(3_600))
            }
        }
        lock.unlock()
        _ = connectionEpoch.bump()
    }

    func stop() {
        lock.lock()
        let hadTask = occupancyTask != nil
        let state = _connectionState
        if wasFullyConnected || occupyingConnected {
            pendingResumeBarrier = true
        }
        streamGeneration += 1
        let generation = streamGeneration
        occupancyTask?.cancel()
        occupancyTask = nil
        revisionTask?.cancel()
        revisionTask = nil
        protocolProbeTask?.cancel()
        protocolProbeTask = nil
        pendingStartConfig = nil
        cancelAllLeasesLocked()
        eventDeduper.clear()
        appliedConfig = nil
        wasFullyConnected = false
        occupyingConnected = false
        emittedReconnectForCurrentGap = false
        emittedConnectedForCurrentGeneration = false
        suppressReconnectForRequiredGrowth = false
        lastAllLive = false
        lastRequiredTargets = []
        lastLiveTargets = []
        openGapLocked(.stopped)
        lock.unlock()
        if hadTask || state != .idle {
            DiagnosticLog.info(.sse, "foreground SSE stream stopping")
        }
        applyConnectionState(.idle, generation: generation)
        publishSnapshot()
    }

    private func applyStartWhileOccupying() async {
        lock.lock()
        let occupancy = occupancyTask
        let generation = streamGeneration
        lock.unlock()
        guard occupancy != nil, isOccupying() else { return }
        let config = await resolveConfig()
        lock.lock()
        let stillSameOccupancy = occupancyTask == occupancy && occupancyTask != nil
        let applied = appliedConfig
        if !stillSameOccupancy || occupancyTask == nil {
            lock.unlock()
            return
        }
        if applied == nil {
            pendingStartConfig = config
            lock.unlock()
            return
        }
        if streamGeneration != generation {
            pendingStartConfig = config
            lock.unlock()
            return
        }
        lock.unlock()
        guard let applied else { return }
        if applied.identity == config.identity && applied.serverProtocol == config.serverProtocol {
            if applied.directories == config.directories {
                DiagnosticLog.debug(.sse, "foreground SSE start ignored; already running")
                return
            }
            guard stillCurrent(generation) else { return }
            reconcileLeases(config, generation: generation)
            return
        }
        guard stillCurrent(generation) else { return }
        retargetIdentity(config, reason: .configChanged)
    }

    private func endInFlightStartApply() {
        lock.lock()
        inFlightStartApplies = max(0, inFlightStartApplies - 1)
        lock.unlock()
    }

    private func waitForInFlightStartApplies() async {
        while true {
            lock.lock()
            let inFlight = inFlightStartApplies
            lock.unlock()
            if inFlight == 0 { return }
            await Task.yield()
        }
    }

    private func beginOccupancy(generation: UInt64) async {
        let config = await resolveConfig()
        guard stillCurrent(generation), isOccupying() else { return }
        await waitForInFlightStartApplies()
        guard stillCurrent(generation), isOccupying() else { return }
        lock.lock()
        let pendingAtCommit = pendingStartConfig
        pendingStartConfig = nil
        let merged = pendingAtCommit ?? config
        appliedConfig = merged
        lock.unlock()
        publishSnapshot()
        lock.lock()
        revisionTask?.cancel()
        revisionTask = Task { [weak self] in
            await self?.collectConfigurationRevisions(generation: generation)
        }
        protocolProbeTask?.cancel()
        protocolProbeTask = Task { [weak self] in
            await self?.probeProtocolWhileOccupying(generation: generation)
        }
        lock.unlock()
        await deviceNetworkPath.waitUntilReady()
        guard stillCurrent(generation), isOccupying() else { return }
        lock.lock()
        let pending = pendingStartConfig
        pendingStartConfig = nil
        let effective = pending ?? merged
        lock.unlock()
        guard stillCurrent(generation) else { return }
        applyConnectionState(
            (wasFullyConnected || pendingResumeBarrier) ? .reconnecting : .connecting,
            generation: generation
        )
        if let pending,
           pending.identity != merged.identity || pending.serverProtocol != merged.serverProtocol {
            guard stillCurrent(generation) else { return }
            retargetIdentity(pending, reason: .configChanged)
            return
        }
        guard stillCurrent(generation) else { return }
        reconcileLeases(effective, generation: generation)
    }

    private func collectConfigurationRevisions(generation: UInt64) async {
        await onConfigurationRevision(generation: generation)
        for await _ in configurationRevisions() {
            if Task.isCancelled { return }
            guard stillCurrent(generation) else { return }
            await onConfigurationRevision(generation: generation)
        }
    }

    private func probeProtocolWhileOccupying(generation: UInt64) async {
        guard let protocolProbe else { return }
        let resolved = await protocolProbe()
        guard stillCurrent(generation), isOccupying() else { return }
        lock.lock()
        let applied = appliedConfig
        lock.unlock()
        guard let applied, resolved != applied.serverProtocol else { return }
        DiagnosticLog.info(
            .sse,
            "SSE protocol resolved \(applied.serverProtocol.wireValue) -> \(resolved.wireValue); reconnecting"
        )
        retargetIdentity(
            LiveConnectionConfig(
                profileID: applied.profileID,
                connectionIdentity: applied.connectionIdentity,
                serverProtocol: resolved,
                directories: applied.directories
            ),
            reason: .configChanged
        )
    }

    private func onConfigurationRevision(generation: UInt64) async {
        let config = await resolveConfig()
        guard stillCurrent(generation), isOccupying() else { return }
        lock.lock()
        let applied = appliedConfig
        lock.unlock()
        guard let applied else { return }
        if applied.identity != config.identity || applied.serverProtocol != config.serverProtocol {
            retargetIdentity(config, reason: .configChanged)
            return
        }
        if applied.directories != config.directories {
            reconcileLeases(config, generation: currentGeneration())
        }
    }

    private func retargetIdentity(_ config: LiveConnectionConfig, reason: LiveGapReason) {
        guard isOccupying() else { return }
        lock.lock()
        streamGeneration += 1
        let generation = streamGeneration
        openGapLocked(reason)
        appliedConfig = config
        eventDeduper.clear()
        occupyingConnected = false
        emittedReconnectForCurrentGap = false
        emittedConnectedForCurrentGeneration = false
        suppressReconnectForRequiredGrowth = false
        cancelAllLeasesLocked()
        lock.unlock()
        _ = connectionEpoch.bump()
        guard stillCurrent(generation) else { return }
        reconcileLeases(config, generation: generation)
    }

    private func reconcileLeases(_ config: LiveConnectionConfig, generation: UInt64) {
        guard stillCurrent(generation), isOccupying() else { return }
        lock.lock()
        appliedConfig = config
        let targets = SseSubscribeTargets.targets(protocol: config.serverProtocol, directories: config.directories)
        let required = Set(targets.map(\.key))
        let stale = leases.keys.filter { !required.contains($0) }
        for key in stale {
            leases.removeValue(forKey: key)?.cancel()
        }
        for target in targets where leases[target.key] == nil {
            leases[target.key] = startLease(target, generation: generation)
        }
        lock.unlock()
        refreshOverallState(generation: generation)
        publishSnapshot()
    }

    private func startLease(_ target: SseSubscribeTargets.Target, generation: UInt64) -> Lease {
        let lease = Lease(target: target)
        lease.loopTask = Task { [weak self, weak lease] in
            guard let self, let lease else { return }
            let retry = RetryBox()
            while !Task.isCancelled, generation == self.currentGeneration(), self.isOccupying() {
                guard self.isRequired(target.key) else { return }
                await self.deviceNetworkPath.waitUntilReady()
                guard generation == self.currentGeneration(), self.isRequired(target.key) else { return }
                lease.activity.touch()
                let attemptId = lease.nextAttempt()
                let streamTask = Task {
                    try await self.api.streamEvents(
                        path: target.path,
                        directory: target.directory,
                        onHTTPResponse: { statusCode in
                            guard lease.currentAttempt() == attemptId else { return }
                            lease.activity.touch()
                            DiagnosticLog.info(
                                .sse,
                                "GET /\(target.path) opened status=\(statusCode) protocol=\(self.api.eventProtocolWireValue)"
                            )
                            self.markLeaseLive(lease, generation: generation)
                            retry.reset()
                        },
                        onActivity: {
                            lease.activity.touch()
                        },
                        onDataLine: { dataLine in
                            lease.activity.touch()
                            await self.publishDataLine(dataLine, generation: generation)
                        }
                    )
                }
                lease.streamTask = streamTask
                let watchTask = Task {
                    while !Task.isCancelled, generation == self.currentGeneration(), !streamTask.isCancelled {
                        let remaining = self.idleTimeout - lease.activity.elapsed()
                        if remaining <= .zero {
                            DiagnosticLog.warning(.sse, "foreground SSE idle timeout target=\(target.key); reconnecting")
                            streamTask.cancel()
                            return
                        }
                        try? await Task.sleep(for: remaining)
                    }
                }
                let pathTask = Task {
                    for await status in self.deviceNetworkPath.updates() {
                        if status == .unavailable {
                            streamTask.cancel()
                            return
                        }
                    }
                }
                do {
                    _ = try await streamTask.value
                } catch is CancellationError {
                    if generation != self.currentGeneration() || !self.isRequired(target.key) {
                        watchTask.cancel()
                        pathTask.cancel()
                        return
                    }
                } catch {
                    DiagnosticLog.warning(.sse, "foreground SSE stream failed target=\(target.key) error=\(String(describing: error))")
                }
                watchTask.cancel()
                pathTask.cancel()
                if lease.currentAttempt() == attemptId {
                    lease.streamTask = nil
                    self.markLeaseDead(lease, generation: generation)
                }
                guard generation == self.currentGeneration(), self.isRequired(target.key) else { return }
                if self.deviceNetworkPath.status == .unavailable {
                    continue
                }
                retry.increment()
                let backoffMs = min(10_000, 1_000 * retry.attempt)
                DiagnosticLog.debug(.sse, "foreground SSE retry target=\(target.key) in \(backoffMs)ms")
                try? await Task.sleep(for: .milliseconds(backoffMs))
            }
        }
        return lease
    }

    private func publishDataLine(_ dataLine: String, generation: UInt64) async {
        guard generation == currentGeneration() else { return }
        let parsed = adapter.parseEvents(dataLine: dataLine)
        if parsed.isEmpty {
            DiagnosticLog.warning(.sse, SseDiagnosticLogging.parseFailedMessage(dataLine))
            if !dataLine.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                lock.lock()
                openGapLocked(.parseFailed)
                lock.unlock()
                publishSnapshot()
                _ = handleStreamEvent(OpencodeEvent(type: "sse.parse.failed", sessionID: nil), generation: generation)
            }
            return
        }
        for event in parsed {
            _ = handleStreamEvent(event, generation: generation)
        }
    }

    @discardableResult
    private func handleStreamEvent(_ event: OpencodeEvent, generation: UInt64) -> Bool {
        guard generation == currentGeneration() else { return false }
        if let line = SseDiagnosticLogging.bufferMessage(for: event) {
            DiagnosticLog.info(.sse, line)
        } else if event.type != "server.heartbeat" {
            DiagnosticLog.debug(.sse, "SSE event type=\(event.type) session=\(event.sessionID ?? "-")")
        }
        switch event.type {
        case "server.connected":
            lock.lock()
            let shouldPublish = !emittedConnectedForCurrentGeneration
            if shouldPublish { emittedConnectedForCurrentGeneration = true }
            lock.unlock()
            if shouldPublish { publish(event) }
            return true
        case "server.heartbeat":
            return false
        default:
            guard eventDeduper.accept(event.id) else { return false }
            publish(event)
            return false
        }
    }

    private func markLeaseLive(_ lease: Lease, generation: UInt64) {
        guard stillCurrent(generation) else { return }
        lease.setLive(true)
        refreshOverallState(generation: generation)
    }

    private func markLeaseDead(_ lease: Lease, generation: UInt64) {
        guard stillCurrent(generation) else { return }
        lease.setLive(false)
        lock.lock()
        occupyingConnected = false
        lock.unlock()
        refreshOverallState(generation: generation)
    }

    private func refreshOverallState(generation: UInt64) {
        guard stillCurrent(generation) else { return }
        lock.lock()
        let config = appliedConfig
        let required = config.map {
            SseSubscribeTargets.targets(protocol: $0.serverProtocol, directories: $0.directories).map(\.key)
        } ?? []
        let live = Set(leases.filter { $0.value.isLive }.map(\.key))
        let allLive = !required.isEmpty && required.allSatisfy { live.contains($0) }
        let incumbentLost = lastRequiredTargets.contains { key in
            required.contains(key) && lastLiveTargets.contains(key) && !live.contains(key)
        }
        let requiredGrew = !lastRequiredTargets.isEmpty
            && lastRequiredTargets.allSatisfy { required.contains($0) }
            && required.count > lastRequiredTargets.count
        let pathLost = deviceNetworkPath.status == .unavailable
        let lostLive = lastAllLive && !allLive
        if incumbentLost {
            openGapLocked(pathLost ? .pathLost : .leaseLost)
            suppressReconnectForRequiredGrowth = false
        } else if lostLive && pathLost {
            openGapLocked(.pathLost)
        } else if lostLive && requiredGrew {
            suppressReconnectForRequiredGrowth = true
        }
        lastAllLive = allLive
        lastRequiredTargets = required
        lastLiveTargets = live
        let shouldPublishReconnect = (wasFullyConnected || pendingResumeBarrier)
            && !emittedReconnectForCurrentGap
            && !suppressReconnectForRequiredGrowth
        let nextState: EventStreamConnectionState
        let publishReconnect: Bool
        if allLive {
            occupyingConnected = true
            wasFullyConnected = true
            suppressReconnectForRequiredGrowth = false
            nextState = .connected
            publishReconnect = shouldPublishReconnect
            if publishReconnect {
                emittedReconnectForCurrentGap = true
                pendingResumeBarrier = false
            }
        } else {
            nextState = wasFullyConnected ? .reconnecting : .connecting
            publishReconnect = false
        }
        let decisionGeneration = streamGeneration
        lock.unlock()
        applyConnectionState(nextState, generation: decisionGeneration)
        if publishReconnect {
            lock.lock()
            let stillSameGeneration = occupancyTask != nil && streamGeneration == decisionGeneration
            lock.unlock()
            if stillSameGeneration {
                DiagnosticLog.info(.sse, "foreground SSE stream reconnected")
                publish(OpencodeEvent(type: "stream.reconnected", sessionID: nil))
            }
        }
        publishSnapshot()
    }

    private func publish(_ event: OpencodeEvent) {
        lock.lock()
        let conts = Array(subscriberContinuations.values)
        lock.unlock()
        for continuation in conts {
            continuation.yield(event)
        }
    }

    private func applyConnectionState(
        _ state: EventStreamConnectionState,
        generation: UInt64? = nil
    ) {
        lock.lock()
        if let generation, generation != streamGeneration {
            lock.unlock()
            return
        }
        let previous = _connectionState
        _connectionState = state
        let conts = Array(connectionStateContinuations.values)
        lock.unlock()
        if previous != state {
            DiagnosticLog.debug(.sse, "foreground SSE state \(previous) -> \(state)")
        }
        for continuation in conts {
            continuation.yield(state)
        }
    }

    private func publishSnapshot() {
        lock.lock()
        let config = appliedConfig
        let required = config.map {
            SseSubscribeTargets.targets(protocol: $0.serverProtocol, directories: $0.directories).map(\.key)
        } ?? []
        let live = leases.filter { $0.value.isLive }.map(\.key)
        _snapshot = LiveConnectionSnapshot(
            config: config,
            generation: streamGeneration,
            connectionState: _connectionState,
            gap: currentGap,
            requiredTargets: required,
            liveTargets: live
        )
        let conts = Array(snapshotContinuations.values)
        let snapshot = _snapshot
        lock.unlock()
        for continuation in conts {
            continuation.yield(snapshot)
        }
    }

    private func resolveConfig() async -> LiveConnectionConfig {
        let directories = await instanceDirectories()
        let profile = await profileID()
        let identity = await connectionIdentity()
        let serverProtocol = protocolHint()
        return LiveConnectionConfig(
            profileID: profile,
            connectionIdentity: identity,
            serverProtocol: serverProtocol,
            directories: directories
        )
    }

    private func stillCurrent(_ generation: UInt64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return occupancyTask != nil && streamGeneration == generation
    }

    private func openGapLocked(_ reason: LiveGapReason) {
        gapClock += 1
        currentGap = LiveGapRevision(generation: gapClock, reason: reason)
        emittedReconnectForCurrentGap = false
    }

    private func currentGeneration() -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return streamGeneration
    }

    private func isOccupying() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return occupancyTask != nil
    }

    private func isRequired(_ key: String) -> Bool {
        lock.lock()
        let config = appliedConfig
        lock.unlock()
        guard let config else { return false }
        return SseSubscribeTargets.targets(protocol: config.serverProtocol, directories: config.directories)
            .contains { $0.key == key }
    }

    private func cancelAllLeasesLocked() {
        leases.values.forEach { $0.cancel() }
        leases.removeAll()
    }

    private final class Lease: @unchecked Sendable {
        let target: SseSubscribeTargets.Target
        let activity = ActivityBox()
        private let liveLock = NSLock()
        private var _live = false
        var loopTask: Task<Void, Never>?
        var streamTask: Task<Void, any Error>?
        private var attempt = 0

        func nextAttempt() -> Int {
            liveLock.lock()
            defer { liveLock.unlock() }
            attempt += 1
            return attempt
        }

        func currentAttempt() -> Int {
            liveLock.lock()
            defer { liveLock.unlock() }
            return attempt
        }

        var isLive: Bool {
            liveLock.lock()
            defer { liveLock.unlock() }
            return _live
        }

        init(target: SseSubscribeTargets.Target) {
            self.target = target
        }

        func setLive(_ live: Bool) {
            liveLock.lock()
            _live = live
            liveLock.unlock()
        }

        func cancel() {
            setLive(false)
            streamTask?.cancel()
            loopTask?.cancel()
            streamTask = nil
            loopTask = nil
        }
    }

    private final class RetryBox: @unchecked Sendable {
        private let lock = NSLock()
        private var value = 0

        var attempt: Int {
            lock.lock()
            defer { lock.unlock() }
            return value
        }

        func reset() {
            lock.lock()
            value = 0
            lock.unlock()
        }

        func increment() {
            lock.lock()
            value += 1
            lock.unlock()
        }
    }

    private final class ActivityBox: @unchecked Sendable {
        private let lock = NSLock()
        private var last = ContinuousClock.now

        func touch() {
            lock.lock()
            last = .now
            lock.unlock()
        }

        func elapsed() -> Duration {
            lock.lock()
            let last = last
            lock.unlock()
            return ContinuousClock.now - last
        }
    }
}
