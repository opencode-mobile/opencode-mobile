import Testing
@testable import OpenCode_Mobile

struct QueuedPromptsDockLabelTests {
    @Test func format_multipleItemsUsesCountAndFirstLine() {
        #expect(formatQueuedPromptsDockLabel(count: 3, preview: "first line") == "3 queued · first line")
    }

    @Test func format_collapsesNewlinesToSpaces() {
        #expect(
            formatQueuedPromptsDockLabel(count: 2, preview: "first line\nsecond line")
                == "2 queued · first line second line"
        )
    }

    @Test func format_singleItem() {
        #expect(
            formatQueuedPromptsDockLabel(count: 1, preview: "also check the compact path")
                == "1 queued · also check the compact path"
        )
    }

    @Test func format_noPreviewUsesCountOnly() {
        #expect(formatQueuedPromptsDockLabel(count: 2, preview: nil) == "2 queued")
        #expect(formatQueuedPromptsDockLabel(count: 1, preview: "   \n  ") == "1 queued")
    }
}
