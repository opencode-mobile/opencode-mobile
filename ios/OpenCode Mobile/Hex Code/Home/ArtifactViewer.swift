import Foundation
import OpenCodeKit
import WebKit

/// The in-app browser loading an artifact (m6-proposal.md §2.4, Open question 2 option B): the app
/// fetches every file itself with its signed-in client, through a `WKURLSchemeHandler` for
/// `hexcode-artifact://<id>/<path>`. The web view never holds the credentials, so no password prompt
/// can appear.
enum ArtifactURL {
    static let scheme = "hexcode-artifact"

    /// The artifact's entry: `hexcode-artifact://<id>/`.
    static func start(id: String) -> URL {
        URL(string: "\(scheme)://\(id)/")!
    }

    /// `GET <address>/api/experimental/artifacts/<id>/view/<path>?<query>&location[directory]=<dir>`
    /// for a page URL under the artifact, with the kit's credentials; nil for any other URL. The
    /// host is compared without case: some engines lower-case it.
    static func serverRequest(for url: URL, id: String, directory: String, connection: ServerConnection) -> URLRequest? {
        guard url.scheme?.lowercased() == scheme, url.host?.lowercased() == id.lowercased(),
              let page = URLComponents(url: url, resolvingAgainstBaseURL: false)
        else { return nil }
        var path = page.percentEncodedPath
        while path.hasPrefix("/") { path.removeFirst() }
        var request = connection.authorizedRequest(path: "api/experimental/artifacts/\(id)/view", locationDirectory: directory)
        guard let base = request.url, var server = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return nil }
        server.percentEncodedPath += "/" + path
        if let query = page.percentEncodedQuery, !query.isEmpty {
            server.percentEncodedQuery = [query, server.percentEncodedQuery].compactMap { $0 }.joined(separator: "&")
        }
        request.url = server.url
        // Every type, not the client's JSON.
        request.setValue("*/*", forHTTPHeaderField: "Accept")
        request.cachePolicy = .reloadIgnoringLocalCacheData
        return request
    }

    /// The headers the page gets: the server's, with `text/markdown` shown as plain text (an
    /// `index.md` entry arrives raw; accepted for v1).
    static func pageHeaders(_ headers: [AnyHashable: Any]) -> [String: String] {
        var out: [String: String] = [:]
        for (key, value) in headers {
            guard let key = key as? String else { continue }
            out[key] = "\(value)"
        }
        if let type = out.first(where: { $0.key.lowercased() == "content-type" }), type.value.lowercased().hasPrefix("text/markdown") {
            out[type.key] = "text/plain; charset=utf-8"
        }
        return out
    }
}

/// What the page area shows when the artifact itself fails to load (m6-proposal.md §2.4, Errors).
/// Only the main page counts; a failed image or script leaves the page as it is.
enum ArtifactFailure: Equatable {
    /// Server unreachable or timed out.
    case unreachable
    /// `404`: deleted, or another project's.
    case gone
    /// `403`: artifacts were turned off.
    case off
    /// `401`: the browser closes and home shows Signed out.
    case signedOut
    /// Any other status.
    case failed(Int)

    /// Nil for a page that loaded.
    static func of(status: Int) -> ArtifactFailure? {
        switch status {
        case 200 ..< 400: nil
        case 401: .signedOut
        case 403: .off
        case 404: .gone
        default: .failed(status)
        }
    }

    /// Nil for a cancelled load (a newer navigation).
    static func of(error: any Error) -> ArtifactFailure? {
        let error = error as NSError
        if error.domain == NSURLErrorDomain, error.code == NSURLErrorCancelled { return nil }
        if error.domain == "WebKitErrorDomain", error.code == 102 { return nil }
        return .unreachable
    }

    /// The error's title, detail and buttons; nil for Signed out, which closes the browser.
    func shown(name: String) -> HexBrowserFailure? {
        let retry = String(localized: "common.try_again", defaultValue: "Try again")
        let done = String(localized: "common.done", defaultValue: "Done")
        switch self {
        case .unreachable:
            return HexBrowserFailure(
                title: String(localized: "browser.unreachable", defaultValue: "Couldn’t reach \(name)"),
                detail: String(localized: "browser.unreachable.sub", defaultValue: "Check that the phone is on the same network as the computer, or on Tailscale."),
                button: retry
            )
        case .gone:
            return HexBrowserFailure(
                title: String(localized: "browser.gone", defaultValue: "This artifact is gone"),
                detail: String(localized: "browser.gone.sub", defaultValue: "It was deleted, or it belongs to another project."),
                button: done,
                action: .done
            )
        case .off:
            return HexBrowserFailure(
                title: String(localized: "browser.off", defaultValue: "Artifacts are off on \(name)"),
                detail: String(localized: "browser.off.sub", defaultValue: "Turn on `experimental.artifacts` in OpenCode’s config there."),
                button: done,
                action: .done,
                secondary: String(localized: "browser.off.turn_on", defaultValue: "Turn on…")
            )
        case let .failed(status):
            return HexBrowserFailure(
                title: String(localized: "browser.failed", defaultValue: "Couldn’t open the artifact"),
                detail: String(localized: "browser.failed.sub", defaultValue: "The server answered \(status)."),
                button: retry
            )
        case .signedOut:
            return nil
        }
    }
}

/// Serves `hexcode-artifact://<id>/…` from the server with the app's own client. Status,
/// `Content-Type` and the other headers pass through; the browser reads the main page's status from
/// the navigation response.
@MainActor
final class ArtifactSchemeHandler: NSObject, WKURLSchemeHandler {
    let id: String
    let directory: String
    private let connection: @Sendable () -> ServerConnection?
    private let session: URLSession
    private var running: [ObjectIdentifier: Task<Void, Never>] = [:]
    /// The status each URL last answered: WebKit gives the navigation delegate a plain
    /// `URLResponse` for a custom scheme, without it.
    private(set) var statuses: [URL: Int] = [:]

    init(id: String, directory: String, connection: @escaping @Sendable () -> ServerConnection?, session: URLSession = OpenCodeURLSessions.rest) {
        self.id = id
        self.directory = directory
        self.connection = connection
        self.session = session
    }

    func webView(_ webView: WKWebView, start task: any WKURLSchemeTask) {
        let key = ObjectIdentifier(task)
        let url = task.request.url
        guard let url, let connection = connection(),
              let request = ArtifactURL.serverRequest(for: url, id: id, directory: directory, connection: connection)
        else {
            task.didFailWithError(URLError(.badURL))
            return
        }
        let session = session
        running[key] = Task { [weak self] in
            do {
                let (data, response) = try await session.data(for: request)
                guard let self, self.running[key] != nil else { return }
                self.running[key] = nil
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                self.statuses[url] = status
                let headers = ArtifactURL.pageHeaders((response as? HTTPURLResponse)?.allHeaderFields ?? [:])
                let answer = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)
                    ?? HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: nil)!
                task.didReceive(answer)
                task.didReceive(data)
                task.didFinish()
            } catch {
                guard let self, self.running[key] != nil else { return }
                self.running[key] = nil
                task.didFailWithError(error)
            }
        }
    }

    func webView(_ webView: WKWebView, stop task: any WKURLSchemeTask) {
        running.removeValue(forKey: ObjectIdentifier(task))?.cancel()
    }
}
