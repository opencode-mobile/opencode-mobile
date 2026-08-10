import Foundation

@MainActor
final class OptimisticSessionCreateCoordinator {
    static let shared = OptimisticSessionCreateCoordinator()

    private final class WeakHandler {
        weak var owner: AnyObject?
        let handler: (String) -> Void

        init(owner: AnyObject, handler: @escaping (String) -> Void) {
            self.owner = owner
            self.handler = handler
        }
    }

    private var failureHandlers: [WeakHandler] = []

    func onCreateFailed(owner: AnyObject, _ handler: @escaping (String) -> Void) {
        prune()
        failureHandlers.append(WeakHandler(owner: owner, handler: handler))
    }

    func reportCreateFailed(_ message: String) {
        prune()
        failureHandlers.forEach { $0.handler(message) }
    }

    private func prune() {
        failureHandlers.removeAll { $0.owner == nil }
    }
}
