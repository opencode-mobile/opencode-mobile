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
