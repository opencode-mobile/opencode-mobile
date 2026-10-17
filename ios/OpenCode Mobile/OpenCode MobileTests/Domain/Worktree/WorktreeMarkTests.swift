import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite
struct WorktreeMarkTests {
    private let added = "/Users/ivan/Projects/opencode-mobile"
    private let checkout = "/Users/ivan/.local/share/opencode/worktree/2fb98b/add-project-browse"

    @Test func badgesAListedWorktreeOtherThanTheAddedPath() {
        let inventory = WorktreeInventory.loaded([added, checkout])
        #expect(WorktreeMark.showsBadge(sessionDirectory: checkout, addedPath: added, inventory: inventory))
    }

    @Test func doesNotBadgeTheAddedPathEvenWhenItIsListed() {
        let inventory = WorktreeInventory.loaded([added, checkout])
        #expect(!WorktreeMark.showsBadge(sessionDirectory: added, addedPath: added, inventory: inventory))
        #expect(!WorktreeMark.showsBadge(sessionDirectory: added + "/", addedPath: added, inventory: inventory))
    }

    @Test func doesNotBadgeASubpathUnlessThatDirectoryIsListed() {
        let inventory = WorktreeInventory.loaded([checkout])
        #expect(!WorktreeMark.showsBadge(sessionDirectory: added + "/packages/app", addedPath: added, inventory: inventory))
    }

    @Test func doesNotBadgeUntilTheInventoryHasLoaded() {
        #expect(!WorktreeMark.showsBadge(sessionDirectory: checkout, addedPath: added, inventory: .unloaded))
        #expect(!WorktreeMark.showsBadge(sessionDirectory: checkout, addedPath: added, inventory: .failed))
        #expect(!WorktreeMark.showsBadge(sessionDirectory: checkout, addedPath: added, inventory: .loaded([])))
        #expect(!WorktreeMark.showsBadge(sessionDirectory: nil, addedPath: added, inventory: .loaded([checkout])))
    }

    @Test func checkoutNameIsTheLastPathSegment() {
        #expect(WorktreeMark.checkoutName(checkout) == "add-project-browse")
        #expect(WorktreeMark.checkoutName("/worktrees/crisp-cactus/") == "crisp-cactus")
    }

    @Test func sessionChatNamesTheCheckoutOnlyWhenThePredicateMatches() {
        let inventory = WorktreeInventory.loaded([added, checkout])
        #expect(
            WorktreeMark.checkoutNameIfMarked(boundDirectory: checkout, addedPaths: [added]) { _ in inventory }
                == "add-project-browse"
        )
        #expect(WorktreeMark.checkoutNameIfMarked(boundDirectory: added, addedPaths: [added]) { _ in inventory } == nil)
        #expect(
            WorktreeMark.checkoutNameIfMarked(
                boundDirectory: added + "/packages/app",
                addedPaths: [added]
            ) { _ in inventory } == nil
        )
    }
}

@Suite
struct WorkspaceDirectoryParserTests {
    @Test func parsesAStringArrayAndObjectDirectories() {
        #expect(WorkspaceDirectoryParser.parse(#"["/a","/b"]"#) == ["/a", "/b"])
        let body = #"{"data":[{"directory":"/repo","name":"main"},{"directory":"/worktrees/crisp-cactus","id":"wrk_1"}]}"#
        #expect(WorkspaceDirectoryParser.parse(body) == ["/repo", "/worktrees/crisp-cactus"])
    }

    @Test func unrecognizedShapesFailRatherThanStop() {
        #expect(WorkspaceDirectoryParser.parse(#"{"workspaces":["/repo"]}"#) == nil)
        #expect(WorkspaceDirectoryParser.parse("not-json") == nil)
        #expect(WorkspaceDirectoryParser.parse("[1]") == nil)
    }
}
