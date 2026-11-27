import Foundation

/// Current branch per worktree directory, looked up only for directories something on screen
/// shows (#1124).
///
/// Every directory the server is asked about makes it open a location (and start its MCP
/// servers), so nothing here sweeps an inventory. Each `Source` reports the directories it shows
/// with `show(_:_:)`; the lookups serve the union of those, at most `maxInFlight` at once, main
/// checkouts first. A directory nothing shows any more leaves the queue before its request
/// starts; a request already running finishes and its answer is kept.
///
/// A decided answer (a branch, no branch, or a 4xx) stays until `refresh()`. An unknown answer
/// (VCS detection still warming up, a failed request) keeps the last known branch and asks again
/// after each of `retryDelays` while the directory is still shown; then it stays unknown until
/// `refresh()`. Only the newest lookup of a directory may write it.
@MainActor
final class HomeBranchLookups {
    nonisolated enum Outcome: Equatable, Sendable {
        /// The server answered; nil for a detached HEAD or a folder without a branch.
        case branch(String?)
        /// The request failed, or the server has not detected the repository yet.
        case unknown
    }

    /// Who shows a directory; an earlier case is asked first.
    nonisolated enum Source: Int, CaseIterable, Sendable {
        /// Each project's main checkout: v2 only knows a project is a repository from its branch.
        case mainCheckouts
        /// Home session rows on screen.
        case homeRows
        /// The scope picker's listed worktrees, the dock, and the worktree tab.
        case picker
        /// Session Chat's worktree lists (Move, Run in, New session) while open.
        case sessionChat
    }

    /// About 45 s of retries, so a cold v2 location that is still detecting git settles.
    static let defaultRetryDelays: [Duration] = [.seconds(1), .seconds(3), .seconds(10), .seconds(30)]
    /// Each lookup can make the server open a location, so only a few run together.
    static let defaultMaxInFlight = 3

    private(set) var branches: [String: String] = [:]
    /// Called after `branches` changes.
    var onChange: (([String: String]) -> Void)?

    private let fetch: @Sendable (String) async -> Outcome
    private let retryDelays: [Duration]
    private let maxInFlight: Int

    private var shownBy: [Source: [String]] = [:]
    /// Best (earliest) source per shown directory.
    private var shown: [String: Source] = [:]
    /// Shown directories, best source first, then in the order each source reported them.
    private var shownOrder: [String] = []
    /// Directories asked since the last `refresh()`: queued, running, waiting to retry, or decided.
    private var requested: Set<String> = []
    /// Waiting for a free slot, with the attempt they make next, in arrival order.
    private var queue: [(directory: String, attempt: Int)] = []
    /// Running requests and retry waits, by directory.
    private var tasks: [String: Task<Void, Never>] = [:]
    private var running: Set<String> = []
    private var generations: [String: Int] = [:]

    init(
        retryDelays: [Duration] = HomeBranchLookups.defaultRetryDelays,
        maxInFlight: Int = HomeBranchLookups.defaultMaxInFlight,
        fetch: @escaping @Sendable (String) async -> Outcome
    ) {
        self.retryDelays = retryDelays
        self.maxInFlight = max(1, maxInFlight)
        self.fetch = fetch
    }

    /// `directories` are what `source` shows now, replacing what it showed before; empty
    /// withdraws it.
    func show(_ source: Source, _ directories: [String]) {
        var seen: Set<String> = []
        let next = directories.filter { !$0.isEmpty && seen.insert($0).inserted }
        guard shownBy[source, default: []] != next else { return }
        shownBy[source] = next.isEmpty ? nil : next
        var best: [String: Source] = [:]
        var order: [String] = []
        for each in Source.allCases {
            for directory in shownBy[each, default: []] where best[directory] == nil {
                best[directory] = each
                order.append(directory)
            }
        }
        shown = best
        shownOrder = order
        // Waiting directories nothing shows any more leave; a running request finishes.
        for directory in requested where shown[directory] == nil && !running.contains(directory) {
            let queued = queue.contains { $0.directory == directory }
            guard queued || tasks[directory] != nil else { continue }
            queue.removeAll { $0.directory == directory }
            tasks.removeValue(forKey: directory)?.cancel()
            requested.remove(directory)
        }
        askShown()
    }

    /// Home refresh: every shown directory is asked again. Known branches stay on screen until
    /// the new answers land.
    func refresh() {
        let pending = Array(tasks.values)
        tasks.removeAll()
        queue.removeAll()
        running.removeAll()
        requested.removeAll()
        pending.forEach { $0.cancel() }
        askShown()
    }

    /// Branches the Session Cache remembered, for directories with no answer yet. A cold Home
    /// shows them until the lookups answer, like known branches after `refresh`.
    func seed(_ cached: [String: String]) {
        var changed = false
        for (directory, branch) in cached where branches[directory] == nil {
            branches[directory] = branch
            changed = true
        }
        if changed { onChange?(branches) }
    }

    /// Disconnect or profile switch: forgets everything.
    func clear() {
        let pending = Array(tasks.values)
        tasks.removeAll()
        queue.removeAll()
        running.removeAll()
        requested.removeAll()
        generations.removeAll()
        shownBy.removeAll()
        shown.removeAll()
        shownOrder.removeAll()
        pending.forEach { $0.cancel() }
        guard !branches.isEmpty else { return }
        branches.removeAll()
        onChange?(branches)
    }

    private func askShown() {
        // Source order, so main checkouts queue first.
        for directory in shownOrder where !requested.contains(directory) {
            requested.insert(directory)
            generations[directory, default: 0] += 1
            queue.append((directory, 0))
        }
        pump()
    }

    /// Starts queued lookups while a slot is free, best source first.
    private func pump() {
        while running.count < maxInFlight, !queue.isEmpty {
            let index = queue.indices.min { lhs, rhs in
                (shown[queue[lhs].directory]?.rawValue ?? Int.max, lhs) < (shown[queue[rhs].directory]?.rawValue ?? Int.max, rhs)
            }!
            let next = queue.remove(at: index)
            run(next.directory, attempt: next.attempt)
        }
    }

    private func run(_ directory: String, attempt: Int) {
        let generation = generations[directory] ?? 0
        running.insert(directory)
        let fetch = fetch
        tasks[directory] = Task { @MainActor [weak self] in
            let outcome = await fetch(directory)
            guard let self else { return }
            let current = self.generations[directory] == generation
            if current, self.running.remove(directory) != nil {
                self.tasks[directory] = nil
            }
            if current, !Task.isCancelled, self.requested.contains(directory) {
                self.settle(directory, attempt: attempt, outcome: outcome)
            }
            self.pump()
        }
    }

    private func settle(_ directory: String, attempt: Int, outcome: Outcome) {
        if case let .branch(branch) = outcome {
            store(branch, for: directory)
            return
        }
        // Unknown: stays asked (so nothing re-asks it) until a retry or the next refresh.
        guard attempt < retryDelays.count else { return }
        let delay = retryDelays[attempt]
        let generation = generations[directory] ?? 0
        tasks[directory] = Task { @MainActor [weak self] in
            if delay > .zero {
                try? await Task.sleep(for: delay)
            } else {
                await Task.yield()
            }
            guard !Task.isCancelled, let self, self.generations[directory] == generation else { return }
            self.tasks[directory] = nil
            if self.shown[directory] != nil {
                self.queue.append((directory, attempt + 1))
                self.pump()
            } else {
                self.requested.remove(directory)
            }
        }
    }

    private func store(_ branch: String?, for directory: String) {
        guard branches[directory] != branch else { return }
        branches[directory] = branch
        onChange?(branches)
    }
}
