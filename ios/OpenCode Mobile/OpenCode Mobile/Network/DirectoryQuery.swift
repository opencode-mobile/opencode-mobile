import Foundation

extension URL {
    nonisolated func appendingDirectoryQuery(_ directory: String?) -> URL {
        appendingQueryItem(name: "directory", value: directory)
    }

    /// V2 deepObject location query: `location[directory]=…`.
    nonisolated func appendingLocationDirectoryQuery(_ directory: String?) -> URL {
        appendingQueryItem(name: "location[directory]", value: directory)
    }

    private nonisolated func appendingQueryItem(name: String, value: String?) -> URL {
        guard let value, !value.isEmpty else { return self }
        guard var components = URLComponents(url: self, resolvingAgainstBaseURL: false) else { return self }
        var items = components.queryItems ?? []
        items.append(URLQueryItem(name: name, value: value))
        components.queryItems = items
        return components.url ?? self
    }
}
