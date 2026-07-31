import Foundation
import OpenCodeMarkdownCore

enum OpenCodeRenderedMarkdownParser {
    nonisolated struct ObservedResult: Sendable {
        let document: NativeMarkdownDocument
        let textMateObservations: [NativeTextMateParseObservation]
    }

    /// Must stay nonisolated: the app defaults to MainActor isolation, and history
    /// enrich runs this inside `Task.detached`. If parse hops back to MainActor,
    /// TextMate blocks scrolling for the entire prepare window.
    nonisolated static func parse(
        _ markdown: String,
        variant: OpenCodeRenderedMarkdownVariant = .standalone,
        isStreaming: Bool = false,
        textMateEnabled: Bool = false,
        structureOnly: Bool = false
    ) -> NativeMarkdownDocument {
        do {
            let requestJson = try encodedRequest(
                markdown,
                variant: variant,
                isStreaming: isStreaming,
                textMateEnabled: textMateEnabled,
                structureOnly: structureOnly
            )
            let responseJson = OpenCodeMarkdownJson.shared.parseMarkdownJson(requestJson: requestJson)
            return try JSONDecoder().decode(NativeMarkdownDocument.self, from: Data(responseJson.utf8))
        } catch {
            return fallbackDocument(for: markdown)
        }
    }

    nonisolated static func parseObserved(
        _ markdown: String,
        variant: OpenCodeRenderedMarkdownVariant = .standalone,
        isStreaming: Bool = false,
        textMateEnabled: Bool = false,
        structureOnly: Bool = false
    ) -> ObservedResult {
        let startedAt = DispatchTime.now().uptimeNanoseconds
        do {
            let decoder = JSONDecoder()
            let requestJson = try encodedRequest(
                markdown,
                variant: variant,
                isStreaming: isStreaming,
                textMateEnabled: textMateEnabled,
                structureOnly: structureOnly
            )
            let responseJson = OpenCodeMarkdownJson.shared.parseMarkdownObservedJson(requestJson: requestJson)
            let envelope = try decoder.decode(NativeMarkdownParseEnvelope.self, from: Data(responseJson.utf8))
            return ObservedResult(
                document: envelope.document,
                textMateObservations: envelope.textMateObservations
            )
        } catch {
            return fallbackResult(
                for: markdown,
                textMateEnabled: textMateEnabled,
                isStreaming: isStreaming,
                startedAt: startedAt
            )
        }
    }

    nonisolated private static func encodedRequest(
        _ markdown: String,
        variant: OpenCodeRenderedMarkdownVariant,
        isStreaming: Bool,
        textMateEnabled: Bool,
        structureOnly: Bool
    ) throws -> String {
            let request = NativeMarkdownParseRequest(
                markdown: markdown,
                variant: variant.rawValue,
                isStreaming: isStreaming,
                textMateEnabled: textMateEnabled,
                structureOnly: structureOnly
            )
        let requestData = try JSONEncoder().encode(request)
        guard let requestJson = String(data: requestData, encoding: .utf8) else {
            throw RequestEncodingError.invalidUTF8
        }
        return requestJson
    }

    nonisolated private static func fallbackResult(
        for markdown: String,
        textMateEnabled: Bool,
        isStreaming: Bool,
        startedAt: UInt64
    ) -> ObservedResult {
        let observations: [NativeTextMateParseObservation]
        if textMateEnabled && !isStreaming {
            let elapsed = DispatchTime.now().uptimeNanoseconds &- startedAt
            observations = [
                NativeTextMateParseObservation(
                    schemaVersion: 1,
                    requestedLanguage: nil,
                    canonicalLanguage: nil,
                    outcome: "Failed",
                    messageCharacters: markdown.utf16.count,
                    fenceCharacters: 0,
                    fenceLines: 0,
                    fenceIndex: 0,
                    fenceCount: 0,
                    spanCount: 0,
                    backend: nil,
                    totalNanoseconds: Int64(clamping: elapsed),
                    stages: [],
                    diagnosticKinds: ["SwiftBridgeFallback"]
                )
            ]
        } else {
            observations = []
        }
        return ObservedResult(
            document: fallbackDocument(for: markdown),
            textMateObservations: observations
        )
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

nonisolated private struct NativeMarkdownParseEnvelope: Decodable, Sendable {
    let document: NativeMarkdownDocument
    let textMateObservations: [NativeTextMateParseObservation]
}

nonisolated private struct NativeMarkdownParseRequest: Encodable, Sendable {
    let markdown: String
    let variant: String
    let isStreaming: Bool
    let textMateEnabled: Bool
    let structureOnly: Bool
}
