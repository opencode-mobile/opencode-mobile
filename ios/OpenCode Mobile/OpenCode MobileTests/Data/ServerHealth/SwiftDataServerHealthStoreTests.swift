import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct SwiftDataServerHealthStoreTests {
    private func makeStores() throws -> [any ServerHealthStore] {
        [try SwiftDataServerHealthStore(container: SwiftDataServerHealthStore.makeInMemoryContainer()), InMemoryServerHealthStore()]
    }

    @Test func pingsComeBackOldestFirstFromSince() async throws {
        for store in try makeStores() {
            await store.appendPing(.success(profileID: "p1", atMs: 30, latencyMs: 3))
            await store.appendPing(.failed(profileID: "p1", atMs: 10, failure: .timeout))
            await store.appendPing(.success(profileID: "p1", atMs: 20, latencyMs: 2, pid: 5, version: "1.0"))
            await store.appendPing(.success(profileID: "p2", atMs: 25, latencyMs: 9))
            #expect(await store.pings(profileID: "p1", sinceMs: 15) == [
                .success(profileID: "p1", atMs: 20, latencyMs: 2, pid: 5, version: "1.0"),
                .success(profileID: "p1", atMs: 30, latencyMs: 3)
            ])
            #expect(await store.pings(profileID: "p1", sinceMs: 0).first == .failed(profileID: "p1", atMs: 10, failure: .timeout))
        }
    }

    @Test func eventsStartWithTheLatestStateBeforeSince() async throws {
        for store in try makeStores() {
            await store.appendEvent(.state(.connecting, profileID: "p1", atMs: 5))
            await store.appendEvent(.state(.live, profileID: "p1", atMs: 8))
            await store.appendEvent(.failure(.network, profileID: "p1", atMs: 9))
            await store.appendEvent(.state(.offline, profileID: "p1", atMs: 20))
            await store.appendEvent(.restart(version: "2", previousVersion: "1", profileID: "p1", atMs: 25))
            await store.appendEvent(.state(.live, profileID: "p2", atMs: 12))
            #expect(await store.events(profileID: "p1", sinceMs: 10) == [
                .state(.live, profileID: "p1", atMs: 8),
                .state(.offline, profileID: "p1", atMs: 20),
                .restart(version: "2", previousVersion: "1", profileID: "p1", atMs: 25)
            ])
            #expect(await store.latestState(profileID: "p1") == .offline)
            #expect(await store.latestState(profileID: "p3") == nil)
        }
    }

    @Test func lastPidIsTheNewestCheckWithAPidOrVersion() async throws {
        for store in try makeStores() {
            #expect(await store.lastPid(profileID: "p1") == nil)
            await store.appendPing(.success(profileID: "p1", atMs: 1, latencyMs: 1, pid: 10, version: "1.0"))
            await store.appendPing(.success(profileID: "p1", atMs: 2, latencyMs: 1, pid: nil, version: "1.1"))
            await store.appendPing(.failed(profileID: "p1", atMs: 3, failure: .network))
            #expect(await store.lastPid(profileID: "p1") == ServerHealthProcessIdentity(pid: nil, version: "1.1"))
        }
    }

    @Test func pruneAndPurge() async throws {
        for store in try makeStores() {
            await store.appendPing(.success(profileID: "p1", atMs: 1, latencyMs: 1))
            await store.appendPing(.success(profileID: "p1", atMs: 100, latencyMs: 1))
            await store.appendEvent(.state(.live, profileID: "p1", atMs: 1))
            await store.appendEvent(.state(.live, profileID: "p2", atMs: 100))
            await store.prune(olderThanMs: 50)
            #expect(await store.pings(profileID: "p1", sinceMs: 0).map(\.atMs) == [100])
            #expect(await store.events(profileID: "p1", sinceMs: 0).isEmpty)
            await store.purge(profileID: "p2")
            #expect(await store.events(profileID: "p2", sinceMs: 0).isEmpty)
            #expect(await store.pings(profileID: "p1", sinceMs: 0).count == 1)
            await store.purgeAll()
            #expect(await store.pings(profileID: "p1", sinceMs: 0).isEmpty)
        }
    }
}
