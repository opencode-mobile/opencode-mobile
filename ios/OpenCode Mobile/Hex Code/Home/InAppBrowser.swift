import Observation
import SwiftUI
import UIKit
import WebKit
import OpenCodeKit

/// Where a navigation inside the in-app browser goes (app plan, "The in-app browser" › Inside the
/// page): the page's own origin stays; another `http` or `https` origin opens in the system
/// browser, and so do `mailto:`, `tel:` and other schemes.
enum BrowserPolicy {
    enum Decision: Equatable {
        case allow
        case system
    }

    static func decide(_ url: URL, home: URL, mainFrame: Bool) -> Decision {
        let scheme = url.scheme?.lowercased() ?? ""
        // The page's own documents: about:blank, srcdoc, blobs and data URLs.
        if ["about", "blob", "data", "javascript"].contains(scheme) { return .allow }
        // An artifact's own files, served by the app.
        if scheme == ArtifactURL.scheme { return sameOrigin(url, home) ? .allow : .system }
        guard scheme == "http" || scheme == "https" else { return .system }
        // A frame inside the page may come from anywhere; only the page itself is kept to its origin.
        guard mainFrame else { return .allow }
        return sameOrigin(url, home) ? .allow : .system
    }

    static func sameOrigin(_ left: URL, _ right: URL) -> Bool {
        left.scheme?.lowercased() == right.scheme?.lowercased()
            && left.host?.lowercased() == right.host?.lowercased()
            && port(left) == port(right)
    }

    private static func port(_ url: URL) -> Int? {
        url.port ?? (url.scheme?.lowercased() == "https" ? 443 : 80)
    }
}

/// What the page area shows when the page itself fails to load (app plan, "Errors"). A dev server's
/// own `4xx` or `5xx` page isn't an error, and neither is a failed image or script.
enum BrowserFailure {
    static func of(_ error: Error, url: URL, name: String) -> HexBrowserFailure? {
        let error = error as NSError
        // Cancelled: a newer navigation, or one sent to the system browser.
        if error.domain == NSURLErrorDomain, error.code == NSURLErrorCancelled { return nil }
        if error.domain == "WebKitErrorDomain", error.code == 102 { return nil }
        let retry = String(localized: "common.try_again", defaultValue: "Try again")
        let refused = [NSURLErrorCannotConnectToHost, NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed, NSURLErrorBadServerResponse]
        if error.domain == NSURLErrorDomain, refused.contains(error.code) {
            // Digits only: a port is never "5,173".
            let port = String(url.port ?? (url.scheme == "https" ? 443 : 80))
            return HexBrowserFailure(
                title: String(localized: "browser.dev_unreachable", defaultValue: "Couldn’t reach port \(port) on \(name)"),
                detail: String(
                    localized: "browser.dev_unreachable.sub",
                    defaultValue: "The dev server may only listen on the computer itself. Start it so it listens on the network (for Vite, add `--host`), then try again."
                ),
                button: retry
            )
        }
        return HexBrowserFailure(
            title: String(localized: "browser.unreachable", defaultValue: "Couldn’t reach \(name)"),
            detail: String(
                localized: "browser.unreachable.sub",
                defaultValue: "Check that the phone is on the same network as the computer, or on Tailscale."
            ),
            button: retry
        )
    }
}

/// A page on the computer in a `WKWebView`, with no credentials: it replaces the Safari view for
/// `localhost` tiles so the app can explain a dev server that only listens on the computer.
/// Website data is kept between opens (the default store); Forget this computer clears it. An
/// artifact's files are fetched by the app itself (`ArtifactSchemeHandler`).
@MainActor
@Observable
final class BrowserModel {
    let start: URL
    let label: String
    let host: String
    let serverName: String
    private(set) var title: String?
    private(set) var progress: Double?
    private(set) var canGoBack = false
    private(set) var canGoForward = false
    private(set) var failure: HexBrowserFailure?
    /// The page shown now, for "Open in Safari".
    private(set) var current: URL
    /// The artifact shown, for an artifact tile.
    let artifact: CardArtifact?
    /// An artifact answered `401`: the browser closes and home shows Signed out.
    @ObservationIgnored var onSignedOut: () -> Void = {}
    /// The `403` error's "Turn on…": the enable sheet.
    @ObservationIgnored var onTurnOn: () -> Void = {}

    @ObservationIgnored let webView: WKWebView
    @ObservationIgnored private var schemeHandler: ArtifactSchemeHandler?
    @ObservationIgnored private var driver: BrowserDriver?
    @ObservationIgnored private var observations: [NSKeyValueObservation] = []
    /// The page whose load failed, for Try again.
    @ObservationIgnored private var failedURL: URL?

    convenience init(destination: CardLinks.Destination, serverName: String) {
        self.init(
            start: destination.url,
            label: destination.label.isEmpty ? destination.shownHost : destination.label,
            host: destination.shownHost,
            serverName: serverName,
            artifact: nil,
            handler: nil
        )
    }

    /// An artifact, every file through the app's signed-in client.
    convenience init(artifact: CardArtifact, serverName: String, directory: String, connection: @escaping @Sendable () -> ServerConnection?) {
        self.init(
            start: ArtifactURL.start(id: artifact.id),
            label: artifact.title,
            host: String(localized: "browser.artifact", defaultValue: "\(serverName) · artifact"),
            serverName: serverName,
            artifact: artifact,
            handler: ArtifactSchemeHandler(id: artifact.id, directory: directory, connection: connection)
        )
    }

    private init(start: URL, label: String, host: String, serverName: String, artifact: CardArtifact?, handler: ArtifactSchemeHandler?) {
        self.start = start
        current = start
        self.label = label
        self.host = host
        self.serverName = serverName
        self.artifact = artifact
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        if let handler { configuration.setURLSchemeHandler(handler, forURLScheme: ArtifactURL.scheme) }
        configuration.allowsInlineMediaPlayback = true
        webView = WKWebView(frame: .zero, configuration: configuration)
        // The edge swipe goes back.
        webView.allowsBackForwardNavigationGestures = true
        webView.isInspectable = true
        let driver = BrowserDriver(model: self)
        self.driver = driver
        webView.navigationDelegate = driver
        webView.uiDelegate = driver
        observations = [
            webView.observe(\.title, options: [.new]) { [weak self] view, _ in
                MainActor.assumeIsolated { self?.title = view.title.flatMap { $0.isEmpty ? nil : $0 } }
            },
            webView.observe(\.estimatedProgress, options: [.new]) { [weak self] view, _ in
                MainActor.assumeIsolated { self?.progress = view.isLoading ? view.estimatedProgress : nil }
            },
            webView.observe(\.isLoading, options: [.new]) { [weak self] view, _ in
                MainActor.assumeIsolated { self?.progress = view.isLoading ? view.estimatedProgress : nil }
            },
            webView.observe(\.canGoBack, options: [.new]) { [weak self] view, _ in
                MainActor.assumeIsolated { self?.canGoBack = view.canGoBack }
            },
            webView.observe(\.canGoForward, options: [.new]) { [weak self] view, _ in
                MainActor.assumeIsolated { self?.canGoForward = view.canGoForward }
            },
            webView.observe(\.url, options: [.new]) { [weak self] view, _ in
                MainActor.assumeIsolated { if let url = view.url { self?.current = url } }
            },
        ]
        schemeHandler = handler
        handler?.onMainPage = { [weak self] result in self?.mainPage(result) }
        webView.load(URLRequest(url: start))
    }

    var isArtifact: Bool { artifact != nil }

    /// The artifact's own page answered: a status other than success fills the page area.
    private func mainPage(_ result: Result<Int, any Error>) {
        let failure: ArtifactFailure?
        switch result {
        case let .success(status): failure = ArtifactFailure.of(status: status)
        case let .failure(error): failure = ArtifactFailure.of(error: error)
        }
        guard let failure else { return }
        if failure == .signedOut { return onSignedOut() }
        show(failure.shown(name: serverName), url: current)
    }

    /// The page's title, else the tile's label.
    var shownTitle: String { title ?? label }

    func reload() {
        if let failedURL {
            failure = nil
            self.failedURL = nil
            webView.load(URLRequest(url: failedURL))
        } else if webView.url == nil {
            webView.load(URLRequest(url: start))
        } else {
            webView.reload()
        }
    }

    func back() { webView.goBack() }
    func forward() { webView.goForward() }

    func openInSystemBrowser() {
        UIApplication.shared.open(current)
    }

    fileprivate func started() {
        failure = nil
        failedURL = nil
    }

    fileprivate func failed(_ error: Error, url: URL?) {
        let url = url ?? current
        if isArtifact {
            // The page's own failure came through the handler.
            guard failure == nil, let shown = ArtifactFailure.of(error: error)?.shown(name: serverName) else { return }
            return show(shown, url: url)
        }
        show(BrowserFailure.of(error, url: url, name: serverName), url: url)
    }

    private func show(_ shown: HexBrowserFailure?, url: URL) {
        guard let shown else { return }
        failure = shown
        failedURL = url
        UIAccessibility.post(notification: .announcement, argument: shown.title)
    }
}

/// The web view's delegates, kept apart from the model so it stays a plain observable class.
@MainActor
private final class BrowserDriver: NSObject, WKNavigationDelegate, WKUIDelegate {
    weak var model: BrowserModel?

    init(model: BrowserModel) {
        self.model = model
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url, let model else { return decisionHandler(.allow) }
        switch BrowserPolicy.decide(url, home: model.start, mainFrame: action.targetFrame?.isMainFrame ?? true) {
        case .allow:
            decisionHandler(.allow)
        case .system:
            decisionHandler(.cancel)
            UIApplication.shared.open(url)
        }
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        MainActor.assumeIsolated { model?.started() }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        let url = (error as NSError).userInfo[NSURLErrorFailingURLErrorKey] as? URL
        MainActor.assumeIsolated { model?.failed(error, url: url) }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        let url = (error as NSError).userInfo[NSURLErrorFailingURLErrorKey] as? URL
        MainActor.assumeIsolated { model?.failed(error, url: url) }
    }

    /// `target=_blank` opens in the same view.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if action.targetFrame == nil { webView.load(action.request) }
        return nil
    }

    // `alert`, `confirm` and `prompt` show system alerts.

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor () -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: String(localized: "common.ok", defaultValue: "OK"), style: .default) { _ in completionHandler() })
        present(alert, from: webView) { completionHandler() }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor (Bool) -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: String(localized: "common.cancel", defaultValue: "Cancel"), style: .cancel) { _ in completionHandler(false) })
        alert.addAction(UIAlertAction(title: String(localized: "common.ok", defaultValue: "OK"), style: .default) { _ in completionHandler(true) })
        present(alert, from: webView) { completionHandler(false) }
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor (String?) -> Void) {
        let alert = UIAlertController(title: nil, message: prompt, preferredStyle: .alert)
        alert.addTextField { $0.text = defaultText }
        alert.addAction(UIAlertAction(title: String(localized: "common.cancel", defaultValue: "Cancel"), style: .cancel) { _ in completionHandler(nil) })
        alert.addAction(UIAlertAction(title: String(localized: "common.ok", defaultValue: "OK"), style: .default) { [weak alert] _ in
            completionHandler(alert?.textFields?.first?.text ?? "")
        })
        present(alert, from: webView) { completionHandler(nil) }
    }

    /// From the controller on top of the web view's window; with none, the page gets its default answer.
    private func present(_ alert: UIAlertController, from view: UIView, otherwise: () -> Void) {
        var top = view.window?.rootViewController
        while let next = top?.presentedViewController { top = next }
        guard let top else { return otherwise() }
        top.present(alert, animated: true)
    }
}

/// The web view itself.
private struct BrowserWebView: UIViewRepresentable {
    let webView: WKWebView

    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ view: WKWebView, context: Context) {}
}

/// The in-app browser, full screen.
struct BrowserScreen: View {
    let model: BrowserModel
    let onDone: () -> Void

    var body: some View {
        HexBrowser(
            title: model.shownTitle,
            host: model.host,
            progress: model.progress,
            canGoBack: model.canGoBack,
            canGoForward: model.canGoForward,
            failure: model.failure,
            openSystemTitle: model.isArtifact ? nil : LocalizedStringResource("browser.open_system", defaultValue: "Open in Safari"),
            onDone: onDone,
            onReload: { model.reload() },
            onBack: { model.back() },
            onForward: { model.forward() },
            onOpenSystem: { model.openInSystemBrowser() },
            onRetry: { model.reload() },
            onSecondary: { model.onTurnOn() }
        ) {
            BrowserWebView(webView: model.webView)
                .ignoresSafeArea(edges: .bottom)
        }
    }
}
