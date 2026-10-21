import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct HomeBranchLookupsTests {
    @Test func newerLookupWinsOverAStaleFailure() async {
        let fetch = ScriptedBranchFetch()
        let lookups = HomeBranchLookups(retryDelays: []) { directory in await fetch.answer(directory) }

        lookups.lookUp(["/wt/tax"])
        #expect(await waitFor { fetch.pending == 1 })
        lookups.refresh()
        lookups.lookUp(["/wt/tax"])
        #expect(await waitFor { fetch.pending == 2 })

        fetch.resolve(index: 1, with: .branch("feature/tax-rounding"))
        #expect(await waitFor { lookups.branches["/wt/tax"] == "feature/tax-rounding" })
        fetch.resolve(index: 0, with: .unknown)
        await settle()

        #expect(lookups.branches["/wt/tax"] == "feature/tax-rounding")
    }

    @Test func worktreesAddedWhileLoadsAreInFlightAreAskedToo() async {
        let fetch = ScriptedBranchFetch()
        let lookups = HomeBranchLookups(retryDelays: []) { directory in await fetch.answer(directory) }

        lookups.lookUp(["/repo"])
        lookups.lookUp(["/repo", "/wt/tax", "/wt/export"])
        #expect(await waitFor { fetch.pending == 3 })
        #expect(fetch.directories == ["/repo", "/wt/tax", "/wt/export"])

        fetch.resolveAll { directory in
            .branch(["/repo": "main", "/wt/tax": "feature/tax", "/wt/export": "feature/export"][directory])
        }

        #expect(await waitFor { lookups.branches.count == 3 })
        #expect(lookups.branches == ["/repo": "main", "/wt/tax": "feature/tax", "/wt/export": "feature/export"])
    }

    @Test func failureRetriesThenKeepsTheKnownBranch() async {
        let answers = Answers([.branch("main"), .unknown, .unknown])
        let lookups = HomeBranchLookups(retryDelays: [.zero]) { _ in await answers.next() }
        var changes = 0
        lookups.onChange = { _ in changes += 1 }

        lookups.lookUp(["/repo"])
        #expect(await waitFor { lookups.branches["/repo"] == "main" })
        lookups.refresh()
        lookups.lookUp(["/repo"])
        #expect(await waitFor { await answers.count == 3 })
        await settle()

        #expect(lookups.branches["/repo"] == "main")
        #expect(changes == 1)
    }

    @Test func retryRecoversAFailedFirstAnswer() async {
        let answers = Answers([.unknown, .branch("feature/x")])
        let lookups = HomeBranchLookups(retryDelays: [.zero]) { _ in await answers.next() }

        lookups.lookUp(["/wt/x"])

        #expect(await waitFor { lookups.branches["/wt/x"] == "feature/x" })
    }

    @Test func exhaustedRetriesAskAgainOnTheNextLookUp() async {
        let answers = Answers([.unknown, .branch("feature/x")])
        let lookups = HomeBranchLookups(retryDelays: []) { _ in await answers.next() }

        lookups.lookUp(["/wt/x"])
        #expect(await waitFor { await answers.count == 1 })
        await settle()
        #expect(lookups.branches["/wt/x"] == nil)

        lookups.lookUp(["/wt/x"])
        #expect(await waitFor { lookups.branches["/wt/x"] == "feature/x" })
    }

    @Test func answeredDirectoriesAreNotAskedAgainUntilRefresh() async {
        let answers = Answers([.branch("main"), .branch("dev")])
        let lookups = HomeBranchLookups(retryDelays: []) { _ in await answers.next() }

        lookups.lookUp(["/repo"])
        #expect(await waitFor { lookups.branches["/repo"] == "main" })
        lookups.lookUp(["/repo"])
        await settle()
        #expect(await answers.count == 1)

        lookups.refresh()
        lookups.lookUp(["/repo"])
        #expect(await waitFor { lookups.branches["/repo"] == "dev" })
    }

    @Test func detachedHeadClearsTheBranch() async {
        let answers = Answers([.branch("main"), .branch(nil)])
        let lookups = HomeBranchLookups(retryDelays: []) { _ in await answers.next() }

        lookups.lookUp(["/repo"])
        #expect(await waitFor { lookups.branches["/repo"] == "main" })
        lookups.refresh()
        lookups.lookUp(["/repo"])

        #expect(await waitFor { lookups.branches["/repo"] == nil })
    }

    private func settle() async {
        for _ in 0 ..< 20 { await Task.yield() }
    }

    private func waitFor(timeoutMs: Int = 2_000, _ predicate: @MainActor () async -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .milliseconds(timeoutMs)
        while ContinuousClock.now < deadline {
            if await predicate() { return true }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return await predicate()
    }
}

/// Holds each fetch until the test answers it by call index.
private final class ScriptedBranchFetch: @unchecked Sendable {
    private let lock = NSLock()
    private var calls: [(directory: String, continuation: CheckedContinuation<HomeBranchLookups.Outcome, Never>?)] = []

    var pending: Int { lock.withLock { calls.filter { $0.continuation != nil }.count } }
    var directories: [String] { lock.withLock { calls.map(\.directory) } }

    func answer(_ directory: String) async -> HomeBranchLookups.Outcome {
        await withCheckedContinuation { continuation in
            lock.withLock { calls.append((directory, continuation)) }
        }
    }

    func resolve(index: Int, with outcome: HomeBranchLookups.Outcome) {
        let continuation = lock.withLock { () -> CheckedContinuation<HomeBranchLookups.Outcome, Never>? in
            defer { calls[index].continuation = nil }
            return calls[index].continuation
        }
        continuation?.resume(returning: outcome)
    }

    func resolveAll(_ outcome: (String) -> HomeBranchLookups.Outcome) {
        let indices = lock.withLock { Array(calls.indices) }
        for index in indices {
            let directory = lock.withLock { calls[index].directory }
            resolve(index: index, with: outcome(directory))
        }
    }
}

private actor Answers {
    private var queue: [HomeBranchLookups.Outcome]
    private(set) var count = 0

    init(_ queue: [HomeBranchLookups.Outcome]) {
        self.queue = queue
    }

    func next() -> HomeBranchLookups.Outcome {
        count += 1
        return queue.isEmpty ? .unknown : queue.removeFirst()
    }
}
