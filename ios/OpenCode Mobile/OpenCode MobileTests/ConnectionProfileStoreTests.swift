import Foundation
import Testing
@testable import OpenCode_Mobile

struct ConnectionProfileStoreTests {
    private let server = "http://127.0.0.1:4096/"

    @Test func saveAndLoadActiveProfile() async {
        let defaults = isolatedDefaults()
        let secure = InMemorySecureCredentialStore()
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: secure)
        await store.saveProfile(
            ConnectionProfile(baseURL: server, username: "opencode", password: "secret")
        )
        let loaded = await store.activeProfile()
        #expect(loaded?.baseURL == server)
        #expect(loaded?.username == "opencode")
        #expect(loaded?.password == "secret")
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

    private func isolatedDefaults() -> UserDefaults {
        let suite = "ConnectionProfileStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }
}

private final class InMemorySecureCredentialStore: SecureCredentialStore, @unchecked Sendable {
    private var passwords: [String: String] = [:]

    func readPassword(serverBaseURL: String) -> String? {
        passwords[serverBaseURL]
    }

    func writePassword(serverBaseURL: String, password: String) {
        passwords[serverBaseURL] = password
    }

    func deletePassword(serverBaseURL: String) {
        passwords.removeValue(forKey: serverBaseURL)
    }
}
