import Foundation

struct ServerPaths: Equatable, Sendable {
    let home: String
    let directory: String
    let worktree: String
}

struct DirectoryNode: Equatable, Sendable, Identifiable {
    var id: String { absolutePath }
    let name: String
    let absolutePath: String
    let isDirectory: Bool
}
