import Foundation

/// Occupancy owner for foreground SSE. Byte read, parse, and event yield stay
/// off MainActor. LiveStores filter before hopping back for UI-owned work
/// (OPE-140 Stage 2). Config apply, live policy, and event filtering live in
/// `LiveConnectionApply`, `LiveOverallPolicy`, and `SseEventIngress`.
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
    private let eventIngress: SseEventIngress
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
    /// Invalidates overlapping `start()` applies after `stop()` or a new occupancy.
    private var occupancySerial: UInt64 = 0
    /// Invalidates in-flight cancel/error handlers after `stop()` or a newer `start()`.
    private var streamGeneration: UInt64 = 0
    private var appliedConfig: LiveConnectionConfig?
    private var pendingStartConfig: LiveConnectionConfig?
    private var inFlightStartApplies = 0
    private var overall = LiveOverallFlags()
    private var currentGap: LiveGapRevision?
    private var gapClock: UInt64 = 0
    private var lastDirectoriesByIdentity: [LiveConnectionConfig.Identity: [String]] = [:]
    private var configEpoch: UInt64 = 0
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
        self.eventIngress = SseEventIngress(adapter: adapter)
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
            let stuckIdle = _connectionState == .idle && appliedConfig == nil
            if !stuckIdle {
                inFlightStartApplies += 1
                let serial = occupancySerial
                lock.unlock()
                Task { [weak self] in
                    defer { self?.endInFlightStartApply(serial) }
                    await self?.applyStartWhileOccupying()
                }
                return
            }
            occupancyTask?.cancel()
            occupancyTask = nil
        }
        DiagnosticLog.info(.sse, "foreground SSE stream starting")
        occupancySerial += 1
        let serial = occupancySerial
        streamGeneration += 1
        let generation = streamGeneration
        openGapLocked(.occupancyStart)
        occupancyTask = Task(priority: .userInitiated) { [weak self] in
            await self?.beginOccupancy(generation: generation, serial: serial)
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
        let pendingResume = overall.pendingResumeBarrier
            || overall.wasFullyConnected
            || overall.occupyingConnected
        occupancySerial += 1
        inFlightStartApplies = 0
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
        eventIngress.reset()
        appliedConfig = nil
        overall = LiveOverallFlags(pendingResumeBarrier: pendingResume)
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
        lock.unlock()
        guard occupancy != nil, isOccupying() else { return }
        let claim = claimConfigEpoch()
        let config = await resolveConfig()
        guard isLatestConfigClaim(claim) else { return }
        lock.lock()
        let occupancyCurrent = occupancyTask != nil && occupancyTask == occupancy
        let decision = LiveConnectionApply.startDecision(
            occupancyCurrent: occupancyCurrent,
            applied: appliedConfig,
            requested: config,
            generation: streamGeneration
        )
        if case .stashPending = decision {
            pendingStartConfig = config
        }
        lock.unlock()
        switch decision {
        case .ignore, .stashPending:
            break
        case .idempotent:
            DiagnosticLog.debug(.sse, "foreground SSE start ignored; already running")
        case let .reconcile(generation, config):
            guard stillCurrent(generation) else { return }
            reconcileLeases(config, generation: generation)
        case let .retarget(generation, config):
            guard stillCurrent(generation) else { return }
            retargetIdentity(config, reason: .configChanged)
        }
    }

    private func endInFlightStartApply(_ serial: UInt64) {
        lock.lock()
        if occupancySerial == serial {
            inFlightStartApplies = max(0, inFlightStartApplies - 1)
        }
        lock.unlock()
    }

    private func waitForInFlightStartApplies(_ serial: UInt64) async {
        while true {
            lock.lock()
            let inFlight = inFlightStartApplies
            let currentSerial = occupancySerial
            let occupying = occupancyTask != nil
            lock.unlock()
            if inFlight == 0 || currentSerial != serial || !occupying { return }
            await Task.yield()
        }
    }

    private func beginOccupancy(generation: UInt64, serial: UInt64) async {
        let config = await bootstrapConfig()
        guard stillCurrent(generation), isOccupying() else { return }
        await waitForInFlightStartApplies(serial)
        guard stillCurrent(generation), isOccupying() else { return }
        lock.lock()
        let pendingAtCommit = pendingStartConfig
        pendingStartConfig = nil
        let merged = pendingAtCommit ?? config
        appliedConfig = merged
        lock.unlock()
        publishSnapshot()
        await deviceNetworkPath.waitUntilReady()
        guard isOccupying() else { return }
        // Overlapping start/revision may have already applied newer directories
        // or identity without invalidating occupancy. Reconcile last-current
        // (pending mailbox, else applied, else pre-wait merge) on the current
        // generation — not the pre-wait bootstrap.
        lock.lock()
        let pending = pendingStartConfig
        pendingStartConfig = nil
        let latest = pending ?? appliedConfig ?? merged
        let generationAfterWait = streamGeneration
        let appliedNow = appliedConfig
        let resumeChrome = overall.wasFullyConnected || overall.pendingResumeBarrier
        lock.unlock()
        applyConnectionState(
            resumeChrome ? .reconnecting : .connecting,
            generation: generationAfterWait
        )
        if stillCurrent(generationAfterWait) {
            if let appliedNow,
               LiveConnectionApply.change(applied: appliedNow, requested: latest) == .identity {
                retargetIdentity(latest, reason: .configChanged)
            } else {
                reconcileLeases(latest, generation: generationAfterWait)
            }
        }
        let collectorGeneration = currentGeneration()
        guard stillCurrent(collectorGeneration) else { return }
        lock.lock()
        revisionTask?.cancel()
        revisionTask = Task { [weak self] in
            await self?.collectConfigurationRevisions(generation: collectorGeneration)
        }
        protocolProbeTask?.cancel()
        protocolProbeTask = Task { [weak self] in
            await self?.probeProtocolWhileOccupying(generation: collectorGeneration)
        }
        lock.unlock()
    }

    private func collectConfigurationRevisions(generation: UInt64) async {
        // Subscribe first so a directory mutation during the initial
        // resolve is buffered instead of lost. Then recheck current
        // worktrees so path/lease setup does not miss a revision.
        let revisions = configurationRevisions()
        await onConfigurationRevision(generation: generation)
        for await _ in revisions {
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
        let claim = claimConfigEpoch()
        let config = await resolveConfig()
        guard stillCurrent(generation), isOccupying() else { return }
        guard isLatestConfigClaim(claim) else { return }
        applyResolvedConfig(config, generation: generation)
    }

    private func applyResolvedConfig(_ config: LiveConnectionConfig, generation: UInt64) {
        // Last-current is appliedConfig once occupancy has committed a target.
        // pendingStartConfig is only a pre-commit mailbox. A leftover pending
        // must not override this fresh resolve and retarget back to an older
        // profile/protocol.
        lock.lock()
        let applied = appliedConfig
        let pending = pendingStartConfig
        pendingStartConfig = nil
        let effective = applied == nil ? (pending ?? config) : config
        lock.unlock()
        guard let applied else {
            reconcileLeases(effective, generation: generation)
            return
        }
        switch LiveConnectionApply.change(applied: applied, requested: effective) {
        case .identity:
            retargetIdentity(effective, reason: .configChanged)
        case .directories:
            reconcileLeases(effective, generation: currentGeneration())
        case .none:
            break
        }
    }

    private func retargetIdentity(_ config: LiveConnectionConfig, reason: LiveGapReason) {
        guard isOccupying() else { return }
        lock.lock()
        streamGeneration += 1
        let generation = streamGeneration
        openGapLocked(reason)
        appliedConfig = config
        eventIngress.reset()
        overall.occupyingConnected = false
        overall.emittedReconnectForCurrentGap = false
        overall.suppressReconnectForRequiredGrowth = false
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
        lastDirectoriesByIdentity[config.identity] = config.directories
        configEpoch += 1
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
        let result = eventIngress.accept(dataLine)
        if result.parsed.isEmpty {
            DiagnosticLog.warning(.sse, SseDiagnosticLogging.parseFailedMessage(dataLine))
            if result.parseFailed {
                lock.lock()
                openGapLocked(.parseFailed)
                lock.unlock()
                publishSnapshot()
                for event in result.toEmit {
                    _ = publishIfCurrent(event, generation: generation)
                }
            }
            return
        }
        for event in result.parsed {
            if let line = SseDiagnosticLogging.bufferMessage(for: event) {
                DiagnosticLog.info(.sse, line)
            } else if event.type != "server.heartbeat" {
                DiagnosticLog.debug(.sse, "SSE event type=\(event.type) session=\(event.sessionID ?? "-")")
            }
        }
        for event in result.toEmit {
            _ = publishIfCurrent(event, generation: generation)
        }
    }

    @discardableResult
    private func publishIfCurrent(_ event: OpencodeEvent, generation: UInt64) -> Bool {
        guard generation == currentGeneration() else { return false }
        publish(event)
        return true
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
        overall.occupyingConnected = false
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
        let decided = LiveOverallPolicy.decide(
            flags: overall,
            input: LiveOverallInput(
                required: required,
                live: live,
                pathLost: deviceNetworkPath.status == .unavailable
            )
        )
        if let reason = decided.openGap {
            recordGapLocked(reason)
        }
        overall = decided.flags
        let nextState = decided.connectionState
        let publishReconnect = decided.publishReconnect
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

    private func bootstrapConfig() async -> LiveConnectionConfig {
        let profile = await profileID()
        let identity = await connectionIdentity()
        let serverProtocol = protocolHint()
        let key = LiveConnectionConfig.Identity(
            profileID: profile,
            connectionIdentity: identity,
            serverProtocol: serverProtocol
        )
        lock.lock()
        let directories = lastDirectoriesByIdentity[key] ?? []
        lock.unlock()
        return LiveConnectionConfig(
            profileID: profile,
            connectionIdentity: identity,
            serverProtocol: serverProtocol,
            directories: directories
        )
    }

    private func claimConfigEpoch() -> UInt64 {
        lock.lock()
        configEpoch += 1
        let epoch = configEpoch
        lock.unlock()
        return epoch
    }

    private func isLatestConfigClaim(_ epoch: UInt64) -> Bool {
        currentConfigEpoch() == epoch
    }

    private func currentConfigEpoch() -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return configEpoch
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

    private func recordGapLocked(_ reason: LiveGapReason) {
        gapClock += 1
        currentGap = LiveGapRevision(generation: gapClock, reason: reason)
    }

    private func openGapLocked(_ reason: LiveGapReason) {
        recordGapLocked(reason)
        overall.emittedReconnectForCurrentGap = false
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
