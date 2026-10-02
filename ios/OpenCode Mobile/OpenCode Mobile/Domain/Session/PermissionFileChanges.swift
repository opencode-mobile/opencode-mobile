import Foundation

/// Edit/write permission metadata as a compact file list. v2 sends
/// `metadata.files[] {file, additions, deletions, patch}`; v1 sends one
/// `filepath` (or `filePath`) plus a unified `diff`.
nonisolated enum PermissionFileChanges {
    static func from(_ metadata: [String: AnyCodable]) -> [PermissionFileChange] {
        if case let .array(files)? = metadata["files"] {
            return files.compactMap { element in
                guard case let .dictionary(file) = element,
                      let path = file["file"]?.stringValue ?? file["path"]?.stringValue
                else { return nil }
                let counted = file["patch"]?.stringValue.map(count)
                return PermissionFileChange(
                    path: path,
                    additions: int(file["additions"]) ?? counted?.additions ?? 0,
                    deletions: int(file["deletions"]) ?? counted?.deletions ?? 0
                )
            }
        }
        guard let path = metadata["filepath"]?.stringValue ?? metadata["filePath"]?.stringValue else { return [] }
        let counted = metadata["diff"]?.stringValue.map(count)
        return [PermissionFileChange(path: path, additions: counted?.additions ?? 0, deletions: counted?.deletions ?? 0)]
    }

    /// `+`/`-` body lines of a unified diff, ignoring the `+++`/`---` headers.
    static func count(_ patch: String) -> (additions: Int, deletions: Int) {
        var additions = 0
        var deletions = 0
        for line in patch.split(separator: "\n", omittingEmptySubsequences: false) {
            if line.hasPrefix("+++") || line.hasPrefix("---") { continue }
            if line.hasPrefix("+") { additions += 1 } else if line.hasPrefix("-") { deletions += 1 }
        }
        return (additions, deletions)
    }

    private static func int(_ value: AnyCodable?) -> Int? {
        switch value {
        case let .int(number)?: number
        case let .double(number)?: Int(number)
        default: nil
        }
    }
}
