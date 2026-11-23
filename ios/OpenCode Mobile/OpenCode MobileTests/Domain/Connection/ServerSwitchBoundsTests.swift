import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
@Suite struct ServerSwitchBoundsTests {
    @Test func catalogReloadsCoalesceIntoOnePerBurst() async {
        let opened = GateSignal()
        let refresh = CoalescedRefresh(sleep: { await opened.wait() })
        var runs = 0

        for _ in 0 ..< 5 {
            refresh.request { runs += 1 }
        }
        opened.fire()
        while runs == 0 { await Task.yield() }
        #expect(runs == 1)

        refresh.request { runs += 1 }
        while runs == 1 { await Task.yield() }
        #expect(runs == 2)
    }

    @Test func cancelledCatalogReloadDoesNotRun() async {
        let opened = GateSignal()
        let refresh = CoalescedRefresh(sleep: {
            await opened.wait()
            try Task.checkCancellation()
        })
        var runs = 0
        refresh.request { runs += 1 }

        refresh.cancel()
        opened.fire()
        for _ in 0 ..< 50 { await Task.yield() }

        #expect(runs == 0)
    }

    @Test func sameURLWithTwoUsernamesReadsEachProfilesProjects() async throws {
        let suite = "ServerSwitchBoundsTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: EmptyCredentials())
        let url = "http://192.0.2.1:4096/"
        try await store.saveProfile(ConnectionProfile(baseURL: url, username: "alice", password: "test-password", id: "a", name: "A"))
        await store.setAddedWorktrees(serverBaseURL: url, worktrees: ["/alice"])
        try await store.saveProfile(ConnectionProfile(baseURL: url, username: "bob", password: "test-password", id: "b", name: "B"))
        await store.setAddedWorktrees(serverBaseURL: url, worktrees: ["/bob"])

        #expect(await store.addedWorktrees(profileID: "a") == ["/alice"])
        #expect(await store.addedWorktrees(profileID: "b") == ["/bob"])
    }

    @Test func detectedServerUpdateKeepsThePasswordAndRefusesUnknownIds() async throws {
        let suite = "ServerSwitchBoundsTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let credentials = CountingCredentials()
        let store = UserDefaultsConnectionProfileStore(defaults: defaults, secureCredentialStore: credentials)
        try await store.saveProfile(
            ConnectionProfile(baseURL: "http://192.0.2.1:4096/", username: "opencode", password: "test-password", id: "a", name: "A")
        )
        let writes = credentials.writes

        #expect(await store.updateDetectedServer(id: "a", baseURL: "http://192.0.2.2:4096/", serverProtocol: .v2))
        #expect(await store.updateDetectedServer(id: "gone", baseURL: "http://192.0.2.3:4096/", serverProtocol: .v2) == false)

        #expect(credentials.writes == writes)
        #expect(await store.listProfiles().map(\.id) == ["a"])
        #expect(await store.activeProfile()?.baseURL == "http://192.0.2.2:4096/")
        #expect(await store.activeProfile()?.password == "test-password")
    }

    @Test func lastTurnSummariesAreBoundedAndClearable() {
        let store = LastTurnReviewChangesStore()
        for index in 0 ... LastTurnReviewChangesStore.maxSnapshots {
            store.publish(sessionID: "ses-\(index)", messageID: "m", summary: nil)
        }
        #expect(store.snapshot(sessionID: "ses-0") == nil)
        #expect(store.snapshot(sessionID: "ses-1") != nil)

        store.clearAll()
        #expect(store.snapshot(sessionID: "ses-1") == nil)
    }

    @Test func inventoryKeysOfTwoUsersOnOneURLStaySeparate() {
        let url = "http://192.0.2.1:4096/"
        let alice = ConnectionProfile(baseURL: url, username: "alice", password: "test-password", id: "a", name: "A")
        let bob = ConnectionProfile(baseURL: url, username: "bob", password: "test-password", id: "b", name: "B")
        let cache = WorktreeInventoryCache()
        let aliceKey = try! #require(WorktreeInventoryCache.serverKey(profile: alice, fallbackBaseURL: url))
        let bobKey = try! #require(WorktreeInventoryCache.serverKey(profile: bob, fallbackBaseURL: url))

        cache.setAddedPaths(["/alice"], server: aliceKey)
        cache.setAddedPaths(["/bob"], server: bobKey)

        #expect(cache.snapshot(server: aliceKey)?.addedPaths == ["/alice"])
        #expect(cache.snapshot(server: bobKey)?.addedPaths == ["/bob"])
    }
}

private final class EmptyCredentials: SecureCredentialStore, @unchecked Sendable {
    func readPassword(serverBaseURL _: String) -> String? { nil }
    func writePassword(serverBaseURL _: String, password _: String) {}
    func deletePassword(serverBaseURL _: String) {}
    func clearAll() {}
}

private final class CountingCredentials: SecureCredentialStore, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: String] = [:]
    private var writeCount = 0

    var writes: Int { lock.withLock { writeCount } }

    func readPassword(serverBaseURL: String) -> String? {
        lock.withLock { values[serverBaseURL] }
    }

    func writePassword(serverBaseURL: String, password: String) {
        lock.withLock {
            writeCount += 1
            values[serverBaseURL] = password
        }
    }

    func deletePassword(serverBaseURL: String) {
        lock.withLock { values[serverBaseURL] = nil }
    }

    func clearAll() {
        lock.withLock { values.removeAll() }
    }
}
