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

    private static func isVisibleDirectoryName(_ name: String) -> Bool {
        if name.isEmpty || name == "." || name == ".." { return false }
        return !name.hasPrefix(".")
    }
}
