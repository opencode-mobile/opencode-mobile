import Foundation
import OpenCodeKit

/// What first run sets up: the paired server, the project, and the model. The server's session
/// token lives in the Keychain through OpenCode Mobile's profile store; the rest in defaults.
struct HexSetup: Equatable, Sendable {
    var profile: ConnectionProfile
    var serverVersion: String?
    /// `hostname` from `GET /api/info` at pairing, the seed of the server's name.
    var serverHostname: String?
    var project: ChosenProject
    var model: ModelChoice
    /// The model's name as the server lists it, for Settings.
    var modelName: String?
    /// When the sign-in (the session token) was issued: at pairing and at each renewal. Nil for a
    /// setup paired before it was kept.
    var signedInAt: Date?

    var serverName: String {
        ServerName.name(hostname: serverHostname, fallbackAddress: ServerHost.firstAddress(of: profile))
    }
}

protocol HexSetupStoring: Sendable {
    func load() async -> HexSetup?
    func save(_ setup: HexSetup) async throws
    func clear() async
}

struct HexSetupStore: HexSetupStoring {
    private struct Stored: Codable {
        var serverVersion: String?
        var serverHostname: String?
        var project: ChosenProject
        var model: ModelChoice
        var modelName: String?
        var signedInAt: Date?
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
        return HexSetup(
            profile: profile,
            serverVersion: stored.serverVersion,
            serverHostname: stored.serverHostname,
            project: stored.project,
            model: stored.model,
            modelName: stored.modelName,
            signedInAt: stored.signedInAt
        )
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
        let stored = Stored(
            serverVersion: setup.serverVersion,
            serverHostname: setup.serverHostname,
            project: setup.project,
            model: setup.model,
            modelName: setup.modelName,
            signedInAt: setup.signedInAt
        )
        defaults.set(try JSONEncoder().encode(stored), forKey: Self.key)
    }

    /// Forget this computer: the setup, and every profile with its password (the Keychain entry
    /// goes with `removeProfile`; `clearAll` alone leaves it).
    func clear() async {
        defaults.removeObject(forKey: Self.key)
        for profile in await profiles.listProfiles() {
            await profiles.removeProfile(id: profile.id)
        }
        await profiles.clearAll()
    }
}

/// What Forget this computer also clears on the phone (app plan, "Forget this computer"): today's
/// session's last date and id, its saved card and its Ask history. The speech model, Appearance
/// and the developer unlock stay.
enum ForgetComputer {
    static let keys = [DailySessionDefaults.key, DailySessionDefaults.sessionKey, SummaryDefaults.key, AskDefaults.key]

    static func clearSessionData(_ defaults: UserDefaults = .standard) {
        for key in keys { defaults.removeObject(forKey: key) }
    }
}
