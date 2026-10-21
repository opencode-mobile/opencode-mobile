import Testing
@testable import OpenCode_Mobile

struct SessionListHomeChromeTests {
    @Test func filterTabs_zeroProjectsOmitsAllAndScopeControl() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [], sessions: [])
        #expect(tabs.isEmpty)
    }

    @Test func filterTabs_oneProjectShowsOnlyThatProject() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [mobile], sessions: [row("1", mobile.key), row("2", mobile.key)])
        #expect(tabs.map(\.id) == ["path:/repo"])
        #expect(tabs.first?.title == "opencode-mobile")
        #expect(tabs.first?.subtitle == "/repo")
        #expect(tabs.first?.count == 2)
    }

    @Test func filterTabs_twoProjectsPrependsAll() {
        let tabs = SessionListHomeChrome.filterTabs(
            projects: [mobile, playground],
            sessions: [row("1", mobile.key), row("2", mobile.key), row("3", playground.key)]
        )
        #expect(tabs.map(\.id) == [SessionListFilterTab.allId, "path:/repo", "path:/play"])
        #expect(tabs.map(\.count) == [3, 2, 1])
        #expect(tabs.first?.title == "All")
        #expect(tabs.first?.pickerTitle == "All projects")
        #expect(tabs.first?.subtitle == "2 projects")
    }

    @Test func filterTabs_multiWorktreeProjectListsItsWorktrees() {
        let main = "/src/repo"
        let feature = "/src/repo-wt"
        let tabs = SessionListHomeChrome.filterTabs(
            projects: [repo, playground],
            sessions: [
                row("1", repo.key, directory: main, scope: HomeScope.worktreeID(projectKey: "prj", directory: main)),
                row("2", repo.key, directory: feature, scope: HomeScope.worktreeID(projectKey: "prj", directory: feature)),
                row("3", repo.key, directory: feature, scope: HomeScope.worktreeID(projectKey: "prj", directory: feature)),
            ]
        )

        #expect(tabs.map(\.id) == [
            SessionListFilterTab.allId,
            "prj",
            HomeScope.worktreeID(projectKey: "prj", directory: main),
            HomeScope.worktreeID(projectKey: "prj", directory: feature),
            "path:/play",
        ])
        let project = tabs[1]
        #expect(project.count == 3)
        #expect(project.title == "repo")
        #expect(project.subtitle == "All 2 worktrees")
        #expect(project.pickerSubtitle == "2 worktrees")
        #expect(!project.isWorktree)

        let mainTab = tabs[2]
        #expect(mainTab.isWorktree)
        #expect(mainTab.count == 1)
        #expect(mainTab.title == "repo")
        #expect(mainTab.subtitle == "main")
        #expect(mainTab.pickerTitle == "main")
        #expect(mainTab.pickerSubtitle == "main checkout")

        let featureTab = tabs[3]
        #expect(featureTab.count == 2)
        #expect(featureTab.subtitle == "feature/x \u{00B7} repo-wt")
        #expect(featureTab.pickerTitle == "feature/x")
        #expect(featureTab.pickerSubtitle == "repo-wt")
    }

    @Test func worktreeTabWithoutBranchReadsTheFolder() {
        let project = HomeProjectGrouping.group(
            addedPaths: ["/src/repo", "/src/repo-wt"],
            resolutions: ["/src/repo": resolution, "/src/repo-wt": resolution]
        )[0]
        let tabs = SessionListHomeChrome.filterTabs(projects: [project], sessions: [])
        #expect(tabs.map(\.subtitle) == ["All 2 worktrees", "repo", "repo-wt"])
        #expect(tabs.map(\.pickerTitle) == ["repo", "repo", "repo-wt"])
    }

    @Test func resolvedFilterId_fallsBackToSoleProjectWhenAllIsOmitted() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [mobile], sessions: [])
        #expect(
            SessionListHomeChrome.resolvedFilterId(selectedId: SessionListFilterTab.allId, tabs: tabs) == "path:/repo"
        )
    }

    @Test func resolvedFilterId_keepsProjectWhenAllReappears() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [mobile, playground], sessions: [])
        #expect(SessionListHomeChrome.resolvedFilterId(selectedId: "path:/repo", tabs: tabs) == "path:/repo")
    }

    @Test func resolvedFilterId_mapsAnAddedPathToItsProject() {
        let projects = [repo, playground]
        let tabs = SessionListHomeChrome.filterTabs(projects: projects, sessions: [])
        #expect(
            SessionListHomeChrome.resolvedFilterId(selectedId: "/src/repo-wt", tabs: tabs, projects: projects) == "prj"
        )
        #expect(
            SessionListHomeChrome.resolvedFilterId(selectedId: "path:/src/repo-wt", tabs: tabs, projects: projects) == "prj"
        )
    }

    @Test func resolvedFilterId_fallsBackToAllWhenTwoPlusAndSelectionMissing() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [mobile, playground], sessions: [])
        #expect(
            SessionListHomeChrome.resolvedFilterId(selectedId: "/gone", tabs: tabs, projects: [mobile, playground])
                == SessionListFilterTab.allId
        )
    }

    @Test func newSessionAction_zeroProjectsDoesNothing() {
        #expect(SessionListHomeChrome.newSessionAction(projects: [], selectedFilterId: SessionListFilterTab.allId) == .none)
    }

    @Test func newSessionAction_oneProjectCreatesInIt() {
        #expect(
            SessionListHomeChrome.newSessionAction(projects: [mobile], selectedFilterId: "path:/repo")
                == .create(directory: "/repo")
        )
        #expect(
            SessionListHomeChrome.newSessionAction(projects: [mobile], selectedFilterId: SessionListFilterTab.allId)
                == .create(directory: "/repo")
        )
    }

    @Test func newSessionAction_allSelectedPresentsPicker() {
        #expect(
            SessionListHomeChrome.newSessionAction(projects: [mobile, playground], selectedFilterId: SessionListFilterTab.allId)
                == .presentPicker
        )
    }

    @Test func newSessionAction_scopedProjectCreatesThere() {
        #expect(
            SessionListHomeChrome.newSessionAction(projects: [mobile, playground], selectedFilterId: "path:/play")
                == .create(directory: "/play")
        )
    }

    @Test func newSessionAction_multiWorktreeProjectAsksWhereToRun() {
        #expect(
            SessionListHomeChrome.newSessionAction(projects: [repo, playground], selectedFilterId: "prj")
                == .chooseWorktree(projectKey: "prj")
        )
        #expect(SessionListHomeChrome.pick(repo) == .chooseWorktree(projectKey: "prj"))
        #expect(SessionListHomeChrome.pick(playground) == .create(directory: "/play"))
    }

    @Test func newSessionAction_worktreeScopeCreatesInThatWorktree() {
        let scope = HomeScope.worktreeID(projectKey: "prj", directory: "/src/repo-wt")
        #expect(
            SessionListHomeChrome.newSessionAction(projects: [repo, playground], selectedFilterId: scope)
                == .create(directory: "/src/repo-wt")
        )
    }

    @Test func runInOptionsCountRunningSessionsPerWorktree() {
        let options = HomeRunInOption.options(
            for: repo,
            sessions: [
                row("1", repo.key, directory: "/src/repo-wt", status: .running),
                row("2", repo.key, directory: "/src/repo-wt", status: .retrying(attempt: 1)),
                row("3", repo.key, directory: "/src/repo", status: .idle),
                row("4", playground.key, directory: "/src/repo", status: .running),
            ]
        )
        #expect(options.map(\.directory) == ["/src/repo", "/src/repo-wt"])
        #expect(options.map(\.title) == ["main", "feature/x"])
        #expect(options.map(\.subtitle) == ["main checkout", "repo-wt"])
        #expect(options.map(\.trailing) == [nil, "2 running"])
    }

    @Test func projectLoad_loadingWinsOverFailure() {
        let load = SessionListHomeChrome.projectLoad(
            repo,
            loadStates: ["/src/repo": .failed(message: "x"), "/src/repo-wt": .loading(isSlow: true)]
        )
        #expect(load == .loading(isSlow: true))
        #expect(SessionListHomeChrome.projectLoad(playground, loadStates: [:]) == nil)
    }

    @Test func terminalAction_followsProjects() {
        #expect(SessionListHomeChrome.terminalAction(projects: []) == .none)
        #expect(SessionListHomeChrome.terminalAction(projects: [mobile]) == .openCatalog)
    }

    @Test func newSessionLongPressAction_needsTwoProjects() {
        #expect(SessionListHomeChrome.newSessionLongPressAction(projects: [mobile]) == .none)
        #expect(SessionListHomeChrome.newSessionLongPressAction(projects: [mobile, playground]) == .presentPicker)
    }

    private let resolution = HomeProjectResolution(id: "prj", canonical: "/src/repo", isRepository: true)

    private var repo: HomeProject {
        HomeProjectGrouping.group(
            addedPaths: ["/src/repo", "/src/repo-wt"],
            resolutions: ["/src/repo": resolution, "/src/repo-wt": resolution],
            branches: ["/src/repo": "main", "/src/repo-wt": "feature/x"]
        )[0]
    }

    private var mobile: HomeProject {
        HomeProject(
            key: "path:/repo",
            projectID: nil,
            displayName: "opencode-mobile",
            memberPaths: ["/repo"],
            isRepository: false,
            worktrees: [HomeWorktree(directory: "/repo", branch: nil, isMain: true)]
        )
    }

    private var playground: HomeProject {
        HomeProject(
            key: "path:/play",
            projectID: nil,
            displayName: "playground",
            memberPaths: ["/play"],
            isRepository: false,
            worktrees: [HomeWorktree(directory: "/play", branch: nil, isMain: true)]
        )
    }

    private func row(
        _ id: String,
        _ projectKey: String,
        directory: String? = nil,
        scope: String? = nil,
        status: SessionListStatus = .idle
    ) -> SessionListSession {
        SessionListSession(
            id: id,
            title: id,
            worktree: projectKey,
            letter: "P",
            letterAccent: .violet,
            subtitle: "",
            status: status,
            caption: "",
            directory: directory,
            worktreeScopeID: scope
        )
    }
}
