import Foundation

public nonisolated enum SseSubscribeTargets {
    public struct Target: Equatable, Sendable {
        public let path: String
        public let directory: String?

        public init(path: String, directory: String?) {
            self.path = path
            self.directory = directory
        }

        public var key: String {
            if let directory {
                return "\(path):\(directory)"
            }
            return path
        }
    }

    public static func targets(protocol serverProtocol: ServerProtocol, directories: [String] = []) -> [Target] {
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
