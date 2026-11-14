import Foundation

/// What Screen Models read from the Server Health recorder.
@MainActor
protocol ServerHealthMonitoring: AnyObject {
    /// Goes up after every recorded check or event, and after a purge.
    var revision: Int { get }
    var liveStatus: ServerHealthLiveStatus { get }
    /// Yields now, then after every change to `revision` or `liveStatus`.
    func updates() -> AsyncStream<Void>
}

/// Records Server Health for the app's lifetime: connection states and stream failures from
/// the singleton live connection, and a health check every 30 s while the app is active.
/// It observes the live connection; it never opens streams of its own.
@MainActor
final class ServerHealthMonitor: ServerHealthMonitoring {
    enum Input: Sendable {
        case snapshot(LiveConnectionSnapshot)
        case occupancy(StreamOccupancy)
        case streamFailure(ServerHealthFailureCategory)
        case ping(profileID: String, atMs: Int64, outcome: ServerHealthPingOutcome)
    }

    static let defaultPingInterval: Duration = .seconds(30)

    private(set) var revision = 0
    private(set) var liveStatus = ServerHealthLiveStatus.stopped

    private let liveConnection: any LiveConnection
    private let worldSignals: any WorldSignals
    private let streamFailures: @Sendable () -> AsyncStream<ServerHealthFailureCategory>
    private let store: any ServerHealthStore
    private let pinger: any ServerHealthPinging
    private let pingTarget: @MainActor () -> ServerHealthPingTarget?
    private let now: @MainActor () -> Int64
    private let sleep: @Sendable (Duration) async throws -> Void
    private let pingInterval: Duration

    private var started = false
    private var inputContinuation: AsyncStream<Input>.Continuation?
    private var observerTasks: [Task<Void, Never>] = []
    private var pingTask: Task<Void, Never>?
    private var pingProfileID: String?

    private var occupancy: StreamOccupancy = .stopped
    private var snapshot = LiveConnectionSnapshot()
    private var currentProfileID: String?
    private var lastState: [String: ServerHealthConnectionState] = [:]
    private var seededStates: Set<String> = []
    private var failureRecordedSinceLive: [String: Bool] = [:]
    private var lastProcess: [String: ServerHealthProcessIdentity] = [:]
    private var loadedProcess: Set<String> = []
    private var lastPingMs: Int?
    private var lastPruneMs: Int64?
    private var updateContinuations: [UUID: AsyncStream<Void>.Continuation] = [:]

    init(
        liveConnection: any LiveConnection,
        worldSignals: any WorldSignals,
        streamFailures: @escaping @Sendable () -> AsyncStream<ServerHealthFailureCategory>,
        store: any ServerHealthStore,
        pinger: any ServerHealthPinging,
        pingTarget: @escaping @MainActor () -> ServerHealthPingTarget?,
        now: @escaping @MainActor () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        pingInterval: Duration = ServerHealthMonitor.defaultPingInterval
    ) {
        self.liveConnection = liveConnection
        self.worldSignals = worldSignals
        self.streamFailures = streamFailures
        self.store = store
        self.pinger = pinger
        self.pingTarget = pingTarget
        self.now = now
        self.sleep = sleep
        self.pingInterval = pingInterval
    }

    /// Starts observing. Safe to call more than once.
    func start() {
        guard !started else { return }
        started = true
        let (inputs, continuation) = AsyncStream.makeStream(of: Input.self)
        inputContinuation = continuation
        let live = liveConnection
        let world = worldSignals
        let failures = streamFailures
        observerTasks = [
            Task { [weak self] in
                await self?.pruneIfDue(force: true)
                for await input in inputs {
                    guard let self else { return }
                    await self.handle(input)
                }
            },
            Task {
                for await snapshot in live.snapshotUpdates() {
                    continuation.yield(.snapshot(snapshot))
                }
            },
            Task {
                for await occupancy in world.occupancyUpdates() {
                    continuation.yield(.occupancy(occupancy))
                }
            },
            Task {
                for await category in failures() {
                    continuation.yield(.streamFailure(category))
                }
            }
        ]
    }

    func stop() {
        observerTasks.forEach { $0.cancel() }
        observerTasks = []
        inputContinuation?.finish()
        inputContinuation = nil
        stopPingLoop()
        started = false
    }

    func updates() -> AsyncStream<Void> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: Void.self, bufferingPolicy: .bufferingNewest(1))
        updateContinuations[id] = continuation
        continuation.yield()
        continuation.onTermination = { [weak self] _ in
            Task { @MainActor in self?.updateContinuations.removeValue(forKey: id) }
        }
        return stream
    }

    /// Drops what is cached for a purged profile (nil: every profile) and tells readers to reload.
    func forget(profileID: String?) {
        if let profileID {
            lastState.removeValue(forKey: profileID)
            seededStates.remove(profileID)
            failureRecordedSinceLive.removeValue(forKey: profileID)
            lastProcess.removeValue(forKey: profileID)
            loadedProcess.remove(profileID)
        } else {
            lastState = [:]
            seededStates = []
            failureRecordedSinceLive = [:]
            lastProcess = [:]
            loadedProcess = []
        }
        if profileID == nil || profileID == currentProfileID {
            lastPingMs = nil
        }
        revision += 1
        refreshLiveStatus()
        publish()
    }

    /// Applies one input. Inputs run one at a time from `start()`; tests call this directly.
    func handle(_ input: Input) async {
        switch input {
        case let .snapshot(next):
            snapshot = next
            let profile = Self.profileID(of: next) ?? pingTarget()?.profileID
            if profile != currentProfileID {
                if let previous = currentProfileID {
                    await record(.stopped, for: previous)
                }
                currentProfileID = profile
                lastPingMs = nil
                if let profile { await loadProcessIfNeeded(profile) }
                updatePingLoop(restart: true)
            }
            await recordCurrentState()
        case let .occupancy(next):
            occupancy = next
            updatePingLoop(restart: false)
            await recordCurrentState()
        case let .streamFailure(category):
            if let profile = currentProfileID, failureRecordedSinceLive[profile] != true {
                failureRecordedSinceLive[profile] = true
                await append(event: .failure(category, profileID: profile, atMs: now()))
            }
        case let .ping(profileID, atMs, outcome):
            await recordPing(profileID: profileID, atMs: atMs, outcome: outcome)
        }
        await pruneIfDue(force: false)
        refreshLiveStatus()
        publish()
    }

    // MARK: - States

    private var effectiveState: ServerHealthConnectionState {
        occupancy == .stopped ? .stopped : ServerHealthConnectionState(snapshot.connectionState)
    }

    private func recordCurrentState() async {
        guard let profile = currentProfileID else { return }
        await record(effectiveState, for: profile)
    }

    private func record(_ state: ServerHealthConnectionState, for profile: String) async {
        if !seededStates.contains(profile) {
            seededStates.insert(profile)
            if lastState[profile] == nil {
                await seedState(for: profile)
            }
        }
        // Nothing recorded yet reads as stopped, so a profile never starts with a stopped event.
        guard (lastState[profile] ?? .stopped) != state else { return }
        lastState[profile] = state
        if state == .live {
            failureRecordedSinceLive[profile] = false
        }
        await append(event: .state(state, profileID: profile, atMs: now()))
    }

    /// Loads the last recorded state. If the app was killed while measuring, that state was
    /// never closed: close it with stopped at the last thing recorded, so the time until this
    /// launch does not count as live.
    private func seedState(for profile: String) async {
        guard let latestState = await store.events(profileID: profile, sinceMs: .max).last,
              let stored = latestState.connectionState
        else { return }
        guard stored != .stopped else {
            lastState[profile] = .stopped
            return
        }
        let laterEvent = await store.events(profileID: profile, sinceMs: latestState.atMs).last?.atMs ?? latestState.atMs
        let laterPing = await store.pings(profileID: profile, sinceMs: latestState.atMs).last?.atMs ?? latestState.atMs
        lastState[profile] = .stopped
        await append(event: .state(.stopped, profileID: profile, atMs: max(latestState.atMs, laterEvent, laterPing)))
    }

    private nonisolated static func profileID(of snapshot: LiveConnectionSnapshot) -> String? {
        guard let id = snapshot.config?.profileID, !id.isEmpty else { return nil }
        return id
    }

    // MARK: - Checks

    private func updatePingLoop(restart: Bool) {
        let shouldRun = occupancy == .active && currentProfileID != nil
        if !shouldRun {
            stopPingLoop()
            return
        }
        if restart || pingTask == nil || pingProfileID != currentProfileID {
            startPingLoop(profileID: currentProfileID!)
        }
    }

    private func startPingLoop(profileID: String) {
        pingTask?.cancel()
        pingProfileID = profileID
        let pinger = pinger
        let sleep = sleep
        let interval = pingInterval
        pingTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                if let target = self.pingTarget(), target.profileID == profileID {
                    let atMs = self.now()
                    let outcome = await pinger.ping(target)
                    guard !Task.isCancelled else { return }
                    if let outcome {
                        self.inputContinuation?.yield(.ping(profileID: profileID, atMs: atMs, outcome: outcome))
                    }
                }
                do {
                    try await sleep(interval)
                } catch {
                    return
                }
            }
        }
    }

    private func stopPingLoop() {
        pingTask?.cancel()
        pingTask = nil
        pingProfileID = nil
    }

    private func recordPing(profileID: String, atMs: Int64, outcome: ServerHealthPingOutcome) async {
        switch outcome {
        case let .success(latencyMs, pid, version):
            await loadProcessIfNeeded(profileID)
            if let previous = lastProcess[profileID], Self.isRestart(previous: previous, pid: pid, version: version) {
                await append(event: .restart(
                    version: version,
                    previousVersion: previous.version,
                    profileID: profileID,
                    atMs: atMs
                ))
            }
            if pid != nil || version != nil {
                lastProcess[profileID] = ServerHealthProcessIdentity(pid: pid, version: version)
            }
            await append(ping: .success(profileID: profileID, atMs: atMs, latencyMs: latencyMs, pid: pid, version: version))
            if profileID == currentProfileID { lastPingMs = latencyMs }
        case let .failure(category):
            await append(ping: .failed(profileID: profileID, atMs: atMs, failure: category))
            if profileID == currentProfileID { lastPingMs = nil }
        }
    }

    /// A different pid, or no pid on either side and a different version.
    nonisolated static func isRestart(previous: ServerHealthProcessIdentity, pid: Int?, version: String?) -> Bool {
        if let oldPid = previous.pid, let newPid = pid {
            return oldPid != newPid
        }
        if previous.pid == nil, pid == nil, let oldVersion = previous.version, let newVersion = version {
            return oldVersion != newVersion
        }
        return false
    }

    private func loadProcessIfNeeded(_ profile: String) async {
        guard !loadedProcess.contains(profile) else { return }
        loadedProcess.insert(profile)
        if lastProcess[profile] == nil, let stored = await store.lastPid(profileID: profile) {
            lastProcess[profile] = stored
        }
    }

    // MARK: - Store

    private func append(event: ServerHealthEvent) async {
        await store.appendEvent(event)
        revision += 1
    }

    private func append(ping: ServerHealthPing) async {
        await store.appendPing(ping)
        revision += 1
    }

    private func pruneIfDue(force: Bool) async {
        let current = now()
        if !force, let last = lastPruneMs, current - last < ServerHealthRetention.pruneIntervalMs { return }
        lastPruneMs = current
        await store.prune(olderThanMs: current - ServerHealthRetention.windowMs)
    }

    // MARK: - Status

    private func refreshLiveStatus() {
        guard let profile = currentProfileID else {
            liveStatus = .stopped
            return
        }
        liveStatus = ServerHealthLiveStatus(
            profileID: profile,
            state: effectiveState,
            lastPingMs: lastPingMs,
            lastVersion: lastProcess[profile]?.version,
            liveTargets: snapshot.liveTargets.count,
            requiredTargets: snapshot.requiredTargets.count
        )
    }

    private func publish() {
        for continuation in updateContinuations.values {
            continuation.yield()
        }
    }
}
