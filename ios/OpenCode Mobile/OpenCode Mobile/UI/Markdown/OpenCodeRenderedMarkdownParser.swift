import Foundation
import OpenCodeMarkdownCore

enum OpenCodeRenderedMarkdownParser {
    private static let encoder = JSONEncoder()
    private static let decoder = JSONDecoder()

    static func parse(
        _ markdown: String,
        variant: OpenCodeRenderedMarkdownVariant = .standalone,
        isStreaming: Bool = false
    ) -> NativeMarkdownDocument {
        do {
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

    private static func fallbackDocument(for markdown: String) -> NativeMarkdownDocument {
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

private struct NativeMarkdownParseRequest: Encodable {
    let markdown: String
    let variant: String
    let isStreaming: Bool
}
