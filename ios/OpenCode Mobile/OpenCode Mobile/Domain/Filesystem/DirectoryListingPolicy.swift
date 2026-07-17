import Foundation

enum DirectoryListingPolicy {
    static let maxVisibleDirectories = 200

    static func prepare(_ nodes: [DirectoryNode]) -> [DirectoryNode] {
        nodes
            .filter(\.isDirectory)
            .filter { isVisibleDirectoryName($0.name) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            .prefix(maxVisibleDirectories)
            .map { $0 }
    }

    static func parentPath(_ path: String) -> String? {
        if path.isEmpty || path == "/" { return nil }
        let url = URL(fileURLWithPath: path)
        let parent = url.deletingLastPathComponent().path
        if parent.isEmpty { return "/" }
        if parent == path { return nil }
        return parent
    }

    private static func isVisibleDirectoryName(_ name: String) -> Bool {
        if name.isEmpty || name == "." || name == ".." { return false }
        return !name.hasPrefix(".")
    }
}
