import Foundation

/// Runs `action` once per burst: the first request starts a short wait, requests during it join
/// it, and a request after the action fired starts a new wait. Never starved by a steady stream.
@MainActor
final class CoalescedRefresh {
    static let defaultWindow = Duration.milliseconds(250)

    private let sleep: @Sendable () async throws -> Void
    private var waiting: Task<Void, Never>?
    private var action: (@MainActor () -> Void)?

    init(sleep: @escaping @Sendable () async throws -> Void = { try await Task.sleep(for: CoalescedRefresh.defaultWindow) }) {
        self.sleep = sleep
    }

    func request(_ action: @escaping @MainActor () -> Void) {
        self.action = action
        guard waiting == nil else { return }
        let sleep = sleep
        waiting = Task { [weak self] in
            do {
                try await sleep()
            } catch {
                return
            }
            guard let self, !Task.isCancelled else { return }
            self.waiting = nil
            let pending = self.action
            self.action = nil
            pending?()
        }
    }

    func cancel() {
        waiting?.cancel()
        waiting = nil
        action = nil
    }
}
