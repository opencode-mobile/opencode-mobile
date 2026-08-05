import Testing
@testable import OpenCode_Mobile

struct OpenCodeRenderedMarkdownDetectorTests {
    @Test func shouldRenderComplexBlockMarkdown() {
        #expect(OpenCodeRenderedMarkdownDetector.shouldRender("# Heading"))
        #expect(OpenCodeRenderedMarkdownDetector.shouldRender("- Item"))
        #expect(OpenCodeRenderedMarkdownDetector.shouldRender("1. Item"))
        #expect(OpenCodeRenderedMarkdownDetector.shouldRender("> quoted"))
        #expect(OpenCodeRenderedMarkdownDetector.shouldRender("| A | B |\n|---|---|"))
        #expect(OpenCodeRenderedMarkdownDetector.shouldRender("```swift\nlet answer = 42\n```"))
        #expect(OpenCodeRenderedMarkdownDetector.shouldRender("```diff\n+added\n-deleted\n```"))
    }

    @Test func shouldRenderSimpleInlineMarkdownWithNativeRenderer() {
        #expect(OpenCodeRenderedMarkdownDetector.shouldRender("Use `inline code` here."))
        #expect(OpenCodeRenderedMarkdownDetector.shouldRender("Read [the docs](https://example.com)."))
        #expect(OpenCodeRenderedMarkdownDetector.shouldRender("This is **important**."))
        #expect(OpenCodeRenderedMarkdownDetector.shouldRender("See https://example.com/docs for details."))
        #expect(OpenCodeRenderedMarkdownDetector.shouldRender("Open <https://example.com>."))
        #expect(!OpenCodeRenderedMarkdownDetector.shouldRender("First paragraph.\n\nSecond paragraph."))
        #expect(!OpenCodeRenderedMarkdownDetector.shouldRender("Plain text stays native."))
    }

    @Test func parserConsumesSharedKmpRenderTree() {
        let document = OpenCodeRenderedMarkdownParser.parse(
            """
            ### Title

            | A | B |
            |---|---|
            | 1 | `two` |

            ```diff
            +added
            -deleted
            ```
            """,
            variant: .standalone
        )

        #expect(document.renderTreeVersion == 1)
        #expect(document.blocks.count == 3)

        guard case .heading(let level, let headingInlines) = document.blocks[0] else {
            Issue.record("Expected first block to decode as a heading")
            return
        }
        #expect(level == 3)
        guard case .text("Title") = headingInlines.first else {
            Issue.record("Expected heading inline text from KMP render tree")
            return
        }

        guard case .table(let header, let rows) = document.blocks[1] else {
            Issue.record("Expected second block to decode as a table")
            return
        }
        #expect(header.count == 2)
        #expect(rows.count == 1)

        guard case .diffBlock(let lines) = document.blocks[2] else {
            Issue.record("Expected third block to decode as a diff block")
            return
        }
        #expect(lines.map(\.kind) == [.added, .deleted])
    }

    @Test func parserUsesBundledTextMateGrammarPathOnIOSBridge() {
        let document = OpenCodeRenderedMarkdownParser.parse(
            """
            ```swift
            let answer = 42
            ```
            """,
            variant: .standalone,
            textMateEnabled: true
        )

        #expect(!document.diagnostics.contains { $0.kind == "HighlighterFallback" })

        guard case .codeBlock(_, _, let spans) = document.blocks.first else {
            Issue.record("Expected a code block from KMP render tree")
            return
        }

        #expect(spans.contains { $0.role == .keyword })
        #expect(!spans.isEmpty)
        let usedNativeHighlighter = !document.diagnostics.contains { $0.kind == "HighlighterBackendFallback" }
        let usedBackendFallback = document.diagnostics.contains { $0.kind == "HighlighterBackendFallback" }
        #expect(usedNativeHighlighter || usedBackendFallback)
    }
}
