import Foundation

protocol ConnectionProfileStore: Sendable {
    func configurationRevisions() -> AsyncStream<Void>
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
    func configurationRevisions() -> AsyncStream<Void> {
        AsyncStream { $0.finish() }
    }

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
    var serverURLs: [String]
    var engine: String?

    init(
        id: String,
        name: String,
        baseURL: String,
        username: String,
        serverProtocol: String?,
        serverURLs: [String],
        engine: String?
    ) {
        self.id = id
        self.name = name
        self.baseURL = baseURL
        self.username = username
        self.serverProtocol = serverProtocol
        self.serverURLs = serverURLs
        self.engine = engine
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case name
        case baseURL
        case username
        case serverProtocol
        case serverURLs
        case engine
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        baseURL = try container.decode(String.self, forKey: .baseURL)
        username = try container.decode(String.self, forKey: .username)
        serverProtocol = try container.decodeIfPresent(String.self, forKey: .serverProtocol)
        serverURLs = try container.decodeIfPresent([String].self, forKey: .serverURLs) ?? []
        engine = try container.decodeIfPresent(String.self, forKey: .engine)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(name, forKey: .name)
        try container.encode(baseURL, forKey: .baseURL)
        try container.encode(username, forKey: .username)
        try container.encodeIfPresent(serverProtocol, forKey: .serverProtocol)
        try container.encode(serverURLs, forKey: .serverURLs)
        try container.encodeIfPresent(engine, forKey: .engine)
    }
}

struct UserDefaultsConnectionProfileStore: ConnectionProfileStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let secureCredentialStore: SecureCredentialStore
    private let lock = NSLock()
    private let revisionSignal = LiveConnectionRevisionSignal()
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

    func configurationRevisions() -> AsyncStream<Void> {
        revisionSignal.stream()
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
        // The password goes to the Keychain first: a failed write leaves the catalog untouched.
        if let saved = next.profiles.first(where: { stored in
            stored.id == profile.id || ConnectionProfileIdentity.sameSavedServer(stored, profile)
        }) {
            try writePassword(saved, password: profile.password)
        }
        writeCatalog(next)
        copyLegacyWorktrees(from: previous, to: next)
        revisionSignal.yield()
    }

    func applyProfile(id: String) async {
        lock.lock()
        defer { lock.unlock() }
        writeCatalog(migratedCatalog().apply(id: id))
        revisionSignal.yield()
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
        revisionSignal.yield()
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
        revisionSignal.yield()
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
        revisionSignal.yield()
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
            // Legacy key migration: if the new key cannot be written, keep the old one so the
            // password is not lost; the next save surfaces the Keychain failure to the user.
            if (try? secureCredentialStore.writePassword(serverBaseURL: id, password: password)) != nil {
                secureCredentialStore.deletePassword(serverBaseURL: normalized)
            }
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
                    serverURLs: record.serverURLs,
                    username: record.username,
                    password: "",
                    serverProtocol: record.serverProtocol.flatMap(ServerProtocol.init(rawValue:)),
                    engine: record.engine.flatMap(ConnectionEngine.init(rawValue:)) ?? .opencode,
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
                    serverProtocol: profile.serverProtocol?.rawValue,
                    serverURLs: ConnectionProfileIdentity.normalizedAddressList(profile.serverURLs),
                    engine: profile.engine == .opencode ? nil : profile.engine.rawValue
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
        func matches(_ profile: ConnectionProfile) -> Bool {
            if ConnectionProfileIdentity.normalizeBaseURL(profile.baseURL) == normalized {
                return true
            }
            return profile.serverURLs.contains {
                ConnectionProfileIdentity.normalizeBaseURL($0) == normalized
            }
        }
        if let active = catalog.activeProfile(), matches(active) {
            return active
        }
        return catalog.profiles.first(where: matches)
    }

    private func writePassword(_ profile: ConnectionProfile, password: String) throws {
        try secureCredentialStore.writePassword(serverBaseURL: profile.id, password: password)
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
