import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
@Suite struct ServerHealthViewModelTests {
    private let now: Int64 = 1_700_000_000_000

    private static func scratchDefaults() -> UserDefaults {
        UserDefaults(suiteName: "ServerHealthViewModelTests.scratch.\(UUID().uuidString)")!
    }
    private let minute: Int64 = 60_000

    private func seededStore(profileID: String = "p1") -> InMemoryServerHealthStore {
        InMemoryServerHealthStore(
            pings: [
                .success(profileID: profileID, atMs: now - 2 * minute, latencyMs: 20, pid: 1, version: "1.4.0"),
                .failed(profileID: profileID, atMs: now - minute, failure: .network),
                .success(profileID: profileID, atMs: now - 3 * 60 * minute, latencyMs: 90)
            ],
            events: [
                .state(.live, profileID: profileID, atMs: now - 5 * 60 * minute),
                .state(.reconnecting, profileID: profileID, atMs: now - 30 * minute),
                .state(.live, profileID: profileID, atMs: now - 29 * minute)
            ]
        )
    }

    private func route(_ profileID: String = "p1", serverProtocol: ServerProtocol? = .v2) -> ServerHealthRoute {
        ServerHealthRoute(profileID: profileID, profileName: "Workstation", serverProtocol: serverProtocol)
    }

    @Test func activeProfileShowsLiveStatusAndADayReport() async {
        let monitor = ServerHealthFakeMonitor()
        monitor.liveStatus = ServerHealthLiveStatus(
            profileID: "p1",
            state: .live,
            lastPingMs: 21,
            lastVersion: "1.5.0",
            liveTargets: 2,
            requiredTargets: 2
        )
        let viewModel = ServerHealthViewModel(route: route(), monitor: monitor, store: seededStore(), now: { [now] in now })
        await viewModel.reload()

        let state = viewModel.state
        #expect(state.profileName == "Workstation")
        #expect(state.protocolLabel == "API v2")
        #expect(state.isActiveProfile)
        #expect(state.status == .live)
        #expect(state.currentPingMs == 21)
        #expect(state.serverVersion == "1.5.0")
        #expect(state.liveTargets == 2)
        #expect(state.recentPings == [20, nil])
        #expect(state.recentP95Ms == 20)
        #expect(state.range == .day)
        #expect(state.report?.range == .day)
        #expect(state.report?.drops == 1)
        #expect(state.report?.checks == 3)
    }

    @Test func otherProfileIsNotLive() async {
        let monitor = ServerHealthFakeMonitor()
        monitor.liveStatus = ServerHealthLiveStatus(
            profileID: "other",
            state: .live,
            lastPingMs: 21,
            lastVersion: "9.9.9",
            liveTargets: 1,
            requiredTargets: 1
        )
        let viewModel = ServerHealthViewModel(route: route(serverProtocol: nil), monitor: monitor, store: seededStore(), now: { [now] in now })
        await viewModel.reload()
        #expect(!viewModel.state.isActiveProfile)
        #expect(viewModel.state.status == .stopped)
        #expect(viewModel.state.currentPingMs == nil)
        #expect(viewModel.state.liveTargets == 0)
        #expect(viewModel.state.protocolLabel == nil)
        #expect(viewModel.state.serverVersion == "1.4.0")
    }

    @Test func selectRangeRebuildsTheReport() async {
        let viewModel = ServerHealthViewModel(
            route: route(),
            monitor: ServerHealthFakeMonitor(),
            store: seededStore(),
            now: { [now] in now }
        )
        await viewModel.reload()
        #expect(viewModel.state.report?.checks == 3)
        await viewModel.selectRange(.hour)
        #expect(viewModel.state.range == .hour)
        #expect(viewModel.state.report?.range == .hour)
        #expect(viewModel.state.report?.buckets.count == 60)
        #expect(viewModel.state.report?.checks == 2)
        await viewModel.selectRange(.week)
        #expect(viewModel.state.report?.buckets.count == 84)
    }

    @Test func emptyProfileHasAnEmptyReport() async {
        let viewModel = ServerHealthViewModel(
            route: route("new"),
            monitor: ServerHealthFakeMonitor(),
            store: seededStore(),
            now: { [now] in now }
        )
        await viewModel.reload()
        #expect(viewModel.state.report?.isEmpty == true)
    }

    @Test func observeReloadsWhenTheRevisionChanges() async {
        let monitor = ServerHealthFakeMonitor()
        monitor.revision = 3
        let store = seededStore()
        let viewModel = ServerHealthViewModel(route: route(), monitor: monitor, store: store, now: { [now] in now }, sleep: { _ in })
        await viewModel.observe()
        #expect(viewModel.state.report?.checks == 3)
    }

    @Test func serversQuickViewSummarisesTheActiveProfile() async throws {
        let suite = "ServerHealthViewModelTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let profileStore = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: ServerHealthTestCredentials())
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: ActiveConnectionManager(profileStore: profileStore),
            connectionProfileStore: profileStore,
            secureCredentialStore: ServerHealthTestCredentials(),
            sessionModelSelectionStore: UserDefaultsSessionModelSelectionStore(defaults: Self.scratchDefaults()),
            composerDraftStore: UserDefaultsComposerDraftStore(defaults: Self.scratchDefaults())
        )
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://192.0.2.10:4096/", username: "opencode", password: "test-password", name: "A")
        )
        let active = try #require(coordinator.currentProfile())
        let monitor = ServerHealthFakeMonitor()
        monitor.liveStatus = ServerHealthLiveStatus(
            profileID: active.id,
            state: .reconnecting,
            lastPingMs: nil,
            lastVersion: nil,
            liveTargets: 0,
            requiredTargets: 1
        )
        let viewModel = ServersViewModel(
            coordinator: coordinator,
            healthMonitor: monitor,
            healthStore: seededStore(profileID: active.id),
            now: { [now] in now }
        )
        await viewModel.refresh()

        let quick = try #require(viewModel.healthQuickView)
        #expect(quick.state == .reconnecting)
        #expect(quick.currentPingMs == nil)
        #expect(quick.recentPings == [20, nil])
        #expect(quick.reconnects == 1)
        #expect(abs((quick.uptimePercent ?? 0) - 299.0 / 300.0 * 100) < 0.001)

        viewModel.openHealth(profileID: active.id)
        #expect(viewModel.healthRoute == ServerHealthRoute(profile: active))
    }

    @Test func serversQuickViewIsNilWithoutAnActiveProfile() async {
        let profileStore = UserDefaultsConnectionProfileStore(
            defaults: UserDefaults(suiteName: "ServerHealthViewModelTests.empty.\(UUID().uuidString)")!,
            secureCredentialStore: ServerHealthTestCredentials()
        )
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: ActiveConnectionManager(profileStore: profileStore),
            connectionProfileStore: profileStore,
            secureCredentialStore: ServerHealthTestCredentials(),
            sessionModelSelectionStore: UserDefaultsSessionModelSelectionStore(defaults: Self.scratchDefaults()),
            composerDraftStore: UserDefaultsComposerDraftStore(defaults: Self.scratchDefaults())
        )
        let viewModel = ServersViewModel(
            coordinator: coordinator,
            healthMonitor: ServerHealthFakeMonitor(),
            healthStore: InMemoryServerHealthStore()
        )
        await viewModel.refresh()
        #expect(viewModel.healthQuickView == nil)
    }

    @Test func coordinatorPurgesServerHealthOnRemoveAndClear() async throws {
        let suite = "ServerHealthViewModelTests.purge.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let profileStore = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: ServerHealthTestCredentials())
        final class Purges: @unchecked Sendable {
            var calls: [String?] = []
        }
        let purges = Purges()
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: ActiveConnectionManager(profileStore: profileStore),
            connectionProfileStore: profileStore,
            secureCredentialStore: ServerHealthTestCredentials(),
            sessionModelSelectionStore: UserDefaultsSessionModelSelectionStore(defaults: Self.scratchDefaults()),
            composerDraftStore: UserDefaultsComposerDraftStore(defaults: Self.scratchDefaults()),
            purgeServerHealth: { profileID in
                await MainActor.run { purges.calls.append(profileID) }
            }
        )
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://192.0.2.10:4096/", username: "opencode", password: "test-password", name: "A")
        )
        let profile = try #require(coordinator.currentProfile())
        await coordinator.removeProfile(id: profile.id)
        await coordinator.clearConnectionData()
        #expect(purges.calls == [profile.id, nil])
    }
}

private final class ServerHealthTestCredentials: SecureCredentialStore, @unchecked Sendable {
    func readPassword(serverBaseURL: String) -> String? { nil }
    func writePassword(serverBaseURL: String, password: String) {}
    func deletePassword(serverBaseURL: String) {}
    func clearAll() {}
}
