import Testing
@testable import OpenCode_Mobile

struct WorktreeInventoryCacheTests {
    private let server = "http://127.0.0.1:4096"

    @Test func unknownUntilTheAddedPathsAreRecorded() {
        let cache = WorktreeInventoryCache()
        cache.record(.loaded(["/repo", "/wt/tax"]), addedPath: "/repo", server: server)
        #expect(cache.snapshot(server: server) == nil)

        cache.setAddedPaths(["/repo"], server: server)
        #expect(cache.snapshot(server: server + "/")?.inventory(for: "/repo") == .loaded(["/repo", "/wt/tax"]))
    }

    @Test func failedLoadKeepsTheLastInventory() {
        let cache = WorktreeInventoryCache()
        cache.setAddedPaths(["/repo"], server: server)
        cache.record(.loaded(["/repo", "/wt/tax"]), addedPath: "/repo", server: server)
        cache.record(.failed, addedPath: "/repo", server: server)
        #expect(cache.snapshot(server: server)?.inventory(for: "/repo") == .loaded(["/repo", "/wt/tax"]))
    }

    @Test func removedPathsAndOtherServersDoNotLeak() {
        let cache = WorktreeInventoryCache()
        cache.setAddedPaths(["/repo", "/other"], server: server)
        cache.record(.loaded(["/repo", "/wt/tax"]), addedPath: "/repo", server: server)
        cache.setAddedPaths(["/other"], server: server)
        #expect(cache.snapshot(server: server)?.inventory(for: "/repo") == .unloaded)
        #expect(cache.snapshot(server: "http://10.0.0.2:4096") == nil)

        cache.clear()
        #expect(cache.snapshot(server: server) == nil)
    }
}

struct SessionChatProjectIdentityTests {
    private let inventory: (String) -> WorktreeInventory = { added in
        added == "/repo" ? .loaded(["/repo", "/wt/tax"]) : .unloaded
    }

    @Test func listedWorktreeShowsItsCheckoutName() {
        let identity = SessionChatProjectIdentity.resolve(worktree: "/wt/tax", addedPaths: ["/repo"], inventoryFor: inventory)
        #expect(identity == SessionChatProjectIdentity(label: "tax", isWorktreeCheckout: true))
    }

    @Test func addedPathShowsTheProjectLetter() {
        let identity = SessionChatProjectIdentity.resolve(worktree: "/repo", addedPaths: ["/repo"], inventoryFor: inventory)
        #expect(identity == SessionChatProjectIdentity(label: "repo", letter: "R", accent: letterAccent(for: "/repo")))
        #expect(identity == .folder("/repo"))
    }

    @Test func blankWorktreeHasNoIdentity() {
        #expect(SessionChatProjectIdentity.resolve(worktree: " ", addedPaths: ["/repo"], inventoryFor: inventory) == .none)
    }
}
