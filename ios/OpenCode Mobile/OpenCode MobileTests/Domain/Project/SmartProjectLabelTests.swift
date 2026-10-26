import Testing
@testable import OpenCode_Mobile

struct SmartProjectLabelTests {
    @Test
    func uniqueBasenameReturnsNameOnly() {
        let label = smartProjectLabel(
            focusWorktree: "/Users/example/Projects/opencode",
            peerWorktrees: [
                "/Users/example/Projects/opencode",
                "/Users/example/Projects/dotfiles",
                "/Users/example/Documents/notes",
            ]
        )
        #expect(label == "opencode")
    }

    @Test
    func emptyPeersReturnsBasename() {
        #expect(
            smartProjectLabel(
                focusWorktree: "/Users/example/Projects/opencode",
                peerWorktrees: []
            ) == "opencode"
        )
    }

    @Test
    func sameNameDifferentParentUsesOneParent() {
        let peers = [
            "/Users/example/Projects/opencode",
            "/Users/example/Documents/opencode",
        ]
        #expect(
            smartProjectLabel(
                focusWorktree: "/Users/example/Projects/opencode",
                peerWorktrees: peers
            ) == "..Projects/opencode"
        )
        #expect(
            smartProjectLabel(
                focusWorktree: "/Users/example/Documents/opencode",
                peerWorktrees: peers
            ) == "..Documents/opencode"
        )
    }

    @Test
    func sharedImmediateParentWalksUp() {
        let peers = [
            "/Users/example/Work/acme/lib/opencode",
            "/Users/example/Work/beta/lib/opencode",
            "/Users/example/Projects/opencode",
        ]
        #expect(
            smartProjectLabel(
                focusWorktree: "/Users/example/Work/acme/lib/opencode",
                peerWorktrees: peers
            ) == "..acme/lib/opencode"
        )
        #expect(
            smartProjectLabel(
                focusWorktree: "/Users/example/Work/beta/lib/opencode",
                peerWorktrees: peers
            ) == "..beta/lib/opencode"
        )
        #expect(
            smartProjectLabel(
                focusWorktree: "/Users/example/Projects/opencode",
                peerWorktrees: peers
            ) == "..Projects/opencode"
        )
    }

    @Test
    func trailingSlashNormalized() {
        #expect(
            smartProjectLabel(
                focusWorktree: "/Users/example/Projects/opencode/",
                peerWorktrees: ["/Users/example/Documents/opencode/"]
            ) == "..Projects/opencode"
        )
    }
}
