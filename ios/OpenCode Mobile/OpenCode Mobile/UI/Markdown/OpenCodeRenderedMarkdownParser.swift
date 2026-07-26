import Foundation
import OpenCodeMarkdownCore

enum OpenCodeRenderedMarkdownParser {
    /// Must stay nonisolated: the app defaults to MainActor isolation, and history
    /// enrich runs this inside `Task.detached`. If parse hops back to MainActor,
    /// TextMate blocks scrolling for the entire prepare window.
    nonisolated static func parse(
        _ markdown: String,
        variant: OpenCodeRenderedMarkdownVariant = .standalone,
        isStreaming: Bool = false
    ) -> NativeMarkdownDocument {
        do {
            let encoder = JSONEncoder()
            let decoder = JSONDecoder()
            let request = NativeMarkdownParseRequest(
                markdown: markdown,
                variant: variant.rawValue,
                isStreaming: isStreaming
            )
            let requestData = try encoder.encode(request)
            guard let requestJson = String(data: requestData, encoding: .utf8) else {
                return fallbackDocument(for: markdown)
            }
            let responseJson = OpenCodeMarkdownJson.shared.parseMarkdownJson(requestJson: requestJson)
            return try decoder.decode(NativeMarkdownDocument.self, from: Data(responseJson.utf8))
        } catch {
            return fallbackDocument(for: markdown)
        }
    }

    nonisolated private static func fallbackDocument(for markdown: String) -> NativeMarkdownDocument {
        NativeMarkdownDocument(
            sourceHash: markdown.hashValue,
            blocks: markdown.isEmpty ? [] : [.paragraph([.text(markdown)])],
            diagnostics: [
                NativeMarkdownDiagnostic(
                    kind: "SwiftBridgeFallback",
                    message: "Rendered Markdown fell back to literal text after the KMP parser bridge failed."
                )
            ]
        )
    }
}

nonisolated private struct NativeMarkdownParseRequest: Encodable, Sendable {
    let markdown: String
    let variant: String
    let isStreaming: Bool
}
