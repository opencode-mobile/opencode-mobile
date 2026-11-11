import Foundation

extension URLRequest {
    /// Appends `items` to the URL's query. Every `+` in the query is then sent as `%2B`: the server
    /// reads a bare `+` as a space. No items leaves the request as it is.
    public nonisolated func appendingQueryItems(_ items: [URLQueryItem]) -> URLRequest {
        guard !items.isEmpty, let url, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return self
        }
        components.queryItems = (components.queryItems ?? []) + items
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        var request = self
        request.url = components.url
        return request
    }
}
