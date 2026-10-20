import Foundation

/// Links the server hands the app (SSE `status.action.link`, failure sheets) open only as
/// web pages. Any other scheme (`tel:`, `file:`, `shortcuts:`, custom app schemes) is dropped,
/// matching what markdown links already do.
enum ExternalLinkPolicy {
    static func isOpenable(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased() else { return false }
        return scheme == "http" || scheme == "https"
    }

    static func openableURL(_ raw: String?) -> URL? {
        guard let raw = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty,
              let url = URL(string: raw), isOpenable(url)
        else { return nil }
        return url
    }
}
