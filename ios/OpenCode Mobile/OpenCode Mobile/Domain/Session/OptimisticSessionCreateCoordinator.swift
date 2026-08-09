import Foundation

@MainActor
final class OptimisticSessionCreateCoordinator {
    static let shared = OptimisticSessionCreateCoordinator()

    private var failureHandlers: [(String) -> Void] = []

    func onCreateFailed(_ handler: @escaping (String) -> Void) {
        failureHandlers.append(handler)
    }

    func reportCreateFailed(_ message: String) {
        failureHandlers.forEach { $0(message) }
    }
}
