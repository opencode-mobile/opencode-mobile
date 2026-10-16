import Foundation

/// Add project folder listings. The repository keeps every child folder; the sheet filters by
/// the typed name prefix first and caps the rows after, so a folder past the cap can still be
/// found by typing.
nonisolated enum DirectoryListingPolicy {
    static let maxVisibleDirectories = 200

    /// Every child folder sorted case-insensitively by name, without `.` and `..`. Hidden (dot)
    /// folders are kept; `isHidden` tells them apart. No cap.
    static func directories(_ nodes: [DirectoryNode]) -> [DirectoryNode] {
        nodes
            .filter { $0.isDirectory && !$0.name.isEmpty && $0.name != "." && $0.name != ".." }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    static func isHidden(_ name: String) -> Bool {
        name.hasPrefix(".")
    }

    /// Folders whose name starts with `filter` (case-insensitive), capped at `limit` after
    /// filtering. Hidden folders show only when `filter` itself starts with `.`.
    static func visibleEntries(
        _ directories: [DirectoryNode],
        filter: String,
        limit: Int = maxVisibleDirectories
    ) -> (rows: [DirectoryNode], truncated: Bool) {
        let showHidden = isHidden(filter)
        let matches = directories.filter { node in
            (showHidden || !isHidden(node.name))
                && (filter.isEmpty || node.name.range(of: filter, options: [.caseInsensitive, .anchored]) != nil)
        }
        guard matches.count > limit else { return (matches, false) }
        return (Array(matches.prefix(limit)), true)
    }
}
