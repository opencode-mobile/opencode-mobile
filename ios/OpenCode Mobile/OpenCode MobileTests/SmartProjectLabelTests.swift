import Testing
@testable import OpenCode_Mobile

struct SmartProjectLabelTests {
    @Test
    func uniqueBasenameReturnsNameOnly() {
        let label = smartProjectLabel(
            focusWorktree: "/Users/ivan/Projects/opencode",
            peerWorktrees: [
                "/Users/ivan/Projects/opencode",
                "/Users/ivan/Projects/dotfiles",
                "/Users/ivan/Documents/notes",
            ]
        )
        #expect(label == "opencode")
    }

    @Test
    func emptyPeersReturnsBasename() {
        #expect(
            smartProjectLabel(
                focusWorktree: "/Users/ivan/Projects/opencode",
                peerWorktrees: []
            ) == "opencode"
        )
    }

    @Test
    func sameNameDifferentParentUsesOneParent() {
        let peers = [
            "/Users/ivan/Projects/opencode",
            "/Users/ivan/Documents/opencode",
        ]
        #expect(
            smartProjectLabel(
                focusWorktree: "/Users/ivan/Projects/opencode",
                peerWorktrees: peers
            ) == "..Projects/opencode"
        )
        #expect(
            smartProjectLabel(
                focusWorktree: "/Users/ivan/Documents/opencode",
                peerWorktrees: peers
            ) == "..Documents/opencode"
        )
    }

    @Test
    func sharedImmediateParentWalksUp() {
        let peers = [
            "/Users/ivan/Work/acme/lib/opencode",
            "/Users/ivan/Work/beta/lib/opencode",
            "/Users/ivan/Projects/opencode",
        ]
        #expect(
            smartProjectLabel(
                focusWorktree: "/Users/ivan/Work/acme/lib/opencode",
                peerWorktrees: peers
            ) == "..acme/lib/opencode"
        )
        #expect(
            smartProjectLabel(
                focusWorktree: "/Users/ivan/Work/beta/lib/opencode",
                peerWorktrees: peers
            ) == "..beta/lib/opencode"
        )
        #expect(
            smartProjectLabel(
                focusWorktree: "/Users/ivan/Projects/opencode",
                peerWorktrees: peers
            ) == "..Projects/opencode"
        )
    }

    @Test
    func trailingSlashNormalized() {
        #expect(
            smartProjectLabel(
                focusWorktree: "/Users/ivan/Projects/opencode/",
                peerWorktrees: ["/Users/ivan/Documents/opencode/"]
            ) == "..Projects/opencode"
        )
    }
}
