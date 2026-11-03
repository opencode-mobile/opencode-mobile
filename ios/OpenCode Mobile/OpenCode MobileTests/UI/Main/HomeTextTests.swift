import Foundation
import Testing
@testable import OpenCode_Mobile

/// Home, Add project and New worktree text come from the String Catalog; server content
/// (project, branch and folder names) stays verbatim.
struct HomeTextTests {
    @Test func countsUseTheCatalogPlurals() {
        #expect(HomeProjectCopy.worktreeCount(1) == "1 worktree")
        #expect(HomeProjectCopy.worktreeCount(3) == "3 worktrees")
        #expect(HomeProjectCopy.allWorktrees(3) == "All 3 worktrees")
        #expect(HomeProjectCopy.running(2) == "2 running")
        #expect(HomeProjectCopy.showAll(7) == "Show all 7")
        #expect(AddProjectFolderItem.countLabel(0).resolved == "Empty")
        #expect(AddProjectFolderItem.countLabel(1).resolved == "1 folder")
        #expect(AddProjectFolderItem.countLabel(4).resolved == "4 folders")
    }

    @Test func allScopeIsKeyed() {
        let projects = ["/src/a", "/src/b"].map { path in
            HomeProject(
                key: path,
                projectID: nil,
                displayName: ServerPathPolicy.name(path),
                memberPaths: [path],
                isRepository: false,
                worktrees: [HomeWorktree(directory: path, branch: nil, isMain: true)]
            )
        }
        let all = SessionListHomeChrome.filterTabs(projects: projects, sessions: []).first
        #expect(all?.title == "All")
        #expect(all?.pickerTitle == "All projects")
        #expect(all?.subtitle == "2 projects")
    }

    @Test func namesStayArgumentsAndAreNeverLookedUp() {
        let item = AddProjectFolderItem(name: "app", path: "/srv/app", isAdded: true, folderCount: 2)
        #expect(item.subtitle?.resolved == "On Home \u{00B7} 2 folders")
        #expect(HomeProjectCopy.joinedExisting("home.run_in") == "Same repo as home.run_in. Showing it as a worktree there.")
        #expect(
            OpenCodeText(WorktreeCreateCopy.failed("worktree.name")).resolved
                == "Couldn't create the worktree: worktree.name"
        )
        #expect(OpenCodeText(WorktreeCreateCopy.currentBranch("main")).resolved == "main (current)")
        #expect(OpenCodeText(WorktreeCreateCopy.nameLong(64)).resolved == "Use at most 64 characters")
    }

    @Test func serversChromeFallsBackToSessions() {
        #expect(HomeServersChrome().title.resolved == "Sessions")
        #expect(HomeServersChrome(title: .verbatim("home.sessions_title")).title.resolved == "home.sessions_title")
    }
}
