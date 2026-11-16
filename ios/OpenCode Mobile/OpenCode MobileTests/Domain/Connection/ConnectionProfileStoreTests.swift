import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct ConnectionProfileStoreTests {
    private let server = "http://127.0.0.1:4096/"

    @Test func saveAndLoadActiveProfile() async throws {
        let defaults = isolatedDefaults()
        let secure = InMemorySecureCredentialStore()
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: secure)
        try await store.saveProfile(
            ConnectionProfile(baseURL: server, username: "opencode", password: "secret")
        )
        let loaded = await store.activeProfile()
        #expect(loaded?.baseURL == server)
        #expect(loaded?.username == "opencode")
        #expect(loaded?.password == "secret")
        #expect(loaded?.serverProtocol == nil)
    }

    @Test func saveProfile_whenTheSecureStoreRefusesTheWrite_throwsAndKeepsTheCatalog() async throws {
        let defaults = isolatedDefaults()
        let secure = InMemorySecureCredentialStore()
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: secure)
        try await store.saveProfile(
            ConnectionProfile(baseURL: server, username: "opencode", password: "first", name: "A")
        )
        secure.failWrites = true

        await #expect(throws: SecureCredentialStoreError.writeFailed(status: -34018)) {
            try await store.saveProfile(
                ConnectionProfile(baseURL: "http://10.0.0.9:4096/", username: "opencode", password: "second", name: "B")
            )
        }

        let profiles = await store.listProfiles()
        #expect(profiles.map(\.name) == ["A"])
        #expect(profiles.first?.password == "first")
    }

    @Test func saveAndLoadPersistsProtocolHint() async throws {
        let defaults = isolatedDefaults()
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: InMemorySecureCredentialStore())
        try await store.saveProfile(
            ConnectionProfile(baseURL: server, username: "opencode", password: "secret", serverProtocol: .v2)
        )
        let loaded = await store.activeProfile()
        #expect(loaded?.serverProtocol == .v2)
    }

    @Test func addWorktree_preservesInsertionOrder() async {
        let defaults = isolatedDefaults()
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: InMemorySecureCredentialStore())
        await store.addWorktree(serverBaseURL: server, worktree: "/a")
        await store.addWorktree(serverBaseURL: server, worktree: "/b")
        await store.addWorktree(serverBaseURL: server, worktree: "/c")
        await store.addWorktree(serverBaseURL: server, worktree: "/a")
        #expect(await store.addedWorktrees(serverBaseURL: server) == ["/a", "/b", "/c"])
    }

    @Test func setAddedWorktrees_dedupesWithoutReordering() async {
        let defaults = isolatedDefaults()
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: InMemorySecureCredentialStore())
        await store.setAddedWorktrees(
            serverBaseURL: server,
            worktrees: ["/one", "/two", "/one", "/three"]
        )
        #expect(await store.addedWorktrees(serverBaseURL: server) == ["/one", "/two", "/three"])
    }

    @Test func removeWorktree_dropsMatchingPath() async {
        let defaults = isolatedDefaults()
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: InMemorySecureCredentialStore())
        await store.setAddedWorktrees(serverBaseURL: server, worktrees: ["/a", "/b", "/c"])
        await store.removeWorktree(serverBaseURL: server, worktree: "/b")
        #expect(await store.addedWorktrees(serverBaseURL: server) == ["/a", "/c"])
        await store.removeWorktree(serverBaseURL: server, worktree: "/missing")
        #expect(await store.addedWorktrees(serverBaseURL: server) == ["/a", "/c"])
    }

    @Test func save_roundTripsServerURLs_andKeepsIdWhenBaseURLChanges() async throws {
        let defaults = isolatedDefaults()
        let store = UserDefaultsConnectionProfileStore(
            defaults: defaults,
            secureCredentialStore: InMemorySecureCredentialStore()
        )
        let lan = "http://192.168.1.2:49374/"
        let tailscale = "http://100.64.0.3:49374"
        try await store.saveProfile(
            ConnectionProfile(
                baseURL: lan,
                serverURLs: [lan, tailscale],
                username: "opencode",
                password: "token",
                id: "profile-1",
                name: "Mac"
            )
        )
        let loaded = await store.activeProfile()
        #expect(loaded?.id == "profile-1")
        #expect(loaded?.serverURLs == [lan, "http://100.64.0.3:49374/"])
        #expect(loaded?.password == "token")

        var updated = try #require(loaded)
        updated.baseURL = tailscale
        try await store.saveProfile(updated)
        let again = await store.activeProfile()
        #expect(again?.id == "profile-1")
        #expect(again?.baseURL == "http://100.64.0.3:49374/")
        #expect(again?.serverURLs == [lan, "http://100.64.0.3:49374/"])
        #expect(await store.listProfiles().count == 1)
    }

    @Test func load_missingServerURLs_decodesAsEmpty() async {
        let defaults = isolatedDefaults()
        let json = """
        {"activeId":"p","profiles":[{"id":"p","name":"Home","baseURL":"http://10.0.0.1:4096/","username":"opencode"}]}
        """
        defaults.set(Data(json.utf8), forKey: "connection_profiles_v2")
        let store = UserDefaultsConnectionProfileStore(
            defaults: defaults,
            secureCredentialStore: InMemorySecureCredentialStore()
        )
        let loaded = await store.activeProfile()
        #expect(loaded?.id == "p")
        #expect(loaded?.baseURL == "http://10.0.0.1:4096/")
        #expect(loaded?.serverURLs.isEmpty == true)
    }

    @Test func removeWorktree_dropsLegacyUrlKeyedPathsAfterProfileExists() async throws {
        let defaults = isolatedDefaults()
        let store = UserDefaultsConnectionProfileStore(
            defaults: defaults,
            secureCredentialStore: InMemorySecureCredentialStore()
        )
        await store.addWorktree(serverBaseURL: server, worktree: "/repo/a")
        await store.addWorktree(serverBaseURL: server, worktree: "/repo/b")
        try await store.saveProfile(ConnectionProfile(baseURL: server, username: "opencode", password: "secret"))
        #expect(await store.addedWorktrees(serverBaseURL: server) == ["/repo/a", "/repo/b"])
        await store.removeWorktree(serverBaseURL: server, worktree: "/repo/a")
        #expect(await store.addedWorktrees(serverBaseURL: server) == ["/repo/b"])
        await store.removeWorktree(serverBaseURL: server, worktree: "/repo/b")
        #expect(await store.addedWorktrees(serverBaseURL: server) == [])
    }

    @Test func load_skipsAnUnreadableRecordAndASaveKeepsTheRest() async throws {
        let defaults = isolatedDefaults()
        let json = """
        {"activeId":"a","profiles":[\
        {"id":"a","name":"Home","baseURL":"http://10.0.0.1:4096/","username":"opencode"},\
        {"id":"broken","baseURL":"http://10.0.0.2:4096/"},\
        {"id":"b","name":"Work","baseURL":"https://example.com/","username":"opencode","serverProtocol":"v2"}]}
        """
        defaults.set(Data(json.utf8), forKey: catalogKey)
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: InMemorySecureCredentialStore())

        #expect(await store.listProfiles().map(\.id) == ["a", "b"])
        #expect(await store.activeProfile()?.id == "a")

        try await store.saveProfile(ConnectionProfile(baseURL: "http://10.0.0.3:4096/", username: "opencode", password: "test-password", id: "c", name: "Lab"))
        #expect(await store.listProfiles().map(\.id) == ["a", "b", "c"])
        #expect(await store.listProfiles().first { $0.id == "b" }?.serverProtocol == .v2)
        // The skipped record stays recoverable.
        #expect(defaults.data(forKey: backupKey) == Data(json.utf8))
    }

    @Test func load_activeIdOnASkippedRecordFallsBackToAReadableOne() async {
        let defaults = isolatedDefaults()
        let json = """
        {"activeId":"broken","profiles":[\
        {"id":"broken","name":"Home"},\
        {"id":"b","name":"Work","baseURL":"https://example.com/","username":"opencode"}]}
        """
        defaults.set(Data(json.utf8), forKey: catalogKey)
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: InMemorySecureCredentialStore())

        #expect(await store.activeProfile()?.id == "b")
        #expect(await store.listProfiles().map(\.id) == ["b"])
    }

    @Test func saveProfile_afterAnUnreadableCatalog_keepsTheOriginalBytes() async throws {
        let defaults = isolatedDefaults()
        let original = Data("{not json".utf8)
        defaults.set(original, forKey: catalogKey)
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: InMemorySecureCredentialStore())

        #expect(await store.listProfiles().isEmpty)
        try await store.saveProfile(ConnectionProfile(baseURL: "http://10.0.0.1:4096/", username: "opencode", password: "test-password", name: "Home"))
        #expect(await store.listProfiles().map(\.name) == ["Home"])
        #expect(defaults.data(forKey: backupKey) == original)

        // A second unreadable catalog does not replace the first backup.
        defaults.set(Data("[]".utf8), forKey: catalogKey)
        try await store.saveProfile(ConnectionProfile(baseURL: "http://10.0.0.2:4096/", username: "opencode", password: "test-password", name: "Work"))
        #expect(defaults.data(forKey: backupKey) == original)
        #expect(await store.listProfiles().map(\.name) == ["Work"])
    }

    @Test func load_aCatalogStoredAsTheWrongTypeIsKeptAndNotMigratedOver() async throws {
        let defaults = isolatedDefaults()
        defaults.set("not data", forKey: catalogKey)
        defaults.set("http://10.0.0.9:4096/", forKey: "active_profile_base_url")
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: InMemorySecureCredentialStore())

        #expect(await store.listProfiles().isEmpty)
        #expect(defaults.string(forKey: backupKey) == "not data")
    }

    @Test func clearAll_removesTheUnreadableCatalogBackup() async {
        let defaults = isolatedDefaults()
        defaults.set(Data("{not json".utf8), forKey: catalogKey)
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: InMemorySecureCredentialStore())
        _ = await store.listProfiles()
        #expect(defaults.object(forKey: backupKey) != nil)
        await store.clearAll()
        #expect(defaults.object(forKey: backupKey) == nil)
    }

    @Test func writtenCatalogStillDecodesWithTheStrictPreviousReader() async throws {
        let defaults = isolatedDefaults()
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: InMemorySecureCredentialStore())
        try await store.saveProfile(
            ConnectionProfile(
                baseURL: "http://192.168.1.2:4096/",
                serverURLs: ["http://192.168.1.2:4096/", "http://100.64.0.3:4096/"],
                username: "opencode",
                password: "test-password",
                serverProtocol: .v2,
                id: "p",
                name: "Mac"
            )
        )
        try await store.saveProfile(ConnectionProfile(baseURL: "https://example.com/", username: "opencode", password: "test-password", id: "q", name: "Remote"))
        await store.applyProfile(id: "p")

        let data = try #require(defaults.data(forKey: catalogKey))
        let decoded = try JSONDecoder().decode(PreviousStoredProfiles.self, from: data)
        #expect(decoded.activeId == "p")
        #expect(decoded.profiles.map(\.id) == ["p", "q"])
        #expect(decoded.profiles.first?.serverProtocol == "v2")
        #expect(decoded.profiles.first?.serverURLs == ["http://192.168.1.2:4096/", "http://100.64.0.3:4096/"])
        #expect(decoded.profiles.last?.serverProtocol == nil)
        #expect(defaults.object(forKey: backupKey) == nil)
    }

    private let catalogKey = "connection_profiles_v2"
    private let backupKey = "connection_profiles_v2.unreadable"

    /// The catalog reader before per-record tolerance: every field required except the two optional ones.
    private struct PreviousStoredProfiles: Decodable {
        var activeId: String?
        var profiles: [Record]

        struct Record: Decodable {
            var id: String
            var name: String
            var baseURL: String
            var username: String
            var serverProtocol: String?
            var serverURLs: [String]
        }
    }

    private func isolatedDefaults() -> UserDefaults {
        let suite = "ConnectionProfileStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }
}

private final class InMemorySecureCredentialStore: SecureCredentialStore, @unchecked Sendable {
    private var passwords: [String: String] = [:]
    var failWrites = false

    func readPassword(serverBaseURL: String) -> String? {
        passwords[serverBaseURL]
    }

    func writePassword(serverBaseURL: String, password: String) throws {
        if failWrites {
            throw SecureCredentialStoreError.writeFailed(status: -34018)
        }
        passwords[serverBaseURL] = password
    }

    func deletePassword(serverBaseURL: String) {
        passwords.removeValue(forKey: serverBaseURL)
    }

    func clearAll() {
        passwords.removeAll()
    }
}
