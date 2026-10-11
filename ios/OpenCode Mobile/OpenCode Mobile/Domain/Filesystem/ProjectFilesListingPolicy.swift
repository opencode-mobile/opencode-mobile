import Foundation

/// File Browser listing rules: folders first, then case-insensitive name; dot entries only
/// when Show hidden files is on. No cap. Add Project keeps `DirectoryListingPolicy`.
nonisolated enum ProjectFilesListingPolicy {
    static func visible(_ entries: [ProjectEntry], showHidden: Bool) -> [ProjectEntry] {
        sorted(entries.filter { entry in
            guard !entry.name.isEmpty, entry.name != ".", entry.name != ".." else { return false }
            return showHidden || !entry.isHidden
        })
    }

    static func sorted(_ entries: [ProjectEntry]) -> [ProjectEntry] {
        entries.sorted { lhs, rhs in
            if lhs.isDirectory != rhs.isDirectory { return lhs.isDirectory }
            let order = lhs.name.caseInsensitiveCompare(rhs.name)
            if order != .orderedSame { return order == .orderedAscending }
            return lhs.name < rhs.name
        }
    }
}
