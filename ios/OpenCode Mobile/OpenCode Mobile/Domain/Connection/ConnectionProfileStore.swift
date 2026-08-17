import Foundation

protocol ConnectionProfileStore: Sendable {
    func activeProfile() async -> ConnectionProfile?
    func listProfiles() async -> [ConnectionProfile]
    func saveProfile(_ profile: ConnectionProfile) async throws
    func applyProfile(id: String) async
    func removeProfile(id: String) async
    func addedWorktrees(serverBaseURL: String) async -> [String]
    func addWorktree(serverBaseURL: String, worktree: String) async
    func removeWorktree(serverBaseURL: String, worktree: String) async
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async
    func clearAll() async
}

extension ConnectionProfileStore {
    func listProfiles() async -> [ConnectionProfile] {
        if let active = await activeProfile() { return [active] }
        return []
    }

    func applyProfile(id _: String) async {}

    func removeProfile(id _: String) async {}
}

private struct StoredConnectionProfiles: Codable {
    var activeId: String?
    var profiles: [StoredConnectionProfile]
}

private struct StoredConnectionProfile: Codable {
    var id: String
    var name: String
    var baseURL: String
    var username: String
    var serverProtocol: String?
}

struct UserDefaultsConnectionProfileStore: ConnectionProfileStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let secureCredentialStore: SecureCredentialStore
    private let lock = NSLock()
    private let worktreePrefix = "addedProjectWorktrees."
    private let worktreeIdPrefix = "worktrees."
    private let catalogKey = "connection_profiles_v2"
    private let activeBaseURLKey = "active_profile_base_url"
    private let activeUsernameKey = "active_profile_username"
    private let activeProtocolKey = "active_profile_protocol"

    init(
        defaults: UserDefaults = .standard,
        secureCredentialStore: SecureCredentialStore = KeychainCredentialStore()
    ) {
        self.defaults = defaults
        self.secureCredentialStore = secureCredentialStore
    }

    func activeProfile() async -> ConnectionProfile? {
        lock.lock()
        defer { lock.unlock() }
        return migratedCatalog().activeProfile()?.withPassword(secureCredentialStore)
    }

    func listProfiles() async -> [ConnectionProfile] {
        lock.lock()
        defer { lock.unlock() }
        return migratedCatalog().profiles.map { $0.withPassword(secureCredentialStore) }
    }

    func saveProfile(_ profile: ConnectionProfile) async throws {
        lock.lock()
        defer { lock.unlock() }
        let previous = migratedCatalog()
        let next = try previous.save(profile)
        writeCatalog(next)
        copyLegacyWorktrees(from: previous, to: next)
        if let saved = next.profiles.first(where: { stored in
            stored.id == profile.id || ConnectionProfileIdentity.sameConnectionIdentity(
                leftURL: stored.baseURL,
                leftUsername: stored.username,
                rightURL: profile.baseURL,
                rightUsername: profile.username
            )
        }) {
            writePassword(saved, password: profile.password)
        }
    }

    func applyProfile(id: String) async {
        lock.lock()
        defer { lock.unlock() }
        writeCatalog(migratedCatalog().apply(id: id))
    }

    func removeProfile(id: String) async {
        lock.lock()
        defer { lock.unlock() }
        let catalog = migratedCatalog()
        let existing = catalog.profiles.first { $0.id == id }
        writeCatalog(catalog.remove(id: id))
        if let existing {
            secureCredentialStore.deletePassword(serverBaseURL: existing.id)
            secureCredentialStore.deletePassword(serverBaseURL: existing.baseURL)
            defaults.removeObject(forKey: worktreeIdPrefix + existing.id)
            defaults.removeObject(forKey: worktreePrefix + existing.baseURL)
        }
    }

    func addedWorktrees(serverBaseURL: String) async -> [String] {
        lock.lock()
        defer { lock.unlock() }
        let catalog = migratedCatalog()
        if let profile = resolveProfile(catalog: catalog, serverBaseURL: serverBaseURL) {
            let idKey = worktreeIdPrefix + profile.id
            if defaults.object(forKey: idKey) != nil {
                return defaults.stringArray(forKey: idKey) ?? []
            }
        }
        return defaults.stringArray(forKey: worktreePrefix + ConnectionProfileIdentity.normalizeBaseURL(serverBaseURL)) ?? []
    }

    func addWorktree(serverBaseURL: String, worktree: String) async {
        var current = await addedWorktrees(serverBaseURL: serverBaseURL)
        guard !current.contains(worktree) else { return }
        current.append(worktree)
        await setAddedWorktrees(serverBaseURL: serverBaseURL, worktrees: current)
    }

    func removeWorktree(serverBaseURL: String, worktree: String) async {
        var current = await addedWorktrees(serverBaseURL: serverBaseURL)
        current.removeAll { $0 == worktree }
        await setAddedWorktrees(serverBaseURL: serverBaseURL, worktrees: current)
    }

    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {
        lock.lock()
        defer { lock.unlock() }
        var seen = Set<String>()
        let ordered = worktrees.filter { seen.insert($0).inserted }
        let catalog = migratedCatalog()
        let key: String
        if let profile = resolveProfile(catalog: catalog, serverBaseURL: serverBaseURL) {
            key = worktreeIdPrefix + profile.id
            defaults.removeObject(forKey: worktreePrefix + ConnectionProfileIdentity.normalizeBaseURL(serverBaseURL))
            defaults.removeObject(forKey: worktreePrefix + ConnectionProfileIdentity.normalizeBaseURL(profile.baseURL))
        } else {
            key = worktreePrefix + ConnectionProfileIdentity.normalizeBaseURL(serverBaseURL)
        }
        defaults.set(ordered, forKey: key)
    }

    func clearAll() async {
        lock.lock()
        defer { lock.unlock() }
        defaults.removeObject(forKey: catalogKey)
        defaults.removeObject(forKey: activeBaseURLKey)
        defaults.removeObject(forKey: activeUsernameKey)
        defaults.removeObject(forKey: activeProtocolKey)
        let keys = defaults.dictionaryRepresentation().keys.filter {
            $0.hasPrefix(worktreePrefix) || $0.hasPrefix(worktreeIdPrefix)
        }
        for key in keys {
            defaults.removeObject(forKey: key)
        }
    }

    private func migratedCatalog() -> ConnectionProfileCatalog {
        if defaults.data(forKey: catalogKey) != nil {
            return loadCatalog()
        }
        guard let baseURL = defaults.string(forKey: activeBaseURLKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            !baseURL.isEmpty
        else {
            return ConnectionProfileCatalog()
        }
        let normalized = ConnectionProfileIdentity.normalizeBaseURL(baseURL)
        let username = defaults.string(forKey: activeUsernameKey)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedUsername = (username?.isEmpty == false) ? username! : ServerConnectionDefaults.username
        let id = UUID().uuidString
        var migrated = ConnectionProfile(
            baseURL: normalized,
            username: resolvedUsername,
            password: "",
            serverProtocol: defaults.string(forKey: activeProtocolKey).flatMap(ServerProtocol.init(rawValue:)),
            id: id,
            name: ConnectionProfileIdentity.hostLabel(normalized)
        )
        let catalog = ConnectionProfileCatalog(profiles: [migrated], activeId: id)
        writeCatalog(catalog)
        let legacyWorktrees = defaults.stringArray(forKey: worktreePrefix + normalized) ?? []
        if !legacyWorktrees.isEmpty {
            defaults.set(legacyWorktrees, forKey: worktreeIdPrefix + id)
        }
        if let password = secureCredentialStore.readPassword(serverBaseURL: normalized) {
            secureCredentialStore.writePassword(serverBaseURL: id, password: password)
            secureCredentialStore.deletePassword(serverBaseURL: normalized)
            migrated.password = password
        }
        defaults.removeObject(forKey: activeBaseURLKey)
        defaults.removeObject(forKey: activeUsernameKey)
        defaults.removeObject(forKey: activeProtocolKey)
        return catalog
    }

    private func loadCatalog() -> ConnectionProfileCatalog {
        guard let data = defaults.data(forKey: catalogKey),
              let stored = try? JSONDecoder().decode(StoredConnectionProfiles.self, from: data)
        else {
            return ConnectionProfileCatalog()
        }
        return ConnectionProfileCatalog(
            profiles: stored.profiles.map { record in
                ConnectionProfile(
                    baseURL: record.baseURL,
                    username: record.username,
                    password: "",
                    serverProtocol: record.serverProtocol.flatMap(ServerProtocol.init(rawValue:)),
                    id: record.id,
                    name: record.name
                )
            },
            activeId: stored.activeId
        )
    }

    private func writeCatalog(_ catalog: ConnectionProfileCatalog) {
        let stored = StoredConnectionProfiles(
            activeId: catalog.activeId,
            profiles: catalog.profiles.map { profile in
                StoredConnectionProfile(
                    id: profile.id,
                    name: profile.name,
                    baseURL: profile.baseURL,
                    username: profile.username,
                    serverProtocol: profile.serverProtocol?.rawValue
                )
            }
        )
        if let data = try? JSONEncoder().encode(stored) {
            defaults.set(data, forKey: catalogKey)
        }
    }

    private func copyLegacyWorktrees(from previous: ConnectionProfileCatalog, to next: ConnectionProfileCatalog) {
        for profile in next.profiles where previous.profiles.contains(where: { $0.id == profile.id }) == false {
            let inherited = defaults.stringArray(
                forKey: worktreePrefix + ConnectionProfileIdentity.normalizeBaseURL(profile.baseURL)
            ) ?? []
            let existing = defaults.stringArray(forKey: worktreeIdPrefix + profile.id) ?? []
            if !inherited.isEmpty, existing.isEmpty {
                defaults.set(inherited, forKey: worktreeIdPrefix + profile.id)
            }
        }
    }

    private func resolveProfile(catalog: ConnectionProfileCatalog, serverBaseURL: String) -> ConnectionProfile? {
        let normalized = ConnectionProfileIdentity.normalizeBaseURL(serverBaseURL)
        if let active = catalog.activeProfile(),
           ConnectionProfileIdentity.normalizeBaseURL(active.baseURL) == normalized
        {
            return active
        }
        return catalog.profiles.first {
            ConnectionProfileIdentity.normalizeBaseURL($0.baseURL) == normalized
        }
    }

    private func writePassword(_ profile: ConnectionProfile, password: String) {
        secureCredentialStore.writePassword(serverBaseURL: profile.id, password: password)
        secureCredentialStore.deletePassword(serverBaseURL: profile.baseURL)
    }
}

private extension ConnectionProfile {
    func withPassword(_ store: SecureCredentialStore) -> ConnectionProfile {
        var copy = self
        copy.password = store.readPassword(serverBaseURL: id)
            ?? store.readPassword(serverBaseURL: baseURL)
            ?? ""
        return copy
    }
}
