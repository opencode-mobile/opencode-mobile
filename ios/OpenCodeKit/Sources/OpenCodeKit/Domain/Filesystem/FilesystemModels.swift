import Foundation

public struct ServerPaths: Equatable, Sendable {
    public let home: String
    public let directory: String
    public let worktree: String

    public init(
        home: String,
        directory: String,
        worktree: String
    ) {
        self.home = home
        self.directory = directory
        self.worktree = worktree
    }
}

public struct DirectoryNode: Equatable, Sendable, Identifiable {
    public var id: String { absolutePath }
    public let name: String
    public let absolutePath: String
    public let isDirectory: Bool

    public init(
        name: String,
        absolutePath: String,
        isDirectory: Bool
    ) {
        self.name = name
        self.absolutePath = absolutePath
        self.isDirectory = isDirectory
    }
}

/// One Add project folder listing: every child folder, and whether the folder is a git repo.
public struct DirectoryListing: Equatable, Sendable {
    /// Every child folder, hidden ones included, sorted (see `DirectoryListingPolicy`).
    public let folders: [DirectoryNode]
    /// The raw listing has a `.git` entry: a folder in a repo, a file in a git worktree.
    public let isGitRepository: Bool

    /// Folders a person sees on entering: hidden (dot) folders are left out.
    public var visibleFolderCount: Int {
        folders.lazy.filter { !DirectoryListingPolicy.isHidden($0.name) }.count
    }

    public init(
        folders: [DirectoryNode],
        isGitRepository: Bool
    ) {
        self.folders = folders
        self.isGitRepository = isGitRepository
    }
}

public nonisolated enum FilesystemError: Error, Equatable {
    /// The folder does not exist (or names a file).
    case directoryNotFound
}
