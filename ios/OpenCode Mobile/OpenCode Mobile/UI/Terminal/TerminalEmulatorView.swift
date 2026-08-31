import SwiftUI
import WebKit

struct TerminalEmulatorView: UIViewRepresentable {
    let viewModel: TerminalViewModel
    let backgroundHex: String
    let foregroundHex: String
    let onData: (String) -> Void
    let onResize: (Int, Int) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(viewModel: viewModel, backgroundHex: backgroundHex, foregroundHex: foregroundHex)
    }

    func makeUIView(context: Context) -> WKWebView {
        let userContent = WKUserContentController()
        userContent.add(context.coordinator, name: "terminal")
        let config = WKWebViewConfiguration()
        config.userContentController = userContent
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        config.preferences.setValue(false, forKey: "allowFileAccessFromFileURLs")
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        webView.navigationDelegate = context.coordinator
        context.coordinator.webView = webView
        context.coordinator.onData = onData
        context.coordinator.onResize = onResize
        viewModel.attachOutput { [weak coordinator = context.coordinator] text in
            coordinator?.write(text)
        }
        if let indexURL = terminalIndexURL() {
            webView.loadFileURL(indexURL, allowingReadAccessTo: indexURL.deletingLastPathComponent())
        }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        context.coordinator.backgroundHex = backgroundHex
        context.coordinator.foregroundHex = foregroundHex
        context.coordinator.onData = onData
        context.coordinator.onResize = onResize
        if context.coordinator.pageReady {
            context.coordinator.applyTheme()
            context.coordinator.fit()
        }
    }

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        coordinator.viewModel.detachOutput()
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "terminal")
    }

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        let viewModel: TerminalViewModel
        var backgroundHex: String
        var foregroundHex: String
        var onData: (String) -> Void = { _ in }
        var onResize: (Int, Int) -> Void = { _, _ in }
        weak var webView: WKWebView?
        var pageReady = false
        private var pending: [String] = []

        init(viewModel: TerminalViewModel, backgroundHex: String, foregroundHex: String) {
            self.viewModel = viewModel
            self.backgroundHex = backgroundHex
            self.foregroundHex = foregroundHex
        }

        func write(_ text: String) {
            if pageReady {
                evaluateWrite(text)
            } else {
                pending.append(text)
            }
        }

        func applyTheme() {
            let theme: [String: String] = [
                "background": backgroundHex,
                "foreground": foregroundHex,
                "cursor": foregroundHex,
            ]
            guard let data = try? JSONSerialization.data(withJSONObject: theme),
                  let json = String(data: data, encoding: .utf8)
            else { return }
            webView?.evaluateJavaScript("window.terminalSetTheme(\(json))", completionHandler: nil)
        }

        func fit() {
            webView?.evaluateJavaScript("window.terminalFit()", completionHandler: nil)
        }

        func userContentController(
            _ userContentController: WKUserContentController,
            didReceive message: WKScriptMessage
        ) {
            let body: [String: Any]
            if let object = message.body as? [String: Any] {
                body = object
            } else if let text = message.body as? String,
                      let data = text.data(using: .utf8),
                      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            {
                body = object
            } else {
                return
            }
            switch body["type"] as? String {
            case "ready":
                pageReady = true
                applyTheme()
                pending.forEach(evaluateWrite)
                pending.removeAll()
                fit()
            case "data":
                if let text = body["data"] as? String {
                    onData(text)
                }
            case "resize":
                onResize(intValue(body["cols"]), intValue(body["rows"]))
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

        private func evaluateWrite(_ text: String) {
            guard let encoded = terminalJsStringLiteral(text) else { return }
            webView?.evaluateJavaScript("window.terminalWrite(\(encoded))", completionHandler: nil)
        }

        private func intValue(_ raw: Any?) -> Int {
            if let value = raw as? Int { return value }
            if let number = raw as? NSNumber { return number.intValue }
            return 0
        }
    }
}

func terminalJsStringLiteral(_ text: String) -> String? {
    guard let data = try? JSONSerialization.data(withJSONObject: text, options: [.fragmentsAllowed]),
          let encoded = String(data: data, encoding: .utf8)
    else { return nil }
    return encoded
}

private func terminalIndexURL() -> URL? {
    Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "Web")
        ?? Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "UI/Terminal/Web")
        ?? Bundle.main.url(forResource: "index", withExtension: "html")
}
