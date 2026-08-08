import Foundation

extension URL {
    nonisolated func appendingDirectoryQuery(_ directory: String?) -> URL {
        guard let directory, !directory.isEmpty else { return self }
        guard var components = URLComponents(url: self, resolvingAgainstBaseURL: false) else { return self }
        var items = components.queryItems ?? []
        items.append(URLQueryItem(name: "directory", value: directory))
        components.queryItems = items
        return components.url ?? self
    }
}
