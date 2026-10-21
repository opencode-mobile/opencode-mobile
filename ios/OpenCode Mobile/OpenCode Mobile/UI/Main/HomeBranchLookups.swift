import Foundation

/// Current branch per worktree directory for Home rows, loaded behind the session list.
///
/// Only the newest lookup of a directory may write it, so a lookup that started before a Home
/// reload (and finishes late, or is cancelled when the REST pool is replaced) never overwrites a
/// newer answer. An unknown answer (a failed request, or a v2 server still detecting the
/// repository) keeps the last known branch and retries a few times; a directory that stayed
/// unknown is asked again on the next `lookUp`, so non-git folders stop after the retries.
@MainActor
final class HomeBranchLookups {
    nonisolated enum Outcome: Equatable, Sendable {
        /// The server answered; nil for a detached HEAD or a folder without a branch.
        case branch(String?)
        /// The request failed, or the server has not detected the repository yet.
        case unknown
    }

    private(set) var branches: [String: String] = [:]
    /// Called after `branches` changes.
    var onChange: (([String: String]) -> Void)?

    private let fetch: @Sendable (String) async -> Outcome
    private let retryDelays: [Duration]
    private var requested: Set<String> = []
    private var generations: [String: Int] = [:]
    private var tasks: [String: Task<Void, Never>] = [:]

    init(
        retryDelays: [Duration] = [.seconds(1), .seconds(3)],
        fetch: @escaping @Sendable (String) async -> Outcome
    ) {
        self.retryDelays = retryDelays
        self.fetch = fetch
    }

    /// Asks for every directory not already answered or in flight since the last `refresh`.
    func lookUp(_ directories: [String]) {
        for directory in directories where !requested.contains(directory) {
            start(directory)
        }
    }

    /// Home refresh: the next `lookUp` asks every directory again. Known branches stay on screen
    /// until the new answers land.
    func refresh() {
        requested.removeAll()
    }

    /// Disconnect or profile switch: forgets everything.
    func clear() {
        tasks.values.forEach { $0.cancel() }
        tasks.removeAll()
        requested.removeAll()
        generations.removeAll()
        guard !branches.isEmpty else { return }
        branches.removeAll()
        onChange?(branches)
    }

    private func start(_ directory: String) {
        requested.insert(directory)
        let generation = (generations[directory] ?? 0) + 1
        generations[directory] = generation
        tasks[directory]?.cancel()
        let fetch = fetch
        let retryDelays = retryDelays
        tasks[directory] = Task { @MainActor [weak self] in
            var attempt = 0
            while true {
                let outcome = await fetch(directory)
                guard !Task.isCancelled, let self, self.generations[directory] == generation else { return }
                if case let .branch(branch) = outcome {
                    self.store(branch, for: directory)
                    self.tasks[directory] = nil
                    return
                }
                guard attempt < retryDelays.count else {
                    // Unknown for now; the next lookUp asks again.
                    self.requested.remove(directory)
                    self.tasks[directory] = nil
                    return
                }
                let delay = retryDelays[attempt]
                attempt += 1
                if delay > .zero {
                    try? await Task.sleep(for: delay)
                } else {
                    await Task.yield()
                }
            }
        }
    }

    private func store(_ branch: String?, for directory: String) {
        guard branches[directory] != branch else { return }
        branches[directory] = branch
        onChange?(branches)
    }
}
