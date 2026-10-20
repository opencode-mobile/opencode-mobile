import Foundation

/// What a redirected request may carry.
///
/// The `Authorization` header is set by hand on every request, so URLSession would
/// forward it wherever a 3xx points. A downgrade (`https` → `http`) is refused outright;
/// a redirect to another origin continues without the credentials; a same-origin
/// redirect passes through unchanged.
enum RedirectPolicy {
    enum Decision: Equatable {
        case follow(URLRequest)
        case refuse
    }

    static func decide(original: URLRequest?, redirect: URLRequest) -> Decision {
        guard let from = original?.url, let to = redirect.url else { return .refuse }
        let fromScheme = from.scheme?.lowercased() ?? ""
        let toScheme = to.scheme?.lowercased() ?? ""
        if fromScheme == "https", toScheme != "https" {
            return .refuse
        }
        if sameOrigin(from, to) {
            return .follow(redirect)
        }
        var stripped = redirect
        stripped.setValue(nil, forHTTPHeaderField: "Authorization")
        stripped.setValue(nil, forHTTPHeaderField: "Cookie")
        return .follow(stripped)
    }

    static func sameOrigin(_ a: URL, _ b: URL) -> Bool {
        guard let aScheme = a.scheme?.lowercased(), let bScheme = b.scheme?.lowercased(),
              let aHost = a.host?.lowercased(), let bHost = b.host?.lowercased()
        else { return false }
        return aScheme == bScheme && aHost == bHost && effectivePort(a) == effectivePort(b)
    }

    private static func effectivePort(_ url: URL) -> Int {
        if let port = url.port { return port }
        switch url.scheme?.lowercased() {
        case "https", "wss": return 443
        default: return 80
        }
    }
}

/// Session delegate that applies `RedirectPolicy` to every task of the session.
final class RedirectPolicyDelegate: NSObject, URLSessionTaskDelegate, Sendable {
    static let shared = RedirectPolicyDelegate()

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        switch RedirectPolicy.decide(original: task.originalRequest, redirect: request) {
        case let .follow(next):
            completionHandler(next)
        case .refuse:
            DiagnosticLog.warning(
                .connection,
                "refused redirect status=\(response.statusCode) to scheme=\(request.url?.scheme ?? "?")"
            )
            completionHandler(nil)
        }
    }
}
