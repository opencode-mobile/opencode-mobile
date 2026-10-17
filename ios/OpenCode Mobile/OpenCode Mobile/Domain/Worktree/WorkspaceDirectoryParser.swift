import Foundation

/// V1 `GET /experimental/workspace` bodies. A recognized shape is an array, or an
/// object whose `data` is an array, of strings or `{ "directory": "..." }`.
/// Nil means the body is not that shape.
enum WorkspaceDirectoryParser {
    static func parse(_ body: String) -> [String]? {
        guard let data = body.data(using: .utf8),
              let element = try? JSONSerialization.jsonObject(with: data)
        else { return nil }
        let array: [Any]
        if let values = element as? [Any] {
            array = values
        } else if let object = element as? [String: Any], let data = object["data"] as? [Any] {
            array = data
        } else {
            return nil
        }
        var directories: [String] = []
        for item in array {
            if let value = item as? String {
                let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty { directories.append(trimmed) }
                continue
            }
            guard let object = item as? [String: Any] else { return nil }
            guard let directory = object["directory"] else { continue }
            if directory is NSNull { continue }
            guard let value = directory as? String else { return nil }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { directories.append(trimmed) }
        }
        return directories
    }
}
