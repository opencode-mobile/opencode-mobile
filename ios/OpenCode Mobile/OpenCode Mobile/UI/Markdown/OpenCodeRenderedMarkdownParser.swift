import Foundation
import OpenCodeMarkdownCore

enum OpenCodeRenderedMarkdownParser {
    /// Parses `markdown` through the shared KMP parser. Code fences are colored by highlight-core
    /// (one-shot, per-fence deadline) unless `structureOnly` is true, which keeps block structure
    /// with empty code spans so the async engine attach can color them later.
    ///
    /// Must stay nonisolated: the app defaults to MainActor isolation, and history
    /// enrich runs this inside `Task.detached`. If parse hops back to MainActor,
    /// highlighting blocks scrolling for the entire prepare window.
    nonisolated static func parse(
        _ markdown: String,
        variant: OpenCodeRenderedMarkdownVariant = .standalone,
        structureOnly: Bool = false
    ) -> NativeMarkdownDocument {
        do {
            let request = NativeMarkdownParseRequest(
                markdown: markdown,
                variant: variant.rawValue,
                structureOnly: structureOnly
            )
            let requestData = try JSONEncoder().encode(request)
            guard let requestJson = String(data: requestData, encoding: .utf8) else {
                throw RequestEncodingError.invalidUTF8
            }
            let responseJson = OpenCodeMarkdownJson.shared.parseMarkdownJson(requestJson: requestJson)
            return try JSONDecoder().decode(NativeMarkdownDocument.self, from: Data(responseJson.utf8))
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

nonisolated private enum RequestEncodingError: Error {
    case invalidUTF8
}

nonisolated private struct NativeMarkdownParseRequest: Encodable, Sendable {
    let markdown: String
    let variant: String
    let structureOnly: Bool
}
