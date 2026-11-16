import Foundation

public protocol ConnectionProfileStore: Sendable {
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
    public func configurationRevisions() -> AsyncStream<Void> {
        AsyncStream { $0.finish() }
    }

    public func listProfiles() async -> [ConnectionProfile] {
        if let active = await activeProfile() { return [active] }
        return []
    }

    public func applyProfile(id _: String) async {}

    public func removeProfile(id _: String) async {}
}

private struct StoredConnectionProfiles: Codable {
    public var activeId: String?
    public var profiles: [StoredConnectionProfile]
}

private struct StoredConnectionProfile: Codable {
    public var id: String
    public var name: String
    public var baseURL: String
    public var username: String
    public var serverProtocol: String?
    public var serverURLs: [String]

    public init(
        id: String,
        name: String,
        baseURL: String,
        username: String,
        serverProtocol: String?,
        serverURLs: [String]
    ) {
        self.id = id
        self.name = name
        self.baseURL = baseURL
        self.username = username
        self.serverProtocol = serverProtocol
        self.serverURLs = serverURLs
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case name
        case baseURL
        case username
        case serverProtocol
        case serverURLs
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        baseURL = try container.decode(String.self, forKey: .baseURL)
        username = try container.decode(String.self, forKey: .username)
        serverProtocol = try container.decodeIfPresent(String.self, forKey: .serverProtocol)
        serverURLs = try container.decodeIfPresent([String].self, forKey: .serverURLs) ?? []
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(name, forKey: .name)
        try container.encode(baseURL, forKey: .baseURL)
        try container.encode(username, forKey: .username)
        try container.encodeIfPresent(serverProtocol, forKey: .serverProtocol)
        try container.encode(serverURLs, forKey: .serverURLs)
    }
}

/// Reads the catalog one record at a time, so a record this build cannot read is skipped
/// instead of failing the whole list. Writing still uses `StoredConnectionProfiles`.
private struct ReadableStoredConnectionProfiles: Decodable {
    var activeId: String?
    var profiles: [ReadableStoredConnectionProfile]
}

private struct ReadableStoredConnectionProfile: Decodable {
    let record: StoredConnectionProfile?
    /// Which record failed and why, without any of its contents.
    let failure: String?

    init(from decoder: Decoder) throws {
        do {
            record = try StoredConnectionProfile(from: decoder)
            failure = nil
        } catch {
            record = nil
            let index = decoder.codingPath.last?.intValue.map(String.init) ?? "?"
            failure = "record \(index): \(Self.kind(of: error))"
        }
    }

    private static func kind(of error: Error) -> String {
        switch error {
        case let DecodingError.keyNotFound(key, _): "missing \(key.stringValue)"
        case let DecodingError.valueNotFound(_, context): "null \(context.codingPath.last?.stringValue ?? "value")"
        case let DecodingError.typeMismatch(_, context): "wrong type for \(context.codingPath.last?.stringValue ?? "value")"
        case DecodingError.dataCorrupted: "corrupted"
        default: String(describing: type(of: error))
        }
    }
}

public struct UserDefaultsConnectionProfileStore: ConnectionProfileStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let secureCredentialStore: SecureCredentialStore
    private let lock = NSLock()
    private let revisionSignal = LiveConnectionRevisionSignal()
    private let worktreePrefix = "addedProjectWorktrees."
    private let worktreeIdPrefix = "worktrees."
    private let catalogKey = "connection_profiles_v2"
    private let unreadableCatalogBackupKey = "connection_profiles_v2.unreadable"
    private let activeBaseURLKey = "active_profile_base_url"
    private let activeUsernameKey = "active_profile_username"
    private let activeProtocolKey = "active_profile_protocol"

    public init(
        defaults: UserDefaults = .standard,
        secureCredentialStore: SecureCredentialStore = KeychainCredentialStore()
    ) {
        self.defaults = defaults
        self.secureCredentialStore = secureCredentialStore
    }

    public func configurationRevisions() -> AsyncStream<Void> {
        revisionSignal.stream()
    }

    public func activeProfile() async -> ConnectionProfile? {
        lock.lock()
        defer { lock.unlock() }
        return migratedCatalog().activeProfile()?.withPassword(secureCredentialStore)
    }

    public func listProfiles() async -> [ConnectionProfile] {
        lock.lock()
        defer { lock.unlock() }
        return migratedCatalog().profiles.map { $0.withPassword(secureCredentialStore) }
    }

    public func saveProfile(_ profile: ConnectionProfile) async throws {
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

    public func applyProfile(id: String) async {
        lock.lock()
        defer { lock.unlock() }
        writeCatalog(migratedCatalog().apply(id: id))
        revisionSignal.yield()
    }

    public func removeProfile(id: String) async {
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

    public func addedWorktrees(serverBaseURL: String) async -> [String] {
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

    public func addWorktree(serverBaseURL: String, worktree: String) async {
        var current = await addedWorktrees(serverBaseURL: serverBaseURL)
        guard !current.contains(worktree) else { return }
        current.append(worktree)
        await setAddedWorktrees(serverBaseURL: serverBaseURL, worktrees: current)
    }

    public func removeWorktree(serverBaseURL: String, worktree: String) async {
        var current = await addedWorktrees(serverBaseURL: serverBaseURL)
        current.removeAll { $0 == worktree }
        await setAddedWorktrees(serverBaseURL: serverBaseURL, worktrees: current)
    }

    public func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {
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

    public func clearAll() async {
        lock.lock()
        defer { lock.unlock() }
        defaults.removeObject(forKey: catalogKey)
        defaults.removeObject(forKey: unreadableCatalogBackupKey)
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
        if defaults.object(forKey: catalogKey) != nil {
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
        guard let raw = defaults.object(forKey: catalogKey) else {
            return ConnectionProfileCatalog()
        }
        let stored: ReadableStoredConnectionProfiles
        do {
            guard let data = raw as? Data else { throw CocoaError(.coderReadCorrupt) }
            stored = try JSONDecoder().decode(ReadableStoredConnectionProfiles.self, from: data)
        } catch {
            // The next save replaces this blob, so keep the original bytes first.
            if backUpUnreadableCatalog(raw) {
                DiagnosticLog.error(.connection, "Saved servers could not be read; the original data is kept aside")
            }
            return ConnectionProfileCatalog()
        }
        let failures = stored.profiles.compactMap(\.failure)
        if !failures.isEmpty, backUpUnreadableCatalog(raw) {
            DiagnosticLog.warning(
                .connection,
                "Skipped \(failures.count) unreadable saved server(s): \(failures.joined(separator: ", "))"
            )
        }
        let profiles = stored.profiles.compactMap(\.record).map { record in
            ConnectionProfile(
                baseURL: record.baseURL,
                serverURLs: record.serverURLs,
                username: record.username,
                password: "",
                serverProtocol: record.serverProtocol.flatMap(ServerProtocol.init(rawValue:)),
                id: record.id,
                name: record.name
            )
        }
        var activeId = stored.activeId
        if !failures.isEmpty, let id = activeId, !profiles.contains(where: { $0.id == id }) {
            // The active server was one of the skipped records: fall back as removing it would.
            activeId = profiles.first?.id
        }
        return ConnectionProfileCatalog(profiles: profiles, activeId: activeId)
    }

    /// Keeps only the first unreadable catalog: a later one must not replace the original.
    /// True when `raw` was stored now, so each case is logged once rather than on every read.
    private func backUpUnreadableCatalog(_ raw: Any) -> Bool {
        guard defaults.object(forKey: unreadableCatalogBackupKey) == nil else { return false }
        defaults.set(raw, forKey: unreadableCatalogBackupKey)
        return true
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
                    serverURLs: ConnectionProfileIdentity.normalizedAddressList(profile.serverURLs)
                )
            }
        )
        do {
            defaults.set(try JSONEncoder().encode(stored), forKey: catalogKey)
        } catch {
            // The error can quote a value; log only its type.
            DiagnosticLog.error(.connection, "Saved servers could not be written (\(type(of: error)))")
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
