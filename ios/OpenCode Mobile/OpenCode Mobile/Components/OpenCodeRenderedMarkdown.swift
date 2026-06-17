import SwiftUI
import WebKit

enum OpenCodeRenderedMarkdownVariant: String, CaseIterable {
    case assistantMessage
    case outgoingMessage
    case standalone
}

enum OpenCodeRenderedMarkdownDetector {
    private static let markdownPattern =
        #"(?m)(^#{1,6}\s)|(^\s*[-*+]\s+)|(^\s*\d+[.)]\s+)|(^>\s)|(^```)|(^~~~)|(^\s*\|.+\|\s*$)|(^\s{4,}\S)|(^\s*(-{3,}|\*{3,}|_{3,})\s*$)"#

    static func shouldRender(_ text: String) -> Bool {
        text.range(of: markdownPattern, options: .regularExpression) != nil
    }
}

struct OpenCodeRenderedMarkdown: View {
    let markdown: String
    let variant: OpenCodeRenderedMarkdownVariant
    let initialHeight: CGFloat
    let isStreaming: Bool
    let onHeightChange: (CGFloat) -> Void
    let onLinkTap: (URL) -> Void

    @Environment(\.colorScheme) private var colorScheme
    @State private var measuredHeight: CGFloat

    init(
        markdown: String,
        variant: OpenCodeRenderedMarkdownVariant,
        initialHeight: CGFloat = 1,
        isStreaming: Bool = false,
        onHeightChange: @escaping (CGFloat) -> Void = { _ in },
        onLinkTap: @escaping (URL) -> Void = { url in UIApplication.shared.open(url) }
    ) {
        self.markdown = markdown
        self.variant = variant
        self.initialHeight = max(1, initialHeight)
        self.isStreaming = isStreaming
        self.onHeightChange = onHeightChange
        self.onLinkTap = onLinkTap
        _measuredHeight = State(initialValue: max(1, initialHeight))
    }

    static func prewarm() {
        RenderedMarkdownWebView.prewarm()
    }

    var body: some View {
        RenderedMarkdownWebView(
            markdown: markdown,
            variant: variant,
            isStreaming: isStreaming,
            tokens: ThemeManager.shared.tokens(for: colorScheme).tokens,
            initialHeight: initialHeight,
            measuredHeight: $measuredHeight,
            onHeightChange: onHeightChange,
            onLinkTap: onLinkTap
        )
        .frame(height: max(1, measuredHeight))
    }
}

private struct RenderedMarkdownWebView: UIViewRepresentable {
    let markdown: String
    let variant: OpenCodeRenderedMarkdownVariant
    let isStreaming: Bool
    let tokens: [String: String]
    let initialHeight: CGFloat
    @Binding var measuredHeight: CGFloat
    let onHeightChange: (CGFloat) -> Void
    let onLinkTap: (URL) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(
            measuredHeight: $measuredHeight,
            initialHeight: initialHeight,
            onHeightChange: onHeightChange,
            onLinkTap: onLinkTap
        )
    }

    func makeUIView(context: Context) -> WKWebView {
        let controller = WKUserContentController()
        controller.add(context.coordinator, name: "openCodeRenderedMarkdown")

        let configuration = WKWebViewConfiguration()
        configuration.processPool = Self.processPool
        configuration.websiteDataStore = .nonPersistent()
        configuration.userContentController = controller
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.showsVerticalScrollIndicator = false
        webView.scrollView.showsHorizontalScrollIndicator = false

        context.coordinator.webView = webView
        if let html = Self.rendererHTML() {
            webView.loadHTMLString(html, baseURL: nil)
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        let signature = Payload.signature(markdown: markdown, variant: variant.rawValue, isStreaming: isStreaming, tokens: tokens)
        let payload = Payload(
            markdown: markdown,
            variant: variant.rawValue,
            isStreaming: isStreaming,
            tokens: tokens,
            renderId: String(signature.hashValue)
        )
        context.coordinator.render(payload, signature: signature)
    }

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "openCodeRenderedMarkdown")
    }

    static func prewarm() {
        _ = rendererHTML()
        _ = processPool
    }

    private static let processPool = WKProcessPool()
    private static var cachedRendererHTML: String?

    private static func rendererHTML() -> String? {
        if let cachedRendererHTML {
            return cachedRendererHTML
        }
        let nestedURL = Bundle.main.url(
            forResource: "rendered-markdown",
            withExtension: "html",
            subdirectory: "Resources/RenderedMarkdown"
        )
        let rootURL = Bundle.main.url(forResource: "rendered-markdown", withExtension: "html")
        guard let url = nestedURL ?? rootURL else { return nil }
        let html = try? String(contentsOf: url, encoding: .utf8)
        cachedRendererHTML = html
        return html
    }

    fileprivate struct Payload: Encodable {
        let markdown: String
        let variant: String
        let isStreaming: Bool
        let tokens: [String: String]
        let renderId: String

        static func signature(
            markdown: String,
            variant: String,
            isStreaming: Bool,
            tokens: [String: String]
        ) -> String {
            let tokenSignature = tokens.keys.sorted().map { key in
                "\(key)=\(tokens[key] ?? "")"
            }.joined(separator: "\u{1f}")
            return [
                markdown,
                variant,
                isStreaming ? "streaming" : "settled",
                tokenSignature,
            ].joined(separator: "\u{1e}")
        }
    }

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        weak var webView: WKWebView?
        private var isReady = false
        private var pendingPayload: Payload?
        private var pendingSignature: String?
        private var lastRenderedSignature: String?
        private var activeRenderID: String?
        private var lastReportedHeight: CGFloat
        private var pendingHeight: CGFloat?
        private var heightFlushScheduled = false
        private let measuredHeight: Binding<CGFloat>
        private let onHeightChange: (CGFloat) -> Void
        private let onLinkTap: (URL) -> Void

        init(
            measuredHeight: Binding<CGFloat>,
            initialHeight: CGFloat,
            onHeightChange: @escaping (CGFloat) -> Void,
            onLinkTap: @escaping (URL) -> Void
        ) {
            self.measuredHeight = measuredHeight
            self.lastReportedHeight = max(1, initialHeight)
            self.onHeightChange = onHeightChange
            self.onLinkTap = onLinkTap
        }

        fileprivate func render(_ payload: Payload, signature: String) {
            if signature == lastRenderedSignature || signature == pendingSignature {
                return
            }
            pendingPayload = payload
            pendingSignature = signature
            guard isReady else { return }
            flushPendingPayload()
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any],
                  let type = body["type"] as? String else { return }

            switch type {
            case "ready":
                isReady = true
                flushPendingPayload()
            case "height":
                guard let renderID = body["renderId"] as? String,
                      renderID == activeRenderID,
                      let height = body["height"] as? Double else { return }
                let nextHeight = max(1, CGFloat(height))
                enqueueHeight(nextHeight)
            case "link":
                guard let value = body["url"] as? String,
                      let renderID = body["renderId"] as? String,
                      renderID == activeRenderID,
                      let url = URL(string: value),
                      url.scheme == "http" || url.scheme == "https" else { return }
                onLinkTap(url)
            default:
                break
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            if navigationAction.navigationType == .other {
                decisionHandler(.allow)
                return
            }
            decisionHandler(.cancel)
        }

        private func flushPendingPayload() {
            guard let payload = pendingPayload,
                  let signature = pendingSignature,
                  let data = try? JSONEncoder().encode(payload),
                  let json = String(data: data, encoding: .utf8) else { return }
            guard signature != lastRenderedSignature else {
                pendingPayload = nil
                pendingSignature = nil
                return
            }
            pendingPayload = nil
            pendingSignature = nil
            activeRenderID = payload.renderId
            lastRenderedSignature = signature
            webView?.evaluateJavaScript("window.OpenCodeRenderedMarkdown.render(\(json));")
        }

        private func enqueueHeight(_ height: CGFloat) {
            pendingHeight = height
            guard !heightFlushScheduled else { return }
            heightFlushScheduled = true
            DispatchQueue.main.async { [weak self] in
                self?.flushHeight()
            }
        }

        private func flushHeight() {
            heightFlushScheduled = false
            guard let nextHeight = pendingHeight else { return }
            pendingHeight = nil
            guard abs(lastReportedHeight - nextHeight) > 1 else { return }
            lastReportedHeight = nextHeight
            measuredHeight.wrappedValue = nextHeight
            onHeightChange(nextHeight)
        }
    }
}

#Preview("Rendered Markdown") {
    OpenCodeRenderedMarkdown(
        markdown: """
        ### Implementation

        - Keep plain text native.
        - Render code when needed.

        ```swift
        let answer = "ship it"
        ```
        """,
        variant: .standalone
    )
    .padding()
    .openCodeTheme()
}
