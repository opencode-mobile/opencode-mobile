import Foundation
import OpenCodeKit

/// What first run sets up: the paired server, the project, and the model. The server's session
/// token lives in the Keychain through OpenCode Mobile's profile store; the rest in defaults.
struct HexSetup: Equatable, Sendable {
    var profile: ConnectionProfile
    var serverVersion: String?
    var project: ChosenProject
    var model: ModelChoice
}

protocol HexSetupStoring: Sendable {
    func load() async -> HexSetup?
    func save(_ setup: HexSetup) async throws
    func clear() async
}

struct HexSetupStore: HexSetupStoring {
    private struct Stored: Codable {
        var serverVersion: String?
        var project: ChosenProject
        var model: ModelChoice
    }

    private static let key = "hexcode.setup.v1"

    private let defaults: UserDefaults
    private let profiles: any ConnectionProfileStore

    init(defaults: UserDefaults = .standard, profiles: any ConnectionProfileStore = UserDefaultsConnectionProfileStore()) {
        self.defaults = defaults
        self.profiles = profiles
    }

    func load() async -> HexSetup? {
        guard let data = defaults.data(forKey: Self.key),
              let stored = try? JSONDecoder().decode(Stored.self, from: data),
              let profile = await profiles.activeProfile(),
              !profile.password.isEmpty
        else { return nil }
        return HexSetup(profile: profile, serverVersion: stored.serverVersion, project: stored.project, model: stored.model)
    }

    /// Hex Code keeps one server: pairing again replaces the previous one.
    func save(_ setup: HexSetup) async throws {
        for existing in await profiles.listProfiles() where existing.id != setup.profile.id {
            await profiles.removeProfile(id: existing.id)
        }
        try await profiles.saveProfile(setup.profile)
        if let saved = await profiles.listProfiles().first {
            await profiles.applyProfile(id: saved.id)
        }
        let stored = Stored(serverVersion: setup.serverVersion, project: setup.project, model: setup.model)
        defaults.set(try JSONEncoder().encode(stored), forKey: Self.key)
    }

    func clear() async {
        defaults.removeObject(forKey: Self.key)
        await profiles.clearAll()
    }
}
