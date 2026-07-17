import Testing
@testable import OpenCode_Mobile

struct RenderedMarkdownBlockIdentityTests {
    @Test func identitiesStayStableWhenLastBlockGrows() {
        let before = document(blocks: [
            .paragraph([.text("Hello")]),
            .paragraph([.text("World")]),
        ])
        let after = document(blocks: [
            .paragraph([.text("Hello")]),
            .paragraph([.text("World! More text streams in")]),
        ])

        #expect(identities(of: before) == identities(of: after))
    }

    @Test func identitiesStayStableWhenBlockIsAppended() {
        let before = document(blocks: [
            .paragraph([.text("Hello")]),
        ])
        let after = document(blocks: [
            .paragraph([.text("Hello")]),
            .codeBlock(language: "swift", code: "let x = 1", spans: []),
        ])

        let beforeIDs = identities(of: before)
        let afterIDs = identities(of: after)
        #expect(Array(afterIDs.prefix(beforeIDs.count)) == beforeIDs)
        #expect(Set(afterIDs).count == afterIDs.count)
    }

    @Test func identitiesAreUniqueForRepeatedBlocks() {
        let doc = document(blocks: [
            .paragraph([.text("Same")]),
            .paragraph([.text("Same")]),
        ])

        let ids = identities(of: doc)
        #expect(Set(ids).count == ids.count)
    }

    private func document(blocks: [NativeMarkdownBlock]) -> NativeMarkdownDocument {
        NativeMarkdownDocument(sourceHash: 1, blocks: blocks)
    }

    private func identities(of document: NativeMarkdownDocument) -> [String] {
        document.blocksWithStableIdentities.map(\.identity)
    }
}
