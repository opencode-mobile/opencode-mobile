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

/// One Add project folder listing: every child folder, and whether the folder is a git repo.
struct DirectoryListing: Equatable, Sendable {
    /// Every child folder, hidden ones included, sorted (see `DirectoryListingPolicy`).
    let folders: [DirectoryNode]
    /// The raw listing has a `.git` entry: a folder in a repo, a file in a git worktree.
    let isGitRepository: Bool

    /// Folders a person sees on entering: hidden (dot) folders are left out.
    var visibleFolderCount: Int {
        folders.lazy.filter { !DirectoryListingPolicy.isHidden($0.name) }.count
    }
}

nonisolated enum FilesystemError: Error, Equatable {
    /// The folder does not exist (or names a file).
    case directoryNotFound
}
