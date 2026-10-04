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
    private let credentialFingerprint: @Sendable () async -> String
    private let protocolHint: @Sendable () -> ServerProtocol
    private let cachedProtocol: @Sendable () -> ServerProtocol?
    private let protocolProbe: (@Sendable () async -> ServerProtocol)?
    private let engineProvider: @Sendable () -> ConnectionEngine
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
    private var lastDirectoriesByIdentity: [LiveConnectionConfig.Identity: [String]] = [:]
    private var configEpoch: UInt64 = 0
    private var leases: [String: Lease] = [:]
    private var subscriberContinuations: [UUID: AsyncStream<OpencodeEvent>.Continuation] = [:]
    private var connectionStateContinuations: [UUID: AsyncStream<EventStreamConnectionState>.Continuation] = [:]
    private var snapshotContinuations: [UUID: AsyncStream<LiveConnectionSnapshot>.Continuation] = [:]
    private var authTerminal = false
    private var protocolConfirmed = false
    private let reconnectWake = ReconnectWakeGate()

    private let idleTimeout: Duration
    private let firstFrameTimeout: Duration

    init(
        api: EventsAPIClient,
        adapter: EventDomainAdapter,
        deviceNetworkPath: any DeviceNetworkPath = ReadyDeviceNetworkPath(),
        idleTimeout: Duration = .seconds(45),
        firstFrameTimeout: Duration = .seconds(3),
        instanceDirectories: @escaping @Sendable () async -> [String] = { [] },
        configurationRevisions: @escaping @Sendable () -> AsyncStream<Void> = { AsyncStream { $0.finish() } },
        connectionEpoch: ConnectionEpoch = .shared,
        profileID: @escaping @Sendable () async -> String = { "" },
        connectionIdentity: (@Sendable () async -> String)? = nil,
        credentialFingerprint: (@Sendable () async -> String)? = nil,
        protocolHint: (@Sendable () -> ServerProtocol)? = nil,
        cachedProtocol: (@Sendable () -> ServerProtocol?)? = nil,
        protocolProbe: (@Sendable () async -> ServerProtocol)? = nil,
        engineProvider: (@Sendable () -> ConnectionEngine)? = nil
    ) {
        self.api = api
        self.eventIngress = SseEventIngress(adapter: adapter)
        self.deviceNetworkPath = deviceNetworkPath
        self.idleTimeout = idleTimeout
        self.firstFrameTimeout = firstFrameTimeout
        self.instanceDirectories = instanceDirectories
        self.configurationRevisions = configurationRevisions
        self.connectionEpoch = connectionEpoch
        self.profileID = profileID
        self.connectionIdentity = connectionIdentity ?? profileID
        self.credentialFingerprint = credentialFingerprint ?? { "" }
        self.protocolHint = protocolHint ?? { api.eventProtocol }
        self.cachedProtocol = cachedProtocol ?? { nil }
        self.protocolProbe = protocolProbe
        self.engineProvider = engineProvider ?? { .opencode }
    }

    func publishSynthetic(_ event: OpencodeEvent) {
        publish(event)
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
        if engineProvider() == .t3 {
            startT3IdleConnected()
            return
        }
        lock.lock()
        if authTerminal {
            lock.unlock()
            return
        }
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
        occupancyTask = Task(priority: .userInitiated) { [weak self] in
            await self?.beginOccupancy(generation: generation, serial: serial)
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(3_600))
            }
        }
        lock.unlock()
        _ = connectionEpoch.bump()
    }

    private func startT3IdleConnected() {
        lock.lock()
        occupancyTask?.cancel()
        revisionTask?.cancel()
        protocolProbeTask?.cancel()
        occupancyTask = nil
        revisionTask = nil
        protocolProbeTask = nil
        let leaseWork = cancelAllLeasesLocked()
        occupancySerial += 1
        streamGeneration += 1
        let generation = streamGeneration
        occupancyTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(3_600))
                _ = self
            }
        }
        appliedConfig = nil
        pendingStartConfig = nil
        authTerminal = false
        protocolConfirmed = true
        _connectionState = .connected
        _snapshot = LiveConnectionSnapshot(
            config: nil,
            generation: generation,
            connectionState: .connected,
            requiredTargets: ["t3"],
            liveTargets: ["t3"]
        )
        let stateConts = Array(connectionStateContinuations.values)
        let snapConts = Array(snapshotContinuations.values)
        let snapshot = _snapshot
        lock.unlock()
        DiagnosticLog.info(.sse, "foreground SSE idle for T3 engine")
        for continuation in stateConts {
            continuation.yield(.connected)
        }
        for continuation in snapConts {
            continuation.yield(snapshot)
        }
        for loop in leaseWork.loops {
            loop.cancel()
        }
        for stream in leaseWork.streams {
            stream.cancel()
        }
    }

    func stop() async {
        lock.lock()
        let hadTask = occupancyTask != nil
        let state = _connectionState
        let wasFullyConnected = overall.wasFullyConnected
        occupancySerial += 1
        inFlightStartApplies = 0
        streamGeneration += 1
        let generation = streamGeneration
        let occupancy = occupancyTask
        occupancyTask = nil
        occupancy?.cancel()
        let revision = revisionTask
        revisionTask = nil
        revision?.cancel()
        let probe = protocolProbeTask
        protocolProbeTask = nil
        probe?.cancel()
        pendingStartConfig = nil
        authTerminal = false
        protocolConfirmed = false
        let leaseWork = cancelAllLeasesLocked()
        eventIngress.reset()
        appliedConfig = nil
        overall = LiveOverallFlags(wasFullyConnected: wasFullyConnected)
        lock.unlock()
        api.retireStreamingSession(token: generation &- 1)
        api.retireStreamingSessions(olderThan: generation)
        if hadTask || state != .idle {
            DiagnosticLog.info(.sse, "foreground SSE stream stopping")
        }
        occupancy?.cancel()
        revision?.cancel()
        probe?.cancel()
        if let occupancy {
            await Self.joinWithTimeout(occupancy)
        }
        if let revision {
            await Self.joinWithTimeout(revision)
        }
        if let probe {
            await Self.joinWithTimeout(probe)
        }
        for loop in leaseWork.loops {
            await Self.joinWithTimeout(loop)
        }
        for stream in leaseWork.streams {
            await Self.joinWithTimeout(stream)
        }
        applyConnectionState(.idle, generation: generation)
        publishSnapshot()
    }

    /// Race-join a cancelled task. `TaskGroup` cannot be used here: it waits for
    /// every child, so a non-cooperative `await task.result` would ignore the
    /// timeout sibling and hang `stop()`.
    private static func joinWithTimeout<Success, Failure: Error>(
        _ task: Task<Success, Failure>,
        timeout: Duration = .milliseconds(1_500)
    ) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let once = ResumeOnce()
            Task {
                _ = await task.result
                once.resume(continuation)
            }
            Task {
                try? await Task.sleep(for: timeout)
                once.resume(continuation)
            }
        }
    }

    private final class ResumeOnce: @unchecked Sendable {
        private let lock = NSLock()
        private var resumed = false

        func resume(_ continuation: CheckedContinuation<Void, Never>) {
            lock.lock()
            defer { lock.unlock() }
            guard !resumed else { return }
            resumed = true
            continuation.resume()
        }
    }

    func onReconnectWake() {
        lock.lock()
        for lease in leases.values {
            lease.retry?.reset()
        }
        lock.unlock()
        reconnectWake.signal()
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
                retargetIdentity(config)
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
        let resumeChrome = overall.wasFullyConnected
        lock.unlock()
        applyConnectionState(
            resumeChrome ? .reconnecting : .connecting,
            generation: generationAfterWait
        )
        let resolvedConfig = await resolveProtocolBeforeLeases(latest)
        guard stillCurrent(generationAfterWait), isOccupying() else { return }
        if stillCurrent(generationAfterWait) {
            if let appliedNow,
               LiveConnectionApply.change(applied: appliedNow, requested: resolvedConfig) == .identity {
                retargetIdentity(resolvedConfig)
            } else {
                reconcileLeases(resolvedConfig, generation: generationAfterWait)
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
        let hadCached = cachedProtocol() != nil
        let resolved = await protocolProbe()
        guard stillCurrent(generation), isOccupying() else { return }
        if hadCached || cachedProtocol() != nil {
            lock.lock()
            protocolConfirmed = true
            lock.unlock()
        }
        lock.lock()
        let applied = appliedConfig
        lock.unlock()
        guard let applied, resolved != applied.serverProtocol else { return }
        DiagnosticLog.info(
            .sse,
            "SSE protocol resolved \(applied.serverProtocol.wireValue) -> \(resolved.wireValue); reconnecting"
        )
        lock.lock()
        protocolConfirmed = true
        lock.unlock()
        retargetIdentity(applied.with(serverProtocol: resolved))
    }

    private func resolveProtocolBeforeLeases(_ config: LiveConnectionConfig) async -> LiveConnectionConfig {
        if let cached = cachedProtocol() {
            lock.lock()
            protocolConfirmed = true
            lock.unlock()
            return config.with(serverProtocol: cached)
        }
        guard let protocolProbe else { return config }
        let resolved = await protocolProbe()
        if cachedProtocol() != nil {
            lock.lock()
            protocolConfirmed = true
            lock.unlock()
        }
        return config.with(serverProtocol: resolved)
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
            retargetIdentity(effective)
        case .directories:
            reconcileLeases(effective, generation: currentGeneration())
        case .none:
            break
        }
    }

    private func retargetIdentity(_ config: LiveConnectionConfig) {
        guard isOccupying() else { return }
        lock.lock()
        streamGeneration += 1
        let generation = streamGeneration
        appliedConfig = config
        eventIngress.reset()
        cancelAllLeasesLocked()
        lock.unlock()
        _ = connectionEpoch.bump()
        guard stillCurrent(generation) else { return }
        reconcileLeases(config, generation: generation)
    }

    private func reconcileLeases(_ config: LiveConnectionConfig, generation: UInt64) {
        guard stillCurrent(generation), isOccupying() else { return }
        if engineProvider() == .t3 {
            startT3IdleConnected()
            return
        }
        lock.lock()
        if authTerminal {
            lock.unlock()
            return
        }
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
        let retry = RetryBox()
        lease.retry = retry
        lease.loopTask = Task { [weak self, weak lease] in
            guard let self, let lease else { return }
            while !Task.isCancelled, generation == self.currentGeneration(), self.isOccupying() {
                if self.isAuthTerminal() { return }
                guard self.isRequired(target.key) else { return }
                await self.deviceNetworkPath.waitUntilReady()
                guard generation == self.currentGeneration(), self.isRequired(target.key) else { return }
                if self.isAuthTerminal() { return }
                // A wake already pending before this attempt must not skip the backoff
                // after it fails. A wake during the attempt sets pending again.
                _ = self.reconnectWake.consumePending()
                lease.activity.touch()
                let attemptId = lease.nextAttempt()
                let handshake = HandshakeBox()
                let streamTask = Task<SseAttemptOutcome, Error> {
                    handshake.attachCurrentStream()
                    self.api.installFreshStreamingSession(token: generation)
                    do {
                        return try await self.api.streamEvents(
                            path: target.path,
                            directory: target.directory,
                            sessionToken: generation,
                            onHTTPResponse: { statusCode in
                                guard lease.currentAttempt() == attemptId else { return }
                                lease.activity.touch()
                                DiagnosticLog.info(
                                    .sse,
                                    "GET /\(target.path) opened status=\(statusCode) protocol=\(self.api.eventProtocolWireValue)"
                                )
                                if (200 ..< 300).contains(statusCode) {
                                    handshake.startTimeout(self.firstFrameTimeout) {
                                        DiagnosticLog.warning(
                                            .sse,
                                            "SSE first frame timeout target=\(target.key)"
                                        )
                                        handshake.cancelAttachedStream()
                                    }
                                }
                            },
                            onActivity: {
                                lease.activity.touch()
                            },
                            onDataLine: { dataLine in
                                lease.activity.touch()
                                await self.publishDataLine(
                                    dataLine,
                                    generation: generation,
                                    handshake: handshake,
                                    onConnected: {
                                        guard lease.currentAttempt() == attemptId else { return }
                                        self.markLeaseLive(lease, generation: generation)
                                        retry.reset()
                                    },
                                    onUnexpectedFirstFrame: {
                                        withUnsafeCurrentTask { $0?.cancel() }
                                    }
                                )
                            }
                        )
                    } catch is CancellationError {
                        throw CancellationError()
                    } catch {
                        return .transport(error)
                    }
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
                var outcome: SseAttemptOutcome
                do {
                    let resolved = try await streamTask.value
                    if handshake.isConnected {
                        outcome = resolved
                    } else {
                        switch resolved {
                        case .http, .transport:
                            outcome = resolved
                        case .ended:
                            outcome = .transport(handshake.error ?? SseHandshakeError(reason: .timeout))
                        }
                    }
                } catch is CancellationError {
                    watchTask.cancel()
                    pathTask.cancel()
                    handshake.cancelTimeout()
                    if generation != self.currentGeneration() || !self.isRequired(target.key) {
                        return
                    }
                    if handshake.failed {
                        outcome = .transport(handshake.error ?? SseHandshakeError(reason: .timeout))
                    } else {
                        outcome = .transport(CancellationError())
                    }
                } catch {
                    outcome = .transport(error)
                }
                handshake.cancelTimeout()
                watchTask.cancel()
                pathTask.cancel()
                if lease.currentAttempt() == attemptId {
                    lease.streamTask = nil
                    self.markLeaseDead(lease, generation: generation)
                }
                guard generation == self.currentGeneration(), self.isRequired(target.key) else { return }
                if self.isAuthTerminal() { return }
                self.logAttemptOutcome(target.key, outcome)
                let decision = SseAttemptPolicy.decide(
                    outcome: outcome,
                    protocolConfirmed: self.isProtocolConfirmed(),
                    attempt: retry.attempt + 1
                )
                switch decision {
                case .terminal(.authFailed):
                    self.markAuthFailed(generation: generation, targetKey: target.key, outcome: outcome)
                    return
                case .terminal:
                    return
                case let .retry(afterMs):
                    if self.deviceNetworkPath.status == .unavailable {
                        continue
                    }
                    retry.increment()
                    DiagnosticLog.debug(.sse, "foreground SSE retry target=\(target.key) in \(afterMs)ms")
                    let wokeEarly = await self.reconnectWake.wait(milliseconds: afterMs)
                    if wokeEarly {
                        retry.reset()
                    }
                }
            }
        }
        return lease
    }

    private func logAttemptOutcome(_ targetKey: String, _ outcome: SseAttemptOutcome) {
        switch outcome {
        case .ended:
            DiagnosticLog.debug(.sse, "SSE attempt ended target=\(targetKey)")
        case let .http(status):
            DiagnosticLog.info(.sse, "SSE attempt http target=\(targetKey) status=\(status)")
        case let .transport(cause):
            DiagnosticLog.warning(
                .sse,
                "SSE attempt transport target=\(targetKey) error=\(String(describing: cause))"
            )
        }
    }

    private func markAuthFailed(
        generation: UInt64,
        targetKey: String,
        outcome: SseAttemptOutcome
    ) {
        let status: Int
        if case let .http(code) = outcome {
            status = code
        } else {
            status = 0
        }
        lock.lock()
        authTerminal = true
        lock.unlock()
        DiagnosticLog.warning(
            .sse,
            "SSE auth failed target=\(targetKey) status=\(status); stopping retries"
        )
        applyConnectionState(.authFailed, generation: generation)
        lock.lock()
        cancelAllLeasesLocked()
        lock.unlock()
        publishSnapshot()
    }

    private func publishDataLine(
        _ dataLine: String,
        generation: UInt64,
        handshake: HandshakeBox,
        onConnected: @escaping @Sendable () -> Void,
        onUnexpectedFirstFrame: @escaping @Sendable () -> Void
    ) async {
        guard generation == currentGeneration() else { return }
        if handshake.isPending {
            if eventIngress.isServerConnectedFrame(dataLine) {
                if handshake.succeed() {
                    onConnected()
                }
            } else if handshake.fail(.unexpectedFirstFrame) {
                onUnexpectedFirstFrame()
                return
            } else {
                return
            }
        } else if handshake.failed {
            return
        }
        await publishDataLine(dataLine, generation: generation)
    }

    private func publishDataLine(_ dataLine: String, generation: UInt64) async {
        guard generation == currentGeneration() else { return }
        let result = eventIngress.accept(dataLine)
        if result.parsed.isEmpty {
            if result.parseFailed {
                DiagnosticLog.warning(.sse, SseDiagnosticLogging.parseFailedMessage(dataLine))
                publishSnapshot()
                for event in result.toEmit {
                    _ = publishIfCurrent(event, generation: generation)
                }
            } else if let droppedType = result.droppedType {
                DiagnosticLog.debug(.sse, SseDiagnosticLogging.droppedMessage(type: droppedType))
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
        refreshOverallState(generation: generation)
    }

    private func refreshOverallState(generation: UInt64) {
        guard stillCurrent(generation) else { return }
        lock.lock()
        if authTerminal {
            let decisionGeneration = streamGeneration
            lock.unlock()
            applyConnectionState(.authFailed, generation: decisionGeneration)
            publishSnapshot()
            return
        }
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
        let serverProtocol = currentServerProtocol()
        let fingerprint = await credentialFingerprint()
        let key = LiveConnectionConfig.Identity(
            profileID: profile,
            connectionIdentity: identity,
            serverProtocol: serverProtocol,
            credentialFingerprint: fingerprint
        )
        lock.lock()
        let directories = lastDirectoriesByIdentity[key] ?? []
        lock.unlock()
        return LiveConnectionConfig(
            profileID: profile,
            connectionIdentity: identity,
            serverProtocol: serverProtocol,
            directories: directories,
            credentialFingerprint: fingerprint
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
        let fingerprint = await credentialFingerprint()
        let serverProtocol = currentServerProtocol()
        return LiveConnectionConfig(
            profileID: profile,
            connectionIdentity: identity,
            serverProtocol: serverProtocol,
            directories: directories,
            credentialFingerprint: fingerprint
        )
    }

    private func currentServerProtocol() -> ServerProtocol {
        if let cached = cachedProtocol() {
            return cached
        }
        lock.lock()
        let applied = appliedConfig?.serverProtocol
        lock.unlock()
        return applied ?? protocolHint()
    }

    private func stillCurrent(_ generation: UInt64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return occupancyTask != nil && streamGeneration == generation
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

    private func isAuthTerminal() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return authTerminal
    }

    private func isProtocolConfirmed() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return protocolConfirmed
    }

    @discardableResult
    private func cancelAllLeasesLocked() -> CancelledLeaseWork {
        var loops: [Task<Void, Never>] = []
        var streams: [Task<SseAttemptOutcome, any Error>] = []
        for lease in leases.values {
            let taken = lease.cancel()
            if let loop = taken.loop { loops.append(loop) }
            if let stream = taken.stream { streams.append(stream) }
        }
        leases.removeAll()
        return CancelledLeaseWork(loops: loops, streams: streams)
    }

    private struct CancelledLeaseWork {
        let loops: [Task<Void, Never>]
        let streams: [Task<SseAttemptOutcome, any Error>]
    }

    private final class Lease: @unchecked Sendable {
        let target: SseSubscribeTargets.Target
        let activity = ActivityBox()
        private let liveLock = NSLock()
        private var _live = false
        var loopTask: Task<Void, Never>?
        var streamTask: Task<SseAttemptOutcome, any Error>?
        var retry: RetryBox?
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

        @discardableResult
        func cancel() -> (loop: Task<Void, Never>?, stream: Task<SseAttemptOutcome, any Error>?) {
            setLive(false)
            let loop = loopTask
            let stream = streamTask
            loop?.cancel()
            stream?.cancel()
            loopTask = nil
            streamTask = nil
            return (loop, stream)
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

    private final class HandshakeBox: @unchecked Sendable {
        private let lock = NSLock()
        private var state: State = .pending
        private var _error: SseHandshakeError?
        private var timeoutTask: Task<Void, Never>?
        private var cancelStream: (@Sendable () -> Void)?

        private enum State {
            case pending
            case connected
            case failed
        }

        var isPending: Bool {
            lock.lock()
            defer { lock.unlock() }
            return state == .pending
        }

        var isConnected: Bool {
            lock.lock()
            defer { lock.unlock() }
            return state == .connected
        }

        var failed: Bool {
            lock.lock()
            defer { lock.unlock() }
            return state == .failed
        }

        var error: SseHandshakeError? {
            lock.lock()
            defer { lock.unlock() }
            return _error
        }

        func attachCurrentStream() {
            withUnsafeCurrentTask { task in
                lock.lock()
                cancelStream = { task?.cancel() }
                lock.unlock()
            }
        }

        func cancelAttachedStream() {
            lock.lock()
            let cancel = cancelStream
            lock.unlock()
            cancel?()
        }

        func startTimeout(_ timeout: Duration, onTimeout: @escaping @Sendable () -> Void) {
            lock.lock()
            timeoutTask?.cancel()
            timeoutTask = Task {
                try? await Task.sleep(for: timeout)
                if self.fail(.timeout) {
                    onTimeout()
                }
            }
            lock.unlock()
        }

        func cancelTimeout() {
            lock.lock()
            timeoutTask?.cancel()
            timeoutTask = nil
            lock.unlock()
        }

        func succeed() -> Bool {
            lock.lock()
            timeoutTask?.cancel()
            timeoutTask = nil
            defer { lock.unlock() }
            guard state == .pending else { return false }
            state = .connected
            return true
        }

        func fail(_ reason: SseHandshakeError.Reason) -> Bool {
            lock.lock()
            defer { lock.unlock() }
            guard state == .pending else { return false }
            state = .failed
            _error = SseHandshakeError(reason: reason)
            return true
        }
    }

    private final class ReconnectWakeGate: @unchecked Sendable {
        private let lock = NSLock()
        private var waiters: [UUID: CheckedContinuation<Bool, Never>] = [:]
        private var pending = false

        func signal() {
            lock.lock()
            let waiting = waiters
            waiters.removeAll()
            if waiting.isEmpty {
                pending = true
            }
            lock.unlock()
            for continuation in waiting.values {
                continuation.resume(returning: true)
            }
        }

        /// Clears a wake that arrived before the attempt. Does not affect `wait()`.
        func consumePending() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            let wasPending = pending
            pending = false
            return wasPending
        }

        func wait(milliseconds: Int) async -> Bool {
            guard milliseconds > 0 else { return false }
            lock.lock()
            if pending {
                pending = false
                lock.unlock()
                return true
            }
            lock.unlock()
            let id = UUID()
            return await withTaskCancellationHandler {
                await withCheckedContinuation { continuation in
                    lock.lock()
                    if pending {
                        pending = false
                        lock.unlock()
                        continuation.resume(returning: true)
                        return
                    }
                    waiters[id] = continuation
                    lock.unlock()
                    Task {
                        try? await Task.sleep(for: .milliseconds(milliseconds))
                        self.finish(id, woke: false)
                    }
                }
            } onCancel: {
                finish(id, woke: false)
            }
        }

        private func finish(_ id: UUID, woke: Bool) {
            lock.lock()
            let continuation = waiters.removeValue(forKey: id)
            lock.unlock()
            continuation?.resume(returning: woke)
        }
    }
}
