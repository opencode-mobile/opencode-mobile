import Foundation

/// The one way a server query string is built. Foundation's `queryItems` setter leaves a `+` in a
/// value bare, and the server reads a bare `+` as a space, so every `+` goes out as `%2B` (as Ktor
/// sends it on Android). Reading `queryItems` back decodes `%2B` to `+`, so code that re-assigns
/// `queryItems` would undo it: append through these helpers instead.
extension URLComponents {
    /// Appends `items` to the query, leaving what is already there as it is. No items is a no-op.
    public nonisolated mutating func appendQueryItems(_ items: [URLQueryItem]) {
        guard !items.isEmpty else { return }
        var added = URLComponents()
        added.queryItems = items
        let encoded = (added.percentEncodedQuery ?? "").replacingOccurrences(of: "+", with: "%2B")
        percentEncodedQuery = [percentEncodedQuery, encoded]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: "&")
    }
}

extension URL {
    /// This URL with `items` appended to its query; see `URLComponents.appendQueryItems`.
    public nonisolated func appendingQueryItems(_ items: [URLQueryItem]) -> URL {
        guard !items.isEmpty, var components = URLComponents(url: self, resolvingAgainstBaseURL: false) else {
            return self
        }
        components.appendQueryItems(items)
        return components.url ?? self
    }
}

extension URLRequest {
    /// This request with `items` appended to its URL's query; see `URLComponents.appendQueryItems`.
    public nonisolated func appendingQueryItems(_ items: [URLQueryItem]) -> URLRequest {
        guard !items.isEmpty, let url else { return self }
        var request = self
        request.url = url.appendingQueryItems(items)
        return request
    }
}
