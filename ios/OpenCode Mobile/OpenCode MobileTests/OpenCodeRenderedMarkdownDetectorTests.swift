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

    @Test func shouldRenderSimpleInlineMarkdownAsNativeText() {
        #expect(!OpenCodeRenderedMarkdownDetector.shouldRender("Use `inline code` here."))
        #expect(!OpenCodeRenderedMarkdownDetector.shouldRender("Read [the docs](https://example.com)."))
        #expect(!OpenCodeRenderedMarkdownDetector.shouldRender("This is **important**."))
        #expect(!OpenCodeRenderedMarkdownDetector.shouldRender("First paragraph.\n\nSecond paragraph."))
        #expect(!OpenCodeRenderedMarkdownDetector.shouldRender("Plain text stays native."))
    }
}
