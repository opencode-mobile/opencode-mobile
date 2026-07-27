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

    @Test func httpClientCodeBlockUsesWebAlignedTokenRoles() {
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
        let document = OpenCodeRenderedMarkdownParser.parse(
            """
            ```javascript
            \(snippet)
            ```
            """,
            variant: .standalone
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

    @Test func javaMainClassCodeBlockUsesWebAlignedTokenRoles() {
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
        let document = OpenCodeRenderedMarkdownParser.parse(
            """
            ```java
            \(snippet)
            ```
            """,
            variant: .standalone
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
    var red: CGFloat = 0
    var green: CGFloat = 0
    var blue: CGFloat = 0
    var alpha: CGFloat = 0
    resolved.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
    return String(format: "#%02x%02x%02x", Int(red * 255), Int(green * 255), Int(blue * 255))
}
