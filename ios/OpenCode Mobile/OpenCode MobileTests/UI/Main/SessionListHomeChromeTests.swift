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

    @Test func filterTabs_listsAllAndProjectsOnly() {
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

        #expect(tabs.map(\.id) == [SessionListFilterTab.allId, "prj", "path:/play"])
        let project = tabs[1]
        #expect(project.count == 3)
        #expect(project.title == "repo")
        #expect(project.subtitle == "All 2 worktrees")
        // The worktree pill carries the count; the row keeps the path.
        #expect(project.pickerSubtitle == "/src/repo")
        #expect(!project.isWorktree)
        #expect(project.dockTitle == "repo")
    }

    @Test func worktreeTabs_listEachWorktreeWithItsCount() {
        let main = "/src/repo"
        let feature = "/src/repo-wt"
        let tabs = SessionListHomeChrome.worktreeTabs(
            for: repo,
            sessions: [
                row("1", repo.key, directory: main, scope: HomeScope.worktreeID(projectKey: "prj", directory: main)),
                row("2", repo.key, directory: feature, scope: HomeScope.worktreeID(projectKey: "prj", directory: feature)),
                row("3", repo.key, directory: feature, scope: HomeScope.worktreeID(projectKey: "prj", directory: feature)),
            ]
        )
        #expect(tabs.map(\.id) == [
            HomeScope.worktreeID(projectKey: "prj", directory: main),
            HomeScope.worktreeID(projectKey: "prj", directory: feature),
        ])

        let mainTab = tabs[0]
        #expect(mainTab.isWorktree)
        #expect(mainTab.parentId == "prj")
        #expect(mainTab.count == 1)
        #expect(mainTab.title == "repo")
        #expect(mainTab.subtitle == "main")
        #expect(mainTab.pickerTitle == "main")
        #expect(mainTab.pickerSubtitle == "main checkout")

        let featureTab = tabs[1]
        #expect(featureTab.count == 2)
        #expect(featureTab.subtitle == "feature/x \u{00B7} repo-wt")
        #expect(featureTab.pickerTitle == "feature/x")
        #expect(featureTab.dockTitle == "repo")
        #expect(featureTab.pickerSubtitle == "repo-wt")

        #expect(SessionListHomeChrome.worktreeTabs(for: playground, sessions: []).isEmpty)
    }

    @Test func worktreeTabWithoutBranchReadsTheFolder() {
        let project = HomeProjectGrouping.group(
            addedPaths: ["/src/repo", "/src/repo-wt"],
            resolutions: ["/src/repo": resolution, "/src/repo-wt": resolution]
        )[0]
        let tabs = SessionListHomeChrome.filterTabs(projects: [project], sessions: [])
        #expect(tabs.map(\.subtitle) == ["All 2 worktrees"])
        let worktrees = SessionListHomeChrome.worktreeTabs(for: project, sessions: [])
        #expect(worktrees.map(\.subtitle) == ["repo", "repo-wt"])
        #expect(worktrees.map(\.pickerTitle) == ["repo", "repo-wt"])
    }

    @Test func worktreesOff_projectTabReadsAsOneCheckout() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [repo, playground], sessions: [], worktreesEnabled: false)
        #expect(tabs.map(\.id) == [SessionListFilterTab.allId, "prj", "path:/play"])
        #expect(tabs[1].subtitle == "/src/repo")
        #expect(tabs[1].pickerSubtitle == "/src/repo")
    }

    @Test func resolvedFilterId_keepsAWorktreeScopeWhileWorktreesAreOn() {
        let projects = [repo, playground]
        let tabs = SessionListHomeChrome.filterTabs(projects: projects, sessions: [])
        let scope = HomeScope.worktreeID(projectKey: "prj", directory: "/src/repo-wt")
        #expect(SessionListHomeChrome.resolvedFilterId(selectedId: scope, tabs: tabs, projects: projects) == scope)
    }

    @Test func resolvedFilterId_mapsAWorktreeScopeToItsProjectWhileWorktreesAreOff() {
        let projects = [repo, playground]
        let tabs = SessionListHomeChrome.filterTabs(projects: projects, sessions: [], worktreesEnabled: false)
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
        let tabs = SessionListHomeChrome.filterTabs(projects: projects, sessions: [])
        let scope = HomeScope.worktreeID(projectKey: "prj", directory: "/src/repo-wt")
        let tab = SessionListHomeChrome.selectedScopeTab(
            selectedId: scope, tabs: tabs, projects: projects, sessions: []
        )
        #expect(tab?.id == scope)
        #expect(tab?.dockTitle == "repo")
        #expect(tab?.subtitle == "feature/x \u{00B7} repo-wt")
        #expect(tab?.parentId == "prj")
        #expect(
            SessionListHomeChrome.selectedScopeTab(selectedId: "prj", tabs: tabs, projects: projects, sessions: []) == nil
        )
        #expect(
            SessionListHomeChrome.selectedScopeTab(
                selectedId: scope, tabs: tabs, projects: projects, sessions: [], worktreesEnabled: false
            ) == nil
        )
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

    @Test func newSessionAction_tapStartsOnTheMainCheckoutWithoutAsking() {
        #expect(
            SessionListHomeChrome.newSessionAction(projects: [repo, playground], selectedFilterId: "prj")
                == .create(directory: "/src/repo")
        )
        #expect(
            SessionListHomeChrome.newSessionAction(
                projects: [repo, playground], selectedFilterId: "prj", worktreesEnabled: false
            ) == .create(directory: "/src/repo")
        )
        #expect(
            SessionListHomeChrome.newSessionAction(projects: [repo], selectedFilterId: SessionListFilterTab.allId)
                == .create(directory: "/src/repo")
        )
    }

    @Test func newSessionAction_mainCheckoutWinsOverTheFirstAddedPath() {
        // The worktree was added first; the main checkout is still where a tap starts.
        let project = HomeProjectGrouping.group(
            addedPaths: ["/src/repo-wt"],
            resolutions: ["/src/repo-wt": resolution],
            inventories: ["/src/repo-wt": ["/src/repo", "/src/repo-wt"]]
        )[0]
        #expect(project.primaryPath == "/src/repo-wt")
        #expect(project.mainCheckoutDirectory == "/src/repo")
        #expect(
            SessionListHomeChrome.newSessionAction(projects: [project], selectedFilterId: project.key)
                == .create(directory: "/src/repo")
        )
    }

    @Test func pick_afterATapStartsOnTheMainCheckout() {
        #expect(SessionListHomeChrome.pick(repo, holdPick: false) == .create(directory: "/src/repo"))
        #expect(SessionListHomeChrome.pick(playground, holdPick: false) == .create(directory: "/play"))
    }

    @Test func pick_afterAHoldOpensRunIn() {
        #expect(
            SessionListHomeChrome.pick(repo, holdPick: true)
                == .show(.runIn(projectKey: "prj", showsOtherProjects: false))
        )
        // #752: a single-checkout project opens Run in too, where New worktree… is.
        #expect(
            SessionListHomeChrome.pick(singleRepo, holdPick: true)
                == .show(.runIn(projectKey: "prj_single", showsOtherProjects: false))
        )
        #expect(
            SessionListHomeChrome.pick(playground, holdPick: true)
                == .show(.runIn(projectKey: "path:/play", showsOtherProjects: false))
        )
        #expect(SessionListHomeChrome.pick(playground, holdPick: true, worktreesEnabled: false) == .create(directory: "/play"))
        #expect(
            SessionListHomeChrome.pick(repo, holdPick: true, worktreesEnabled: false) == .create(directory: "/src/repo")
        )
    }

    @Test func newSessionAction_worktreeScopeCreatesInThatWorktree() {
        let scope = HomeScope.worktreeID(projectKey: "prj", directory: "/src/repo-wt")
        #expect(
            SessionListHomeChrome.newSessionAction(projects: [repo, playground], selectedFilterId: scope)
                == .create(directory: "/src/repo-wt")
        )
        // Worktrees off: the stored worktree scope reads as its project, which starts on main.
        #expect(
            SessionListHomeChrome.newSessionAction(
                projects: [repo, playground], selectedFilterId: scope, worktreesEnabled: false
            ) == .create(directory: "/src/repo")
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

    @Test func newSessionLongPressAction_opensRunInForAnyTargetWhileWorktreesAreOn() {
        // #752: Run in lists New worktree…, so even one checkout has something to choose.
        #expect(
            SessionListHomeChrome.newSessionLongPressAction(projects: [mobile], selectedFilterId: "path:/repo")
                == .runIn(projectKey: "path:/repo", showsOtherProjects: false)
        )
        #expect(
            SessionListHomeChrome.newSessionLongPressAction(
                projects: [mobile, playground], selectedFilterId: SessionListFilterTab.allId
            ) == .projects(holdPick: true)
        )
        #expect(
            SessionListHomeChrome.newSessionLongPressAction(projects: [mobile, playground], selectedFilterId: "path:/play")
                == .runIn(projectKey: "path:/play", showsOtherProjects: true)
        )
    }

    @Test func newSessionLongPressAction_singleCheckoutGitProjectOpensRunIn() {
        #expect(singleRepo.worktrees.count == 1)
        #expect(
            SessionListHomeChrome.newSessionLongPressAction(projects: [singleRepo], selectedFilterId: "prj_single")
                == .runIn(projectKey: "prj_single", showsOtherProjects: false)
        )
        #expect(
            SessionListHomeChrome.newSessionLongPressAction(
                projects: [singleRepo, playground], selectedFilterId: "prj_single"
            ) == .runIn(projectKey: "prj_single", showsOtherProjects: true)
        )
        // Worktrees off: unchanged, a single project has nothing to choose.
        #expect(
            SessionListHomeChrome.newSessionLongPressAction(
                projects: [singleRepo], selectedFilterId: "prj_single", worktreesEnabled: false
            ) == nil
        )
    }

    @Test func newSessionLongPressAction_opensRunInForAMultiWorktreeTarget() {
        #expect(
            SessionListHomeChrome.newSessionLongPressAction(projects: [repo, playground], selectedFilterId: "prj")
                == .runIn(projectKey: "prj", showsOtherProjects: true)
        )
        let scope = HomeScope.worktreeID(projectKey: "prj", directory: "/src/repo-wt")
        #expect(
            SessionListHomeChrome.newSessionLongPressAction(projects: [repo, playground], selectedFilterId: scope)
                == .runIn(projectKey: "prj", showsOtherProjects: true)
        )
        // One project: Run in without Other projects.
        #expect(
            SessionListHomeChrome.newSessionLongPressAction(projects: [repo], selectedFilterId: SessionListFilterTab.allId)
                == .runIn(projectKey: "prj", showsOtherProjects: false)
        )
        // All with several projects has no target: the project list.
        #expect(
            SessionListHomeChrome.newSessionLongPressAction(
                projects: [repo, playground], selectedFilterId: SessionListFilterTab.allId
            ) == .projects(holdPick: true)
        )
    }

    @Test func newSessionLongPressAction_neverOffersRunInWhileWorktreesAreOff() {
        #expect(
            SessionListHomeChrome.newSessionLongPressAction(
                projects: [repo, playground], selectedFilterId: "prj", worktreesEnabled: false
            ) == .projects(holdPick: true)
        )
        #expect(
            SessionListHomeChrome.newSessionLongPressAction(
                projects: [repo], selectedFilterId: "prj", worktreesEnabled: false
            ) == nil
        )
    }

    @Test func otherProjectsListTheRestBelowRunIn() {
        let projects = [repo, playground, mobile]
        #expect(
            SessionListHomeChrome.otherProjects(projects, mode: .runIn(projectKey: "prj", showsOtherProjects: true))
                .map(\.key) == ["path:/play", "path:/repo"]
        )
        #expect(
            SessionListHomeChrome.otherProjects(projects, mode: .runIn(projectKey: "prj", showsOtherProjects: false))
                .isEmpty
        )
        #expect(SessionListHomeChrome.otherProjects(projects, mode: .projects(holdPick: true)).isEmpty)
    }

    @Test func targetProject_followsTheScope() {
        let scope = HomeScope.worktreeID(projectKey: "prj", directory: "/src/repo-wt")
        #expect(SessionListHomeChrome.targetProject(projects: [repo, playground], selectedFilterId: scope)?.key == "prj")
        #expect(
            SessionListHomeChrome.targetProject(projects: [repo, playground], selectedFilterId: "path:/play")?.key
                == "path:/play"
        )
        #expect(
            SessionListHomeChrome.targetProject(projects: [repo, playground], selectedFilterId: SessionListFilterTab.allId)
                == nil
        )
        #expect(
            SessionListHomeChrome.targetProject(projects: [repo], selectedFilterId: SessionListFilterTab.allId)?.key == "prj"
        )
    }

    @Test func dockSwipe_walksAllAndProjectsOnly() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [repo, playground], sessions: []).map(\.id)
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
        let tabs = SessionListHomeChrome.filterTabs(projects: [repo, playground], sessions: []).map(\.id)
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
