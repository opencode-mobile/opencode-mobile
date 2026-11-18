import Foundation
import Testing
@testable import OpenCode_Mobile

/// Tapping a server on Servers makes it the active server (#1086).
@MainActor
@Suite struct ServersSwitchTests {
    private final class SaveCounter: @unchecked Sendable {
        var count = 0
    }

    private struct Fixture {
        let viewModel: ServersViewModel
        let coordinator: ConnectionSessionCoordinator
        let saves: SaveCounter
        let active: ConnectionProfile
        let other: ConnectionProfile
    }

    private static func scratchDefaults() -> UserDefaults {
        UserDefaults(suiteName: "ServersSwitchTests.scratch.\(UUID().uuidString)")!
    }

    /// Two saved servers; counts profile saves only after both exist.
    private func fixture() async throws -> Fixture {
        let profileStore = UserDefaultsConnectionProfileStore(
            defaults: Self.scratchDefaults(),
            secureCredentialStore: ServersSwitchTestCredentials()
        )
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: ActiveConnectionManager(profileStore: profileStore),
            connectionProfileStore: profileStore,
            secureCredentialStore: ServersSwitchTestCredentials(),
            sessionModelSelectionStore: UserDefaultsSessionModelSelectionStore(defaults: Self.scratchDefaults()),
            composerDraftStore: UserDefaultsComposerDraftStore(defaults: Self.scratchDefaults())
        )
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://192.0.2.10:4096/", username: "opencode", password: "test-password", name: "Work")
        )
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://198.51.100.20:4096/", username: "opencode", password: "test-password", name: "Home")
        )
        let active = try #require(coordinator.currentProfile())
        let other = try #require(await coordinator.listProfiles().first { $0.id != active.id })
        let saves = SaveCounter()
        coordinator.onProfileSaved { saves.count += 1 }
        let viewModel = ServersViewModel(
            coordinator: coordinator,
            healthMonitor: ServerHealthFakeMonitor(),
            healthStore: InMemoryServerHealthStore()
        )
        await viewModel.refresh()
        return Fixture(viewModel: viewModel, coordinator: coordinator, saves: saves, active: active, other: other)
    }

    @Test func tappingAnotherServerMakesItActive() async throws {
        let fixture = try await fixture()

        await fixture.viewModel.switchTo(profileID: fixture.other.id)

        #expect(fixture.viewModel.activeId == fixture.other.id)
        #expect(fixture.coordinator.currentProfile()?.id == fixture.other.id)
        #expect(fixture.saves.count == 1)
        #expect(fixture.viewModel.switchingID == nil)
    }

    @Test func tappingTheActiveServerDoesNothing() async throws {
        let fixture = try await fixture()

        await fixture.viewModel.switchTo(profileID: fixture.active.id)

        #expect(fixture.viewModel.activeId == fixture.active.id)
        #expect(fixture.saves.count == 0)
    }

    @Test func tappingAnUnknownServerDoesNothing() async throws {
        let fixture = try await fixture()

        await fixture.viewModel.switchTo(profileID: "missing")

        #expect(fixture.viewModel.activeId == fixture.active.id)
        #expect(fixture.saves.count == 0)
    }
}

private final class ServersSwitchTestCredentials: SecureCredentialStore, @unchecked Sendable {
    func readPassword(serverBaseURL: String) -> String? { nil }
    func writePassword(serverBaseURL: String, password: String) {}
    func deletePassword(serverBaseURL: String) {}
    func clearAll() {}
}
