import Testing
@testable import OpenCode_Mobile

struct SessionListHomeChromeTests {
    @Test func filterTabs_zeroProjectsOmitsAllAndScopeControl() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [])
        #expect(tabs.isEmpty)
    }

    @Test func filterTabs_oneProjectShowsOnlyThatProject() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [mobile])
        #expect(tabs.map(\.id) == ["path:/repo"])
        #expect(tabs.first?.title == "opencode-mobile")
        #expect(tabs.first?.subtitle == "/repo")
    }

    @Test func filterTabs_twoProjectsPrependsAll() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [mobile, playground])
        #expect(tabs.map(\.id) == [SessionListFilterTab.allId, "path:/repo", "path:/play"])
        #expect(tabs.first?.title == "All")
        #expect(tabs.first?.pickerTitle == "All projects")
        #expect(tabs.first?.subtitle == "2 projects")
    }

    @Test func filterTabs_listsAllAndProjectsOnly() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [repo, playground])

        #expect(tabs.map(\.id) == [SessionListFilterTab.allId, "prj", "path:/play"])
        let project = tabs[1]
        #expect(project.title == "repo")
        #expect(project.subtitle == "All 2 worktrees")
        // The worktree pill carries the count; the row keeps the path.
        #expect(project.pickerSubtitle == "/src/repo")
        #expect(!project.isWorktree)
        #expect(project.dockTitle == "repo")
    }

    @Test func worktreeTabs_listEachWorktree() {
        let main = "/src/repo"
        let feature = "/src/repo-wt"
        let tabs = SessionListHomeChrome.worktreeTabs(for: repo)
        #expect(tabs.map(\.id) == [
            HomeScope.worktreeID(projectKey: "prj", directory: main),
            HomeScope.worktreeID(projectKey: "prj", directory: feature),
        ])

        let mainTab = tabs[0]
        #expect(mainTab.isWorktree)
        #expect(mainTab.parentId == "prj")
        #expect(mainTab.title == "repo")
        #expect(mainTab.subtitle == "main")
        #expect(mainTab.pickerTitle == "main")
        #expect(mainTab.pickerSubtitle == "main checkout")

        let featureTab = tabs[1]
        #expect(featureTab.subtitle == "feature/x \u{00B7} repo-wt")
        #expect(featureTab.pickerTitle == "feature/x")
        #expect(featureTab.dockTitle == "repo")
        #expect(featureTab.pickerSubtitle == "repo-wt")

        #expect(SessionListHomeChrome.worktreeTabs(for: playground).isEmpty)
    }

    @Test func worktreeTabWithoutBranchReadsTheFolder() {
        let project = HomeProjectGrouping.group(
            addedPaths: ["/src/repo", "/src/repo-wt"],
            resolutions: ["/src/repo": resolution, "/src/repo-wt": resolution]
        )[0]
        let tabs = SessionListHomeChrome.filterTabs(projects: [project])
        #expect(tabs.map(\.subtitle) == ["All 2 worktrees"])
        let worktrees = SessionListHomeChrome.worktreeTabs(for: project)
        #expect(worktrees.map(\.subtitle) == ["repo", "repo-wt"])
        #expect(worktrees.map(\.pickerTitle) == ["repo", "repo-wt"])
    }

    @Test func worktreesOff_projectTabReadsAsOneCheckout() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [repo, playground], worktreesEnabled: false)
        #expect(tabs.map(\.id) == [SessionListFilterTab.allId, "prj", "path:/play"])
        #expect(tabs[1].subtitle == "/src/repo")
        #expect(tabs[1].pickerSubtitle == "/src/repo")
    }

    @Test func resolvedFilterId_keepsAWorktreeScopeWhileWorktreesAreOn() {
        let projects = [repo, playground]
        let tabs = SessionListHomeChrome.filterTabs(projects: projects)
        let scope = HomeScope.worktreeID(projectKey: "prj", directory: "/src/repo-wt")
        #expect(SessionListHomeChrome.resolvedFilterId(selectedId: scope, tabs: tabs, projects: projects) == scope)
    }

    @Test func resolvedFilterId_mapsAWorktreeScopeToItsProjectWhileWorktreesAreOff() {
        let projects = [repo, playground]
        let tabs = SessionListHomeChrome.filterTabs(projects: projects, worktreesEnabled: false)
        let scope = HomeScope.worktreeID(projectKey: "prj", directory: "/src/repo-wt")
        #expect(
            SessionListHomeChrome.resolvedFilterId(selectedId: scope, tabs: tabs, projects: projects, worktreesEnabled: false)
                == "prj"
        )
        // Turning worktrees back on restores the stored worktree scope.
        #expect(SessionListHomeChrome.resolvedFilterId(selectedId: scope, tabs: tabs, projects: projects) == scope)
    }

    @Test func selectedScopeTab_describesAWorktreeScopeForTheDock() {
        let projects = [repo, playground]
        let tabs = SessionListHomeChrome.filterTabs(projects: projects)
        let scope = HomeScope.worktreeID(projectKey: "prj", directory: "/src/repo-wt")
        let tab = SessionListHomeChrome.selectedScopeTab(
            selectedId: scope, tabs: tabs, projects: projects
        )
        #expect(tab?.id == scope)
        #expect(tab?.dockTitle == "repo")
        #expect(tab?.subtitle == "feature/x \u{00B7} repo-wt")
        #expect(tab?.parentId == "prj")
        #expect(
            SessionListHomeChrome.selectedScopeTab(selectedId: "prj", tabs: tabs, projects: projects) == nil
        )
        #expect(
            SessionListHomeChrome.selectedScopeTab(
                selectedId: scope, tabs: tabs, projects: projects, worktreesEnabled: false
            ) == nil
        )
    }

    @Test func resolvedFilterId_fallsBackToSoleProjectWhenAllIsOmitted() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [mobile])
        #expect(
            SessionListHomeChrome.resolvedFilterId(selectedId: SessionListFilterTab.allId, tabs: tabs) == "path:/repo"
        )
    }

    @Test func resolvedFilterId_keepsProjectWhenAllReappears() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [mobile, playground])
        #expect(SessionListHomeChrome.resolvedFilterId(selectedId: "path:/repo", tabs: tabs) == "path:/repo")
    }

    @Test func resolvedFilterId_mapsAnAddedPathToItsProject() {
        let projects = [repo, playground]
        let tabs = SessionListHomeChrome.filterTabs(projects: projects)
        #expect(
            SessionListHomeChrome.resolvedFilterId(selectedId: "/src/repo-wt", tabs: tabs, projects: projects) == "prj"
        )
        #expect(
            SessionListHomeChrome.resolvedFilterId(selectedId: "path:/src/repo-wt", tabs: tabs, projects: projects) == "prj"
        )
    }

    @Test func resolvedFilterId_fallsBackToAllWhenTwoPlusAndSelectionMissing() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [mobile, playground])
        #expect(
            SessionListHomeChrome.resolvedFilterId(selectedId: "/gone", tabs: tabs, projects: [mobile, playground])
                == SessionListFilterTab.allId
        )
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

    @Test func dockSwipe_walksAllAndProjectsOnly() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [repo, playground]).map(\.id)
        #expect(tabs == [SessionListFilterTab.allId, "prj", "path:/play"])
        #expect(
            SessionListScopePaging.neighborID(selectedID: "prj", parentID: nil, tabIDs: tabs, direction: .next)
                == "path:/play"
        )
        #expect(
            SessionListScopePaging.neighborID(selectedID: "prj", parentID: nil, tabIDs: tabs, direction: .previous)
                == SessionListFilterTab.allId
        )
        #expect(
            SessionListScopePaging.neighborID(selectedID: "path:/play", parentID: nil, tabIDs: tabs, direction: .next)
                == nil
        )
    }

    @Test func dockSwipe_fromAWorktreeScopeSitsInsideItsProject() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [repo, playground]).map(\.id)
        let scope = HomeScope.worktreeID(projectKey: "prj", directory: "/src/repo-wt")
        #expect(
            SessionListScopePaging.neighborID(selectedID: scope, parentID: "prj", tabIDs: tabs, direction: .previous)
                == "prj"
        )
        #expect(
            SessionListScopePaging.neighborID(selectedID: scope, parentID: "prj", tabIDs: tabs, direction: .next)
                == "path:/play"
        )
        // The last project's worktree has nothing after it.
        let last = ["prj"]
        #expect(
            SessionListScopePaging.neighborID(selectedID: scope, parentID: "prj", tabIDs: last, direction: .next) == nil
        )
        #expect(
            SessionListScopePaging.neighborID(selectedID: scope, parentID: "prj", tabIDs: last, direction: .previous)
                == "prj"
        )
    }

    private let resolution = HomeProjectResolution(id: "prj", canonical: "/src/repo", isRepository: true)

    private var repo: HomeProject {
        HomeProjectGrouping.group(
            addedPaths: ["/src/repo", "/src/repo-wt"],
            resolutions: ["/src/repo": resolution, "/src/repo-wt": resolution],
            branches: ["/src/repo": "main", "/src/repo-wt": "feature/x"]
        )[0]
    }

    private var singleRepo: HomeProject {
        HomeProjectGrouping.group(
            addedPaths: ["/src/single"],
            resolutions: ["/src/single": HomeProjectResolution(id: "prj_single", canonical: "/src/single", isRepository: true)],
            branches: ["/src/single": "main"]
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
