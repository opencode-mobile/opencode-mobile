import Foundation
import Observation

/// Opens Server Health for one Connection Profile.
struct ServerHealthRoute: Identifiable, Hashable {
    let profileID: String
    let profileName: String
    let serverProtocol: ServerProtocol?

    var id: String { profileID }

    init(profileID: String, profileName: String, serverProtocol: ServerProtocol?) {
        self.profileID = profileID
        self.profileName = profileName
        self.serverProtocol = serverProtocol
    }

    init(profile: ConnectionProfile) {
        self.init(profileID: profile.id, profileName: profile.displayName, serverProtocol: profile.serverProtocol)
    }
}

struct ServerHealthUiState: Equatable {
    var profileName: String
    /// "API v1" or "API v2"; nil before the protocol is known.
    var protocolLabel: String?
    var status: ServerHealthConnectionState
    var currentPingMs: Int?
    var recentPings: [Int?]
    var recentP95Ms: Int?
    var range: ServerHealthRange
    var report: ServerHealthReport?
    var serverVersion: String?
    var liveTargets: Int
    var requiredTargets: Int
    /// Live status (state, current ping, event streams) applies only to the active profile.
    var isActiveProfile: Bool
}

/// Server Health for one profile. Works for any saved profile; only the active one is live.
@MainActor
@Observable
final class ServerHealthViewModel {
    static let defaultReloadThrottle: Duration = .seconds(2)

    private(set) var state: ServerHealthUiState

    @ObservationIgnored private let route: ServerHealthRoute
    @ObservationIgnored private let monitor: any ServerHealthMonitoring
    @ObservationIgnored private let store: any ServerHealthStore
    @ObservationIgnored private let now: () -> Int64
    @ObservationIgnored private let reloadThrottle: Duration
    @ObservationIgnored private let sleep: @Sendable (Duration) async -> Void
    @ObservationIgnored private var pings: [ServerHealthPing] = []
    @ObservationIgnored private var events: [ServerHealthEvent] = []
    @ObservationIgnored private var storedVersion: String?
    @ObservationIgnored private var loadedRevision: Int?
    @ObservationIgnored private var lastReload: ContinuousClock.Instant?

    init(
        route: ServerHealthRoute,
        monitor: any ServerHealthMonitoring,
        store: any ServerHealthStore,
        now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        reloadThrottle: Duration = ServerHealthViewModel.defaultReloadThrottle,
        sleep: @escaping @Sendable (Duration) async -> Void = { try? await Task.sleep(for: $0) }
    ) {
        self.route = route
        self.monitor = monitor
        self.store = store
        self.now = now
        self.reloadThrottle = reloadThrottle
        self.sleep = sleep
        state = ServerHealthUiState(
            profileName: route.profileName,
            protocolLabel: ServerHealthText.protocolLabel(route.serverProtocol),
            status: .stopped,
            currentPingMs: nil,
            recentPings: [],
            recentP95Ms: nil,
            range: .day,
            report: nil,
            serverVersion: nil,
            liveTargets: 0,
            requiredTargets: 0,
            isActiveProfile: false
        )
    }

    /// Follows the monitor until the task is cancelled: live fields at once, data reloads at
    /// most every `reloadThrottle`.
    func observe() async {
        for await _ in monitor.updates() {
            applyLiveStatus()
            guard monitor.revision != loadedRevision else { continue }
            if let lastReload {
                let elapsed = lastReload.duration(to: ContinuousClock.now)
                if elapsed < reloadThrottle {
                    await sleep(reloadThrottle - elapsed)
                    if Task.isCancelled { return }
                }
            }
            await reload()
        }
    }

    func selectRange(_ range: ServerHealthRange) async {
        guard range != state.range else { return }
        state.range = range
        rebuild()
    }

    /// Loads the last 7 days once and builds the report for the selected range from it.
    func reload() async {
        lastReload = ContinuousClock.now
        loadedRevision = monitor.revision
        let since = now() - ServerHealthRetention.windowMs
        pings = await store.pings(profileID: route.profileID, sinceMs: since)
        events = await store.events(profileID: route.profileID, sinceMs: since)
        storedVersion = await store.lastPid(profileID: route.profileID)?.version
        applyLiveStatus()
        rebuild()
    }

    private func applyLiveStatus() {
        let live = monitor.liveStatus
        let isActive = live.profileID == route.profileID
        state.isActiveProfile = isActive
        state.status = isActive ? live.state : .stopped
        state.currentPingMs = isActive ? live.lastPingMs : nil
        state.liveTargets = isActive ? live.liveTargets : 0
        state.requiredTargets = isActive ? live.requiredTargets : 0
        state.serverVersion = (isActive ? live.lastVersion : nil) ?? storedVersion
    }

    private func rebuild() {
        let nowMs = now()
        let recent = ServerHealthReportBuilder.recentPings(pings, nowMs: nowMs)
        state.recentPings = recent
        state.recentP95Ms = ServerHealthReportBuilder.percentile(recent.compactMap { $0 }.sorted(), 95)
        state.report = ServerHealthReportBuilder.build(pings: pings, events: events, nowMs: nowMs, range: state.range)
    }
}
