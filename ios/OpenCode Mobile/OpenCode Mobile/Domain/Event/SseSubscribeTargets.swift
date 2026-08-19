import Foundation

nonisolated enum SseSubscribeTargets {
    struct Target: Equatable, Sendable {
        let path: String
        let directory: String?
    }

    static func targets(protocol serverProtocol: ServerProtocol, directories: [String] = []) -> [Target] {
        switch serverProtocol {
        case .v2:
            [Target(path: "api/event", directory: nil)]
        case .v1:
            [Target(path: "global/event", directory: nil)] +
                directories
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .uniqued()
                .map { Target(path: "event", directory: $0) }
        }
    }
}

private extension Array where Element == String {
    func uniqued() -> [String] {
        var seen = Set<String>()
        return filter { seen.insert($0).inserted }
    }
}
