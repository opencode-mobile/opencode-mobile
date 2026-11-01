import Foundation
import Testing
@testable import OpenCode_Mobile

struct HomeCompactRowTests {
    // MARK: Line 2 split

    @Test func bothNamesFitKeepTheirFullWidth() {
        #expect(OpenCodeListItemPathSplit.primaryWidth(primary: 80, secondary: 100, available: 200) == 80)
        #expect(OpenCodeListItemPathSplit.primaryWidth(primary: 120, secondary: 80, available: 200) == 120)
    }

    @Test func shortProjectKeepsItsWidthWhenItFitsInFortyPercent() {
        // 70 + 300 > 200; 70 <= 80.
        #expect(OpenCodeListItemPathSplit.primaryWidth(primary: 70, secondary: 300, available: 200) == 70)
        #expect(OpenCodeListItemPathSplit.primaryWidth(primary: 80, secondary: 300, available: 200) == 80)
    }

    @Test func shortWorktreeKeepsItsWidthWhenItFitsInSixtyPercent() {
        // Project 150 > 80, worktree 100 <= 120: the project gets the rest.
        #expect(OpenCodeListItemPathSplit.primaryWidth(primary: 150, secondary: 100, available: 200) == 100)
        #expect(OpenCodeListItemPathSplit.primaryWidth(primary: 300, secondary: 120, available: 200) == 80)
    }

    @Test func bothLongSplitFortySixty() {
        #expect(OpenCodeListItemPathSplit.primaryWidth(primary: 300, secondary: 300, available: 200) == 80)
        #expect(OpenCodeListItemPathSplit.primaryWidth(primary: 81, secondary: 121, available: 200) == 80)
    }

    @Test func noRoomGivesNoWidth() {
        #expect(OpenCodeListItemPathSplit.primaryWidth(primary: 50, secondary: 50, available: 0) == 0)
    }

    // MARK: Branch shortening

    @Test func shortBranchDropsOneKnownPrefix() {
        #expect(HomeCompactRowPath.shortBranch("feature/803-compact-home-rows") == "803-compact-home-rows")
        #expect(HomeCompactRowPath.shortBranch("fix/783-header") == "783-header")
        #expect(HomeCompactRowPath.shortBranch("chore/bump-version-1.2.0") == "bump-version-1.2.0")
        #expect(HomeCompactRowPath.shortBranch("opencode/brave-otter") == "brave-otter")
        #expect(HomeCompactRowPath.shortBranch("feature/fix/nested") == "fix/nested")
    }

    @Test func shortBranchKeepsOtherNames() {
        #expect(HomeCompactRowPath.shortBranch("main") == "main")
        #expect(HomeCompactRowPath.shortBranch("release/1.2") == "release/1.2")
        #expect(HomeCompactRowPath.shortBranch("features/x") == "features/x")
        #expect(HomeCompactRowPath.shortBranch("feature/") == "feature/")
    }

    // MARK: Worktree visibility

    @Test func mainCheckoutHasNoWorktreePart() {
        #expect(label(directory: "/repo") == nil)
        #expect(label(directory: nil) == nil)
        // Unknown branch on the main checkout still shows nothing.
        let unknownBranch = repo(worktrees: [HomeWorktree(directory: "/repo", branch: nil, isMain: true)])
        #expect(label(directory: "/repo", project: unknownBranch) == nil)
    }

    @Test func otherWorktreeShowsItsShortenedBranch() {
        #expect(label(directory: "/repo-wt") == "803-compact-home-rows")
        #expect(label(directory: "/repo-wt/") == "803-compact-home-rows")
    }

    @Test func detachedWorktreeShowsItsFolder() {
        #expect(label(directory: "/wt/brave-otter") == "brave-otter")
    }

    @Test func unlistedDirectoryShowsItsFolder() {
        #expect(label(directory: "/elsewhere/scratch") == "scratch")
    }

    @Test func nonGitProjectHasNoWorktreePart() {
        let notes = HomeProject(
            key: "path:/notes",
            projectID: nil,
            displayName: "notes",
            memberPaths: ["/notes"],
            isRepository: false,
            worktrees: [HomeWorktree(directory: "/notes", branch: nil, isMain: true)]
        )
        #expect(label(directory: "/notes", project: notes) == nil)
    }

    @Test func worktreesOffHidesTheWorktreePart() {
        #expect(label(directory: "/repo-wt", worktreesEnabled: false) == nil)
        #expect(label(directory: "/wt/brave-otter", worktreesEnabled: false) == nil)
    }

    @Test func homeRowsCarryTheWorktreeLabel() {
        let resolution = HomeProjectResolution(id: "prj", canonical: "/repo", isRepository: true)
        let sections = [
            section(
                worktree: "/repo",
                session(id: "a", directory: "/repo", updatedAtEpochMs: 30),
                session(id: "b", directory: "/repo-wt", updatedAtEpochMs: 20)
            ),
        ]
        let projects = HomeProjectGrouping.group(
            addedPaths: ["/repo"],
            resolutions: ["/repo": resolution],
            inventories: ["/repo": ["/repo", "/repo-wt"]],
            branches: ["/repo": "main", "/repo-wt": "feature/b"]
        )

        let on = sections.crossProjectSessionListSessions(projects: projects)
        #expect(on.map(\.worktreeLabel) == [nil, "b"])
        #expect(on.map(\.compactRow.path) == [
            OpenCodeListItemPath(primary: "repo"),
            OpenCodeListItemPath(primary: "repo", secondary: "b"),
        ])

        let off = sections.crossProjectSessionListSessions(projects: projects, worktreesEnabled: false)
        #expect(off.map(\.worktreeLabel) == [nil, nil])
        #expect(off.map(\.compactRow.path.primary) == ["repo", "repo"])
    }

    @Test func nonGitHomeRowShowsTheProjectNameOnly() {
        let sessions = [
            section(worktree: "/Users/demo/notes", session(id: "n", directory: nil, updatedAtEpochMs: 10)),
        ].crossProjectSessionListSessions()
        #expect(sessions.first?.worktreeLabel == nil)
        #expect(sessions.first?.compactRow.path == OpenCodeListItemPath(primary: "notes"))
    }

    // MARK: Status

    @Test func idleRowHasNoPillNoDotAndNoEmphasis() {
        let row = compactRow(.idle)
        #expect(row.status == nil)
        #expect(!row.pulsing)
        #expect(!row.emphasized)
    }

    @Test func activeRowsKeepTodaysLabelsAndTones() {
        #expect(compactRow(.running).status == OpenCodeListItemStatus(text: "Running", tone: .interactive))
        // An interpolated label is a localised format with an argument; compare what is shown.
        #expect(compactRow(.retrying(attempt: 2)).status?.text.resolved == "Retrying \u{00B7} 2")
        #expect(compactRow(.retrying(attempt: 2)).status?.tone == .warning)
        #expect(compactRow(.ready).status == OpenCodeListItemStatus(text: "Ready", tone: .success))
        #expect(compactRow(.done).status == OpenCodeListItemStatus(text: "Done", tone: .success))
        #expect(compactRow(.failed).status == OpenCodeListItemStatus(text: "Failed", tone: .error))
        #expect(compactRow(.stopped).status == OpenCodeListItemStatus(text: "Stopped", tone: .warning))
    }

    @Test func onlyLiveRunningPulses() {
        #expect(compactRow(.running).pulsing)
        #expect(!compactRow(.running, isStale: true).pulsing)
        #expect(compactRow(.running, isStale: true).status?.tone == .stale)
        #expect(!compactRow(.retrying(attempt: 1)).pulsing)
        #expect(!compactRow(.done).pulsing)
    }

    @Test func unreadFinishesEmphasizeTheTitle() {
        #expect(compactRow(.ready).emphasized)
        #expect(compactRow(.done).emphasized)
        #expect(compactRow(.failed).emphasized)
        #expect(compactRow(.stopped).emphasized)
        #expect(!compactRow(.running).emphasized)
        #expect(!compactRow(.retrying(attempt: nil)).emphasized)
    }

    // MARK: Helpers

    private func repo(
        worktrees: [HomeWorktree] = [
            HomeWorktree(directory: "/repo", branch: "main", isMain: true),
            HomeWorktree(directory: "/repo-wt", branch: "feature/803-compact-home-rows", isMain: false),
            HomeWorktree(directory: "/wt/brave-otter", branch: nil, isMain: false),
        ]
    ) -> HomeProject {
        HomeProject(
            key: "prj",
            projectID: "prj",
            displayName: "opencode-mobile",
            memberPaths: ["/repo"],
            isRepository: true,
            worktrees: worktrees
        )
    }

    private func label(directory: String?, project: HomeProject? = nil, worktreesEnabled: Bool = true) -> String? {
        HomeCompactRowPath.worktreeLabel(
            project: project ?? repo(),
            sessionDirectory: directory,
            worktreesEnabled: worktreesEnabled
        )
    }

    private func compactRow(_ status: SessionListStatus, isStale: Bool = false) -> SessionListCompactRow {
        SessionListSession(
            id: "s", title: "T", worktree: "prj", letter: "O", letterAccent: .red,
            subtitle: "/repo", status: status, statusIsStale: isStale, caption: "",
            projectName: "opencode-mobile"
        ).compactRow
    }

    private func section(worktree: String, _ sessions: SessionListItem...) -> ProjectSectionItem {
        ProjectSectionItem(
            id: worktree,
            worktree: worktree,
            displayName: worktree,
            pathSubtitle: worktree,
            letter: "P",
            letterAccent: .violet,
            allSessions: sessions,
            loadError: nil
        )
    }

    private func session(id: String, directory: String?, updatedAtEpochMs: Int64?) -> SessionListItem {
        SessionListItem(id: id, title: id, worktree: "", updatedAtEpochMs: updatedAtEpochMs, directory: directory)
    }
}
