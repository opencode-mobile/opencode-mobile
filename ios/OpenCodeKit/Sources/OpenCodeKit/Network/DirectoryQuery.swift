import Foundation

extension URL {
    public nonisolated func appendingDirectoryQuery(_ directory: String?) -> URL {
        appendingQueryItem(name: "directory", value: directory)
    }

    /// V2 deepObject location query: `location[directory]=…`.
    public nonisolated func appendingLocationDirectoryQuery(_ directory: String?) -> URL {
        appendingQueryItem(name: "location[directory]", value: directory)
    }

    private nonisolated func appendingQueryItem(name: String, value: String?) -> URL {
        guard let value, !value.isEmpty else { return self }
        return appendingQueryItems([URLQueryItem(name: name, value: value)])
    }
}
