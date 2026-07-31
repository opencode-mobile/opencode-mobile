import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite
struct BlockDecorationCacheTests {
    @Test func putAndGetRoundTrip() {
        let cache = BlockDecorationCache(maxEntries: 4)
        let revision = ContentRevision.of("# Hello")
        let document = NativeMarkdownDocument(
            sourceHash: 1,
            blocks: [.paragraph([.text("Hello")])]
        )
        cache.put(
            sessionKey: "ses-1",
            blockID: "p:t1",
            contentRevision: revision,
            isStreaming: true,
            document: document
        )
        let hit = cache.get(
            sessionKey: "ses-1",
            blockID: "p:t1",
            contentRevision: revision,
            isStreaming: true
        )
        #expect(hit?.sourceHash == 1)
        #expect(
            cache.get(
                sessionKey: "ses-1",
                blockID: "p:t1",
                contentRevision: revision,
                isStreaming: false
            ) == nil
        )
    }

    @Test func evictsLeastRecentlyUsed() {
        let cache = BlockDecorationCache(maxEntries: 2)
        let docs = (1...3).map { index in
            NativeMarkdownDocument(
                sourceHash: index,
                blocks: [.paragraph([.text("\(index)")])]
            )
        }
        for index in 0..<3 {
            cache.put(
                sessionKey: "ses",
                blockID: "b-\(index)",
                contentRevision: ContentRevision.of("t\(index)"),
                isStreaming: false,
                document: docs[index]
            )
        }
        #expect(cache.count == 2)
        #expect(
            cache.get(
                sessionKey: "ses",
                blockID: "b-0",
                contentRevision: ContentRevision.of("t0"),
                isStreaming: false
            ) == nil
        )
        #expect(
            cache.get(
                sessionKey: "ses",
                blockID: "b-2",
                contentRevision: ContentRevision.of("t2"),
                isStreaming: false
            )?.sourceHash == 3
        )
    }

    @Test func clearRemovesAllEntries() {
        let cache = BlockDecorationCache(maxEntries: 8)
        cache.put(
            sessionKey: "ses",
            blockID: "b-1",
            contentRevision: ContentRevision.of("x"),
            isStreaming: false,
            document: NativeMarkdownDocument(sourceHash: 9, blocks: [])
        )
        cache.clear()
        #expect(cache.count == 0)
    }
}

@Suite
struct StreamingTextBlockIdentityTests {
    @Test func interleavedToolSplitsRunsAndTargetsLastStreamingText() {
        let message = Message(
            id: "msg-1",
            role: .assistant,
            text: "intro\n```js\nconst x = 1\n```",
            parts: [
                .text(TextPart(id: "prt-1", text: "intro", isStreaming: false)),
                .tool(ToolPart(id: "prt-tool", toolName: "read", status: .completed)),
                .text(TextPart(id: "prt-2", text: "```js\nconst x = 1\n```", isStreaming: true)),
            ],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        let target = StreamingTextBlockIdentity.resolveStreaming(message: message)
        #expect(target?.blockID == PresentationBlockId.mergedText(["prt-2"]))
        #expect(target?.plain == "```js\nconst x = 1\n```")
    }

    @Test func invisibleTodoWriteDoesNotSplitMergedStreamingText() {
        let message = Message(
            id: "msg-1",
            role: .assistant,
            text: "one\ntwo",
            parts: [
                .text(TextPart(id: "prt-1", text: "one", isStreaming: false)),
                .tool(ToolPart(id: "prt-todo", toolName: "todowrite", status: .completed)),
                .text(TextPart(id: "prt-2", text: "two", isStreaming: true)),
            ],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        let target = StreamingTextBlockIdentity.resolveStreaming(message: message)
        #expect(target?.blockID == PresentationBlockId.mergedText(["prt-1", "prt-2"]))
        #expect(target?.plain == "one\ntwo")
    }

    @Test func missingPartIdsFallBackToMessageScopedBlock() {
        let message = Message(
            id: "msg-1",
            role: .assistant,
            text: "hello",
            parts: [
                .text(TextPart(id: nil, text: "hello", isStreaming: true)),
            ],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        let target = StreamingTextBlockIdentity.resolveStreaming(message: message)
        #expect(target?.blockID == PresentationBlockId.messageFallback("msg-1"))
        #expect(target?.plain == "hello")
    }
}
