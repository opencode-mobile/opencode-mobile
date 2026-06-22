import Foundation
import Testing
@testable import OpenCode_Mobile

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

    @Test func mapperReusesAnExistingFinalDocumentWithoutComparingRuntimeHashes() async {
        let document = NativeMarkdownDocument(sourceHash: 17, blocks: [.paragraph([.text("Heading")])])
        let row = SessionChatRow(
            id: "message-1",
            role: .assistant,
            text: "# Heading",
            metadataLabel: "",
            isPending: false,
            isStreaming: false,
            renderedMarkdown: document,
            modelProviderID: nil,
            modelID: nil,
            contextInputTokens: nil
        )

        let enriched = await RenderedMarkdownMapper.shared.enrichRows([row], previousByID: [:])

        #expect(enriched.first?.renderedMarkdown?.sourceHash == 17)
    }
}
