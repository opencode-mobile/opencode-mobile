import Foundation
import Testing
@testable import OpenCode_Mobile

/// #1004: the Run session in card and its Project, Worktree and Starts from sheets.
@MainActor
struct NewSessionDraftPresentationTests {
    @Test func localDraftShowsProjectAndWorktreeRowsAndTheMainCheckoutPath() {
        let state = uiState(NewSessionDraft(projectKey: "prj", projectDirectory: "/src/repo"))

        #expect(state.rows.map(\.kind) == [.project, .worktree])
        #expect(state.rows[0].row.value.resolved == "repo")
        #expect(state.rows[0].row.label.resolved == "Project")
        #expect(state.rows[1].row.value.resolved == "Local")
        #expect(!state.rows[1].row.highlighted)
        #expect(state.rows[1].row.enabled)
        #expect(state.checkoutPath?.resolved == "/src/repo")
        #expect(state.worktreeSheetTitle.resolved == "Run in repo")
    }

    @Test func existingWorktreeShowsItsBranchHighlightedWithItsPath() throws {
        let state = uiState(NewSessionDraft(
            projectKey: "prj",
            projectDirectory: "/src/repo",
            place: .worktree(directory: "/src/repo-wt", branch: "feature/x")
        ))

        let worktree = try #require(state.rows.first { $0.kind == .worktree }?.row)
        #expect(worktree.value.resolved == "feature/x")
        #expect(worktree.monospacedValue == true)
        #expect(worktree.highlighted == true)
        #expect(state.checkoutPath?.resolved == "/src/repo-wt")
    }

    @Test func newWorktreeShowsStartsFromOnlyOnV2() {
        let draft = NewSessionDraft(projectKey: "prj", projectDirectory: "/src/repo", place: .newWorktree(baseBranch: nil))

        let v2 = uiState(draft, serverProtocol: .v2)
        let v1 = uiState(draft, serverProtocol: .v1)

        #expect(v2.rows.map(\.kind) == [.project, .worktree, .startsFrom])
        #expect(v2.rows[1].row.value.resolved == "New worktree")
        #expect(v2.rows[1].row.highlighted)
        // The default base is the main checkout's branch.
        #expect(v2.rows[2].row.value.resolved == "main")
        #expect(v2.checkoutPath?.resolved == "Created when you send")
        #expect(v1.rows.map(\.kind) == [.project, .worktree])
    }

    @Test func pickedBaseBranchShowsInStartsFromAndTheSheet() {
        let draft = NewSessionDraft(projectKey: "prj", projectDirectory: "/src/repo", place: .newWorktree(baseBranch: "release"))

        let state = uiState(draft, serverProtocol: .v2, baseBranches: .loaded(["main", "release", "feature/x"]))

        #expect(state.rows.last?.row.value.resolved == "release")
        #expect(state.baseBranchRows.map(\.ref) == [nil, "release", "feature/x"])
        #expect(state.baseBranchRows.first?.title.resolved == "main (current)")
        #expect(state.baseBranchRows.first { $0.isSelected }?.ref == "release")
    }

    @Test func folderThatIsNotAGitRepositoryShowsADisabledWorktreeRow() throws {
        let state = uiState(
            NewSessionDraft(projectKey: "path:/notes", projectDirectory: "/notes"),
            projects: [notes]
        )

        let worktree = try #require(state.rows.first { $0.kind == .worktree }?.row)
        #expect(worktree.value.resolved == "None \u{00B7} not a Git repo")
        #expect(worktree.enabled == false)
        #expect(worktree.tile == .icon(.folder))
    }

    @Test func worktreesOffHideTheWorktreeRow() {
        let state = uiState(
            NewSessionDraft(projectKey: "prj", projectDirectory: "/src/repo"),
            worktreesEnabled: false
        )

        #expect(state.rows.map(\.kind) == [.project])
    }

    @Test func worktreeSheetListsLocalNewWorktreeAndTheProjectsWorktreesWithRunningCounts() {
        let state = uiState(
            NewSessionDraft(projectKey: "prj", projectDirectory: "/src/repo"),
            runningCounts: ["/src/repo-wt": 2]
        )

        let options = state.worktreeOptions
        #expect(options.local.selected)
        #expect(options.local.subtitle?.resolved == "main \u{00B7} current checkout")
        #expect(options.newWorktree?.subtitle?.resolved == "Isolated copy, created when you send")
        #expect(options.worktrees.map(\.place) == [.worktree(directory: "/src/repo-wt", branch: "feature/x")])
        #expect(options.worktrees.first?.subtitle?.resolved == "2 running")
    }

    @Test func worktreeSheetShowsTheFolderWhenNothingRuns() {
        let state = uiState(NewSessionDraft(projectKey: "prj", projectDirectory: "/src/repo"))

        #expect(state.worktreeOptions.worktrees.first?.subtitle?.resolved == "repo-wt")
    }

    @Test func newWorktreeOptionFollowsNewWorktreeAvailability() {
        let draft = NewSessionDraft(projectKey: "prj", projectDirectory: "/src/repo")

        #expect(uiState(draft, serverCanCreateWorktrees: true).worktreeOptions.newWorktree != nil)
        #expect(uiState(draft, serverCanCreateWorktrees: false).worktreeOptions.newWorktree == nil)
        #expect(uiState(draft, worktreesEnabled: false).worktreeOptions.newWorktree == nil)
        let notGit = uiState(NewSessionDraft(projectKey: "path:/notes", projectDirectory: "/notes"), projects: [notes])
        #expect(notGit.worktreeOptions.newWorktree == nil)
        #expect(notGit.worktreeOptions.worktrees.isEmpty)
    }

    @Test func projectSheetListsHomeOrderWithTheCurrentProjectSelected() {
        let state = uiState(NewSessionDraft(projectKey: "path:/notes", projectDirectory: "/notes"), projects: [repo, notes])

        #expect(state.projectOptions.map(\.key) == ["prj", "path:/notes"])
        #expect(state.projectOptions.map(\.selected) == [false, true])
    }

    @Test func projectSearchFiltersByNameIgnoringCaseAndAccents() {
        let options = NewSessionDraftPresentation.projectOptions(projects: [repo, notes, cafe], selectedKey: nil)

        #expect(NewSessionDraftPresentation.filter(options, query: "").count == 3)
        #expect(NewSessionDraftPresentation.filter(options, query: " NOT ").map(\.key) == ["path:/notes"])
        #expect(NewSessionDraftPresentation.filter(options, query: "cafe").map(\.key) == ["path:/cafe"])
        #expect(NewSessionDraftPresentation.filter(options, query: "zzz").isEmpty)
    }

    @Test func unknownProjectFallsBackToTheFolderName() {
        let state = uiState(NewSessionDraft(projectKey: nil, projectDirectory: "/elsewhere/tool"), projects: [])

        #expect(state.rows.map(\.kind) == [.project])
        #expect(state.rows[0].row.value.resolved == "tool")
    }

    // MARK: Fixtures

    private func uiState(
        _ draft: NewSessionDraft,
        projects: [HomeProject]? = nil,
        worktreesEnabled: Bool = true,
        runningCounts: [String: Int] = [:],
        serverProtocol: ServerProtocol = .v2,
        serverCanCreateWorktrees: Bool = true,
        baseBranches: NewSessionBaseBranchesLoad = .idle
    ) -> SessionChatDraftUiState {
        NewSessionDraftPresentation.uiState(
            draft: draft,
            context: NewSessionDraftContext(
                projects: projects ?? [repo, notes],
                worktreesEnabled: worktreesEnabled,
                runningCounts: runningCounts
            ),
            serverProtocol: serverProtocol,
            serverCanCreateWorktrees: serverCanCreateWorktrees,
            isCreating: false,
            baseBranches: baseBranches
        )
    }

    private var repo: HomeProject {
        let resolution = HomeProjectResolution(id: "prj", canonical: "/src/repo", isRepository: true)
        return HomeProjectGrouping.group(
            addedPaths: ["/src/repo", "/src/repo-wt"],
            resolutions: ["/src/repo": resolution, "/src/repo-wt": resolution],
            branches: ["/src/repo": "main", "/src/repo-wt": "feature/x"]
        )[0]
    }

    private var notes: HomeProject {
        HomeProject(
            key: "path:/notes",
            projectID: nil,
            displayName: "notes",
            memberPaths: ["/notes"],
            isRepository: false,
            worktrees: [HomeWorktree(directory: "/notes", branch: nil, isMain: true)]
        )
    }

    private var cafe: HomeProject {
        HomeProject(
            key: "path:/cafe",
            projectID: nil,
            displayName: "Caf\u{00E9}",
            memberPaths: ["/cafe"],
            isRepository: false,
            worktrees: [HomeWorktree(directory: "/cafe", branch: nil, isMain: true)]
        )
    }
}
