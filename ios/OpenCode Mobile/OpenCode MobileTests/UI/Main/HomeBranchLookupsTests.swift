import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct HomeBranchLookupsTests {
    @Test func newerLookupWinsOverAStaleFailure() async {
        let fetch = ScriptedBranchFetch()
        let lookups = HomeBranchLookups(retryDelays: []) { directory in await fetch.answer(directory) }

        lookups.show(.homeRows, ["/wt/tax"])
        #expect(await waitFor { fetch.pending == 1 })
        lookups.refresh()
        #expect(await waitFor { fetch.pending == 2 })

        fetch.resolve(index: 1, with: .branch("feature/tax-rounding"))
        #expect(await waitFor { lookups.branches["/wt/tax"] == "feature/tax-rounding" })
        fetch.resolve(index: 0, with: .unknown)
        await settle()

        #expect(lookups.branches["/wt/tax"] == "feature/tax-rounding")
    }

    @Test func worktreesShownWhileLoadsAreInFlightAreAskedToo() async {
        let fetch = ScriptedBranchFetch()
        let lookups = HomeBranchLookups(retryDelays: []) { directory in await fetch.answer(directory) }

        lookups.show(.mainCheckouts, ["/repo"])
        lookups.show(.homeRows, ["/repo", "/wt/tax", "/wt/export"])
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

        lookups.show(.homeRows, ["/repo"])
        #expect(await waitFor { lookups.branches["/repo"] == "main" })
        lookups.refresh()
        #expect(await waitFor { await answers.count == 3 })
        await settle()

        #expect(lookups.branches["/repo"] == "main")
        #expect(changes == 1)
    }

    @Test func retryRecoversAFailedFirstAnswer() async {
        let answers = Answers([.unknown, .branch("feature/x")])
        let lookups = HomeBranchLookups(retryDelays: [.zero]) { _ in await answers.next() }

        lookups.show(.homeRows, ["/wt/x"])

        #expect(await waitFor { lookups.branches["/wt/x"] == "feature/x" })
    }

    @Test func theDefaultRetriesLastLongEnoughForAColdLocation() {
        // A cold v2 location can take longer than a few seconds to detect git.
        #expect(HomeBranchLookups.defaultRetryDelays == [.seconds(1), .seconds(3), .seconds(10), .seconds(30)])
        #expect(HomeBranchLookups.defaultMaxInFlight == 3)
    }

    @Test func aFolderThatNeverSettlesStopsAfterTheRetriesUntilRefresh() async {
        let answers = Answers([])
        let lookups = HomeBranchLookups(retryDelays: [.zero, .zero]) { _ in await answers.next() }

        lookups.show(.homeRows, ["/plain"])
        #expect(await waitFor { await answers.count == 3 })
        await settle()

        // Showing it again (Home reports on every scroll) asks nothing; a refresh does.
        lookups.show(.homeRows, [])
        lookups.show(.homeRows, ["/plain"])
        lookups.show(.picker, ["/plain"])
        await settle()
        #expect(await answers.count == 3)

        lookups.refresh()
        #expect(await waitFor { await answers.count == 6 })
    }

    @Test func aDecidedAnswerIsNotAskedAgainUntilRefresh() async {
        let answers = Answers([.branch(nil), .branch("dev")])
        let lookups = HomeBranchLookups(retryDelays: []) { _ in await answers.next() }

        lookups.show(.homeRows, ["/gone"])
        #expect(await waitFor { await answers.count == 1 })
        lookups.show(.homeRows, [])
        lookups.show(.homeRows, ["/gone"])
        await settle()
        #expect(await answers.count == 1)

        lookups.refresh()
        #expect(await waitFor { lookups.branches["/gone"] == "dev" })
    }

    @Test func atMostThreeLookupsAreInFlightAtOnce() async {
        let fetch = ScriptedBranchFetch()
        let lookups = HomeBranchLookups(retryDelays: []) { directory in await fetch.answer(directory) }

        lookups.show(.homeRows, (1 ... 10).map { "/wt/\($0)" })
        #expect(await waitFor { fetch.pending == 3 })
        await settle()
        #expect(fetch.pending == 3)

        for index in 0 ..< 10 {
            #expect(await waitFor { fetch.directories.count > index })
            fetch.resolve(index: index, with: .branch("dev"))
            await settle()
            #expect(fetch.pending <= 3)
        }
        #expect(await waitFor { lookups.branches.count == 10 })
    }

    @Test func mainCheckoutsAreAskedBeforeRows() async {
        let fetch = ScriptedBranchFetch()
        let lookups = HomeBranchLookups(retryDelays: []) { directory in await fetch.answer(directory) }

        lookups.show(.homeRows, ["/wt/a", "/wt/b", "/wt/c", "/wt/d"])
        lookups.show(.mainCheckouts, ["/repo"])
        #expect(await waitFor { fetch.pending == 3 })
        fetch.resolve(index: 0, with: .branch("dev"))
        #expect(await waitFor { fetch.directories.count == 4 })

        // The first three started before the main checkout was known; it goes next.
        #expect(fetch.directories == ["/wt/a", "/wt/b", "/wt/c", "/repo"])
    }

    @Test func aQueuedDirectoryNothingShowsAnyMoreIsNeverAsked() async {
        let fetch = ScriptedBranchFetch()
        let lookups = HomeBranchLookups(retryDelays: []) { directory in await fetch.answer(directory) }

        // A fast scroll: rows 1...10 pass by, rows 9 and 10 stay.
        lookups.show(.homeRows, (1 ... 10).map { "/wt/\($0)" })
        #expect(await waitFor { fetch.pending == 3 })
        lookups.show(.homeRows, ["/wt/9", "/wt/10"])
        fetch.resolveAll { _ in .branch("dev") }
        #expect(await waitFor { fetch.directories.count == 5 })
        fetch.resolveAll { _ in .branch("dev") }
        await settle()

        #expect(fetch.directories == ["/wt/1", "/wt/2", "/wt/3", "/wt/9", "/wt/10"])
        // A request that was already running keeps its answer.
        #expect(await waitFor { lookups.branches.count == 5 })
    }

    @Test func aRetryWaitsOnlyWhileTheDirectoryIsShown() async {
        let answers = Answers([])
        let lookups = HomeBranchLookups(retryDelays: [.milliseconds(50)]) { _ in await answers.next() }

        lookups.show(.homeRows, ["/wt/x"])
        #expect(await waitFor { await answers.count == 1 })
        lookups.show(.homeRows, [])
        try? await Task.sleep(for: .milliseconds(150))
        #expect(await answers.count == 1)

        // Shown again later: a fresh ladder.
        lookups.show(.homeRows, ["/wt/x"])
        #expect(await waitFor { await answers.count == 2 })
    }

    @Test func aDirectoryShownByTwoSourcesStaysWhileEitherShowsIt() async {
        let fetch = ScriptedBranchFetch()
        let lookups = HomeBranchLookups(retryDelays: [], maxInFlight: 1) { directory in await fetch.answer(directory) }

        lookups.show(.homeRows, ["/wt/a", "/wt/b"])
        lookups.show(.sessionChat, ["/wt/b"])
        #expect(await waitFor { fetch.pending == 1 })
        lookups.show(.homeRows, [])
        fetch.resolve(index: 0, with: .branch("dev"))

        #expect(await waitFor { fetch.directories.count == 2 })
        #expect(fetch.directories == ["/wt/a", "/wt/b"])
    }

    @Test func refreshAsksOnlyWhatIsShown() async {
        let fetch = ScriptedBranchFetch()
        let lookups = HomeBranchLookups(retryDelays: []) { directory in await fetch.answer(directory) }

        lookups.show(.homeRows, ["/wt/a", "/wt/b"])
        #expect(await waitFor { fetch.pending == 2 })
        fetch.resolveAll { _ in .branch("dev") }
        #expect(await waitFor { lookups.branches.count == 2 })
        lookups.show(.homeRows, ["/wt/b"])
        lookups.refresh()

        #expect(await waitFor { fetch.directories.count == 3 })
        await settle()
        #expect(fetch.directories.last == "/wt/b")
        #expect(fetch.directories.count == 3)
    }

    @Test func detachedHeadClearsTheBranch() async {
        let answers = Answers([.branch("main"), .branch(nil)])
        let lookups = HomeBranchLookups(retryDelays: []) { _ in await answers.next() }

        lookups.show(.homeRows, ["/repo"])
        #expect(await waitFor { lookups.branches["/repo"] == "main" })
        lookups.refresh()

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
