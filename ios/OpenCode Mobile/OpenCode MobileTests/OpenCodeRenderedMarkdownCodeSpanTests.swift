import Foundation
import SwiftUI
import Testing
import UIKit
@testable import OpenCode_Mobile

@MainActor
struct OpenCodeRenderedMarkdownCodeSpanTests {
    @Test func overlappingHttpClientSpansRenderFullSourceText() {
        let code = "HttpClient"
        let spans = [
            NativeCodeSpan(start: 3, end: 4, role: .property),
            NativeCodeSpan(start: 5, end: 6, role: .property),
            NativeCodeSpan(start: 0, end: 10, role: .type),
        ]

        let output = NativeMarkdownAttributedString.code(code, spans: spans)

        #expect(String(output.characters) == code)
        #expect(output.characters.count == code.count)
    }

    @Test func codeSpansUseKotlinUtf16Offsets() {
        let code = "😀let answer = 42"
        // Kotlin reports the start of "let" after the emoji as UTF-16 offset 2.
        let output = NativeMarkdownAttributedString.code(
            code,
            spans: [NativeCodeSpan(start: 2, end: 5, role: .keyword)]
        )

        #expect(String(output.characters) == code)
    }

    @Test func attributedCodeCacheIncludesPaletteRevision() {
        let code = "let"
        let spans = [NativeCodeSpan(start: 0, end: 3, role: .keyword)]
        let firstPalette = NativeCodeColorPalette(
            colors: [.keyword: .red],
            plain: .black,
            cacheRevision: 101
        )
        let secondPalette = NativeCodeColorPalette(
            colors: [.keyword: .blue],
            plain: .black,
            cacheRevision: 102
        )

        let first = NativeMarkdownAttributedString.code(code, spans: spans, palette: firstPalette)
        let second = NativeMarkdownAttributedString.code(code, spans: spans, palette: secondPalette)
        guard let firstColor = NSAttributedString(first).attribute(
            .foregroundColor,
            at: 0,
            effectiveRange: nil
        ) as? UIColor,
            let secondColor = NSAttributedString(second).attribute(
                .foregroundColor,
                at: 0,
                effectiveRange: nil
            ) as? UIColor
        else {
            Issue.record("Expected attributed code colors")
            return
        }
        #expect(hex(firstColor) == "#ff0000")
        #expect(hex(secondColor) == "#0000ff")
    }

    @Test func parallelTextMateFirstUsePreservesEveryFenceSource() async {
        let fixtures = [
            ("swift", "struct PhaseOne { let value = 1 }"),
            ("kotlin", "data class PhaseOne(val value: Int)"),
            ("typescript", "const phaseOne: number = 1"),
            ("python", "def phase_one():\n    return 1"),
        ]

        let documents = await withTaskGroup(of: (Int, NativeMarkdownDocument).self) { group in
            for index in 0..<16 {
                group.addTask {
                    let fixture = fixtures[index % fixtures.count]
                    let markdown = "```\(fixture.0)\n\(fixture.1)\n```"
                    let document = await highlightedDocument(markdown, ownerKey: "parallel-\(index)")
                    return (index, document)
                }
            }

            var collected: [(Int, NativeMarkdownDocument)] = []
            for await document in group {
                collected.append(document)
            }
            return collected.sorted { $0.0 < $1.0 }.map(\.1)
        }

        #expect(documents.count == 16)
        for (index, document) in documents.enumerated() {
            let expected = fixtures[index % fixtures.count].1
            let code =
                document.blocks.compactMap { block -> String? in
                    guard case .codeBlock(_, let code, _) = block else { return nil }
                    return code
                }.first
            #expect(code == expected)
            #expect(!document.diagnostics.contains { $0.kind == "SwiftBridgeFallback" })
        }
    }

    @Test func preparerReusesAnExistingFinalDocumentWithoutComparingRuntimeHashes() async {
        let document = NativeMarkdownDocument(sourceHash: 17, blocks: [.paragraph([.text("Heading")])])
        let previous =
            DisplayMessage(
                message: Message(
                    id: "message-1",
                    role: .assistant,
                    text: "# Heading",
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
                renderedMarkdown: document
            )
        let prepared = await MessageContentPreparer.shared.prepareMessage(
            Message(
                id: "message-1",
                role: .assistant,
                text: "# Heading",
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
            previous: previous
        )

        #expect(prepared.renderedMarkdown?.sourceHash == 17)
    }

    @Test func preparerDoesNotReuseFinalDocumentWhenTextChanges() async {
        let document = NativeMarkdownDocument(sourceHash: 17, blocks: [.paragraph([.text("Old")])])
        let previous =
            DisplayMessage(
                message: Message(
                    id: "message-1",
                    role: .assistant,
                    text: "# Old",
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
                renderedMarkdown: document
            )

        let prepared = await MessageContentPreparer.shared.prepareMessage(
            Message(
                id: "message-1",
                role: .assistant,
                text: "# New",
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
            previous: previous
        )

        #expect(prepared.renderedMarkdown?.sourceHash != 17)
        guard case .heading(_, let inlines)? = prepared.renderedMarkdown?.blocks.first else {
            Issue.record("Expected changed markdown to be parsed as a heading")
            return
        }
        guard case .text(let headingText)? = inlines.first else {
            Issue.record("Expected changed heading text")
            return
        }
        #expect(headingText == "New")
    }

    @Test func preparerReusesStreamingDocumentAsFinalPlaceholder() async {
        let document = NativeMarkdownDocument(sourceHash: 99, blocks: [.paragraph([.text("Partial")])])
        let previous =
            DisplayMessage(
                message: Message(
                    id: "message-1",
                    role: .assistant,
                    text: "# Partial",
                    parts: [.text(TextPart(text: "# Partial", isStreaming: true))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
                renderedMarkdown: document
            )
        let prepared = await MessageContentPreparer.shared.prepareMessage(
            Message(
                id: "message-1",
                role: .assistant,
                text: "# Partial",
                parts: [.text(TextPart(text: "# Partial", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
            previous: previous,
            mode: .finalPlaceholder
        )

        #expect(prepared.renderedMarkdown?.sourceHash == 99)
    }

    @Test func preparerFinalAfterPlaceholderUpgradesStreamingDocument() async {
        let preferences = MarkdownHighlightPreferences.shared
        let priorTextMateEnabled = preferences.textMateEnabled
        preferences.setTextMateEnabled(true)
        MessageContentPreparer.shared.clearDocumentCache()
        defer {
            preferences.setTextMateEnabled(priorTextMateEnabled)
            MessageContentPreparer.shared.clearDocumentCache()
        }

        let markdown = "```kotlin\nval answer = 42\n```"
        let streamingMessage = Message(
            id: "message-1",
            role: .assistant,
            text: markdown,
            parts: [.text(TextPart(text: markdown, isStreaming: true))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        let finalMessage = Message(
            id: "message-1",
            role: .assistant,
            text: markdown,
            parts: [.text(TextPart(text: markdown, isStreaming: false))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )

        let streaming = await MessageContentPreparer.shared.prepareMessage(
            streamingMessage,
            mode: .streaming
        )
        #expect(streaming.renderedMarkdown != nil)
        #expect(streaming.awaitingTextMateUpgrade == false)

        let placeholder = await MessageContentPreparer.shared.prepareMessage(
            finalMessage,
            previous: streaming,
            mode: .finalPlaceholder
        )
        #expect(placeholder.awaitingTextMateUpgrade)
        #expect(placeholder.renderedMarkdown?.sourceHash == streaming.renderedMarkdown?.sourceHash)

        let finalized = await MessageContentPreparer.shared.prepareMessage(
            finalMessage,
            previous: placeholder,
            mode: .final
        )
        #expect(finalized.renderedMarkdown != nil)
        #expect(finalized.awaitingTextMateUpgrade == false)
        // Streaming uses semantic fallback; Final must clear that diagnostic via TextMate.
        #expect(
            streaming.renderedMarkdown?.diagnostics.contains { $0.kind == "HighlighterFallback" } == true
        )
        #expect(
            finalized.renderedMarkdown?.diagnostics.contains { $0.kind == "HighlighterFallback" } != true
        )
    }

    @Test func httpClientCodeBlockUsesWebAlignedTokenRoles() async {
        let snippet = """
        // HTTP Client with interceptors, retry logic
        class HttpClient {
          #baseUrl;
          #headers;
          #interceptors;
          #timeout;

          constructor(options = {}) {
            this.#baseUrl = options.baseUrl || "";
            this.#headers = new Map(Object.entries(options.headers || {}));
            this.#interceptors = { request: [], response: [] };
            this.#timeout = options.timeout || 30000;
          }
        }
        """
        let document = await highlightedDocument(
            """
            ```javascript
            \(snippet)
            ```
            """,
            ownerKey: "http-client"
        )

        guard case .codeBlock(_, let code, let spans) = document.blocks.first else {
            Issue.record("Expected HttpClient fixture to decode as a code block")
            return
        }

        #expect(role(for: "constructor", in: code, spans: spans) == .keyword)
        #expect(role(for: "new", in: code, spans: spans) == .keyword)
        #expect(role(for: "options", in: code, spans: spans) == .variable)
        #expect(role(for: "this", in: code, spans: spans) == .property)
        #expect(role(for: "Map", in: code, spans: spans) == .function)
        #expect(role(for: "30000", in: code, spans: spans) == .number)
        #expect(role(for: "\"\"", in: code, spans: spans) == .string)
        #expect(role(for: "request", in: code, spans: spans) == .property)
        #expect(role(for: "#baseUrl", in: code, spans: spans, occurrence: 0) == nil)
        #expect(role(for: "#baseUrl", in: code, spans: spans, occurrence: 1) == .property)
    }

    @Test func javaMainClassCodeBlockUsesWebAlignedTokenRoles() async {
        let snippet = """
        public class Main {
            public static void main(String[] args) {
                // Create an array
                int[] numbers = {1, 2, 3, 4, 5};

                // Loop through array
                for (int i = 0; i < numbers.length; i++) {
                    System.out.println(numbers[i]);
                }

                // Try-catch block
                try {
                    int result = 10 / 0;
                } catch (ArithmeticException e) {
                    System.err.println("Division by zero!");
                }
            }
        }
        """
        let document = await highlightedDocument(
            """
            ```java
            \(snippet)
            ```
            """,
            ownerKey: "java-main"
        )

        guard case .codeBlock(_, let code, let spans) = document.blocks.first else {
            Issue.record("Expected Java Main fixture to decode as a code block")
            return
        }

        #expect(role(for: "public", in: code, spans: spans) == .keyword)
        #expect(role(for: "Main", in: code, spans: spans) == .type)
        #expect(role(for: "println", in: code, spans: spans) == .function)
        #expect(role(for: "1", in: code, spans: spans) == .number)
        #expect(
            spans.contains {
                $0.role == .string && utf16Text(in: code, start: $0.start, end: $0.end).contains("Division by zero")
            }
        )
    }

    @Test func streamingNeverForcePlainUnderFormatAlways() {
        #expect(
            OpenCodeRenderedMarkdown.forcePlainStreamingCode(isStreaming: true, codeLength: 500)
                == false
        )
        #expect(
            OpenCodeRenderedMarkdown.forcePlainStreamingCode(isStreaming: false, codeLength: 5_000)
                == false
        )
        #expect(
            OpenCodeRenderedMarkdown.forcePlainStreamingCode(isStreaming: true, codeLength: 50_000)
                == false
        )
    }

    @Test func codeSpanColorsUseWebAlignedSyntaxTokens() {
        let darkTraits = UITraitCollection(userInterfaceStyle: .dark)
        let cases: [(NativeCodeTokenRole, String)] = [
            (.plain, "text-strong"),
            (.variable, "syntax-variable"),
            (.keyword, "syntax-keyword"),
            (.type, "syntax-type"),
            (.function, "syntax-primitive"),
            (.property, "syntax-constant"),
            (.constant, "syntax-constant"),
            (.number, "syntax-property"),
            (.string, "syntax-string"),
            (.operatorToken, "syntax-operator"),
            (.punctuation, "syntax-punctuation"),
        ]

        for (role, token) in cases {
            let output = NativeMarkdownAttributedString.code(
                "x",
                spans: [NativeCodeSpan(start: 0, end: 1, role: role)]
            )
            guard let rendered = output.runs.first?.foregroundColor else {
                Issue.record("Missing foreground color for role \(role)")
                continue
            }
            let expectedHex = ThemeManager.shared.resolvedDark[token].lowercased()
            let renderedHex = hex(rendered, traits: darkTraits).lowercased()
            #expect(renderedHex == expectedHex, "Expected role \(role) to resolve token \(token)")
        }
    }

    private func highlightedDocument(
        _ markdown: String,
        ownerKey: String
    ) async -> NativeMarkdownDocument {
        let fallback = OpenCodeRenderedMarkdownParser.parse(markdown, textMateEnabled: false)
        let fences = fallback.textMateFences(
            ownerKey: ownerKey,
            revision: TextMateHighlightRevision(value: 1),
            policyEpoch: 1
        )
        var document = fallback
        for fence in fences {
            guard let result = try? await KMPTextMateHighlightingEngine.shared.highlight(
                fence.request(
                    intent: .interactiveFinal,
                    messageCharacters: markdown.utf16.count,
                    fenceCount: fences.count
                )
            ) else {
                continue
            }
            if case .attached(let attached) = document.attachingTextMate(fence: fence, result: result) {
                document = attached
            }
        }
        return document
    }
}

private func role(
    for text: String,
    in code: String,
    spans: [NativeCodeSpan],
    occurrence: Int = 0
) -> NativeCodeTokenRole? {
    guard let range = nthRange(of: text, in: code, occurrence: occurrence) else { return nil }
    guard let utf16Lower = range.lowerBound.samePosition(in: code.utf16),
          let utf16Upper = range.upperBound.samePosition(in: code.utf16) else { return nil }
    let start = code.utf16.distance(from: code.utf16.startIndex, to: utf16Lower)
    let end = code.utf16.distance(from: code.utf16.startIndex, to: utf16Upper)
    if let exact = spans.first(where: { $0.start == start && $0.end == end }) {
        return exact.role
    }
    return spans.first(where: { $0.start <= start && $0.end >= end })?.role
}

private func nthRange(of text: String, in code: String, occurrence: Int) -> Range<String.Index>? {
    var found = 0
    var searchStart = code.startIndex
    while searchStart < code.endIndex,
          let range = code.range(of: text, range: searchStart..<code.endIndex) {
        if found == occurrence { return range }
        found += 1
        searchStart = range.upperBound
    }
    return nil
}

private func utf16Text(in code: String, start: Int, end: Int) -> String {
    let lowerOffset = min(max(start, 0), code.utf16.count)
    let upperOffset = min(max(end, lowerOffset), code.utf16.count)
    let lower = code.utf16.index(code.utf16.startIndex, offsetBy: lowerOffset)
    let upper = code.utf16.index(code.utf16.startIndex, offsetBy: upperOffset)
    guard let stringLower = String.Index(lower, within: code),
          let stringUpper = String.Index(upper, within: code) else {
        return ""
    }
    return String(code[stringLower..<stringUpper])
}

private func hex(_ color: Color, traits: UITraitCollection) -> String {
    let resolved = UIColor(color).resolvedColor(with: traits)
    return hex(resolved)
}

private func hex(_ color: UIColor) -> String {
    let resolved = color.resolvedColor(with: UITraitCollection(userInterfaceStyle: .light))
    var red: CGFloat = 0
    var green: CGFloat = 0
    var blue: CGFloat = 0
    var alpha: CGFloat = 0
    resolved.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
    return String(format: "#%02x%02x%02x", Int(red * 255), Int(green * 255), Int(blue * 255))
}
