import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct ProjectListHomeViewModelTests {
    @Test func pullToRefresh_keepsRefreshingUntilLiveStoreCatchUpFinishes() async {
        let sessions = [
            Session(id: "1", title: "Alpha", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 1),
        ]
        let sessionRepository = FakeSessionRepository(sessions: sessions)
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        let loaded = await waitUntil {
            if case let .loaded(sections, _, _, _) = viewModel.state {
                return sections.first?.allSessions.isEmpty == false
            }
            return false
        }
        #expect(loaded)

        sessionRepository.holdNextListSessions()
        let refreshTask = Task { await viewModel.pullToRefresh() }
        let blocked = await waitUntil(timeoutMs: 5_000) {
            viewModel.isRefreshing && sessionRepository.isListBlocked
        }
        #expect(blocked)
        sessionRepository.resumeListSessions()
        await refreshTask.value
        #expect(!viewModel.isRefreshing)
    }

    @Test func catchUpChrome_doesNotDriveHomePullToRefresh() async {
        let sessions = [
            Session(id: "1", title: "Alpha", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 1),
        ]
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: sessions),
            messageRepository: FakeMessageRepository(catchUpChrome: true),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        let loaded = await waitUntil {
            if case let .loaded(sections, _, _, _) = viewModel.state {
                return sections.first?.allSessions.isEmpty == false
            }
            return false
        }
        #expect(loaded)
        #expect(!viewModel.isRefreshing)
    }

    @Test func pullToRefresh_recoversFromAStalledCatchUp_andStillWorksAfterwards() async {
        let sessions = [
            Session(id: "1", title: "Alpha", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 1),
        ]
        let sessionRepository = FakeSessionRepository(sessions: sessions)
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"]),
            invalidationAckTimeoutMs: 400
        )
        await viewModel.refresh()
        let loaded = await waitUntil {
            if case let .loaded(sections, _, _, _) = viewModel.state {
                return sections.first?.allSessions.isEmpty == false
            }
            return false
        }
        #expect(loaded)

        sessionRepository.holdNextListSessions()
        let refreshTask = Task { await viewModel.pullToRefresh() }
        let blocked = await waitUntil(timeoutMs: 5_000) {
            viewModel.isRefreshing && sessionRepository.isListBlocked
        }
        #expect(blocked)

        let released = await waitUntil(timeoutMs: 5_000) { !viewModel.isRefreshing }
        #expect(
            released,
            "the spinner must drop once the ack ceiling passes; a never-acked invalidate leaves isRefreshing latched true forever"
        )
        refreshTask.cancel()

        sessionRepository.resumeListSessions()
        let settled = await waitUntil(timeoutMs: 5_000) { !sessionRepository.isListBlocked }
        #expect(settled)
        let callsBefore = sessionRepository.listCallCount
        await viewModel.pullToRefresh()
        let refetched = await waitUntil(timeoutMs: 5_000) {
            sessionRepository.listCallCount > callsBefore
        }
        #expect(
            refetched,
            "a later pull-to-refresh must still reach the server; the isRefreshing guard makes a lost ack a permanent no-op"
        )
        #expect(!viewModel.isRefreshing)
    }

    @Test func refresh_probeFailed() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(shouldFailProbe: true)
        )
        await viewModel.refresh()
        guard case .probeFailed = viewModel.state else {
            Issue.record("Expected probeFailed")
            return
        }
    }

    @Test func refresh_staysEmpty_whenNoAddedProjects() async {
        let store = FakeConnectionProfileStore()
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(bootWorktree: "/boot"),
            connectionProfileStore: store
        )
        await viewModel.refresh()
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/").isEmpty)
        guard case .empty = viewModel.state else {
            Issue.record("Expected empty without auto-seeded projects")
            return
        }
    }

    @Test func refresh_loadsAllSessions() async {
        let sessions = (1 ... 7).map {
            Session(id: "\($0)", title: "S\($0)", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: Int64($0))
        }
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: sessions),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        // Disk-first paints empty cache immediately; wait for live SWR to fill sections.
        let populated = await waitUntil {
            if case let .loaded(sections, _, _, _) = viewModel.state {
                return sections.first?.allSessions.count == 7
            }
            return false
        }
        #expect(populated)
        guard case let .loaded(sections, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.first?.allSessions.count == 7)
    }

    @Test func refresh_excludesArchivedSessionsFromActiveLists() async {
        let sessions = [
            Session(id: "active", title: "Active", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 2),
            Session(
                id: "archived",
                title: "Archived",
                slug: nil,
                projectID: nil,
                directory: "/repo",
                parentID: nil,
                updatedAtEpochMs: 3,
                archivedAtEpochMs: 1
            ),
        ]
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: sessions),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        let populated = await waitUntil {
            if case let .loaded(sections, _, _, _) = viewModel.state {
                return sections.first?.allSessions.map(\.id) == ["active"]
            }
            return false
        }
        #expect(populated)
        guard case let .loaded(sections, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.first?.allSessions.map(\.id) == ["active"])
        #expect(sections.first?.allSessions.count == 1)
    }

    @Test func refresh_preservesAddedWorktreeOrder() async {
        let viewModel = makeViewModel(
            projectRepository: SlowFirstProjectRepository(),
            sessionRepository: SlowFirstSessionRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/slow", "/fast"])
        )
        await viewModel.refresh()
        guard case let .loaded(sections, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.map(\.worktree) == ["/slow", "/fast"])
    }

    @Test func addProject_addsNewWorktree() async {
        let store = FakeConnectionProfileStore(initial: ["/one"])
        let projectRepository = FakeProjectRepository(projects: [
            Project(id: "two", worktree: "/two", displayName: "two"),
        ])
        let viewModel = makeViewModel(
            projectRepository: projectRepository,
            connectionProfileStore: store
        )
        await viewModel.refresh()
        await viewModel.addProject(worktree: "/two")
        guard case let .loaded(sections, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.map(\.worktree).contains("/two"))
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/").contains("/two"))
        #expect(viewModel.pendingSelectedFilterId == "/two")
        #expect(viewModel.pendingSelectedFilterOverridesAll)
        viewModel.consumePendingSelectedFilter()
        #expect(!viewModel.pendingSelectedFilterOverridesAll)
    }

    @Test func addProject_alreadyAdded_selectsWorktree() async {
        let store = FakeConnectionProfileStore(initial: ["/one", "/two"])
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: store
        )
        await viewModel.refresh()
        await viewModel.addProject(worktree: "/two")
        #expect(viewModel.pendingSelectedFilterId == "/two")
        // Already on home: the scope only moves when a single project is shown, not from All.
        #expect(!viewModel.pendingSelectedFilterOverridesAll)
        #expect(viewModel.projectLoadStates["/two"] == nil)
        #expect(viewModel.removalSnackbar?.text == "\"two\" is already on home")
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/one", "/two"])
    }

    @Test func reorderProjects_reordersSectionsAndPersistsOrder() async {
        let store = FakeConnectionProfileStore(initial: ["/one", "/two", "/three"])
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: store
        )
        await viewModel.refresh()

        viewModel.reorderProjects(worktrees: ["/three", "/one", "/two"])

        guard case let .loaded(sections, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.map(\.worktree) == ["/three", "/one", "/two"])
        await viewModel.projectOrderPersistence?.value
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/three", "/one", "/two"])
    }

    @Test func reorderProjects_ignoresOrdersThatChangeTheProjectSet() async {
        let store = FakeConnectionProfileStore(initial: ["/one", "/two"])
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: store
        )
        await viewModel.refresh()

        viewModel.reorderProjects(worktrees: ["/two"])
        viewModel.reorderProjects(worktrees: ["/two", "/one", "/extra"])
        viewModel.reorderProjects(worktrees: ["/one", "/two"])

        guard case let .loaded(sections, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.map(\.worktree) == ["/one", "/two"])
        #expect(viewModel.projectOrderPersistence == nil)
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/one", "/two"])
    }

    @Test func requestRemoveProject_setsPendingRemoval() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one", "/two"])
        )
        await viewModel.refresh()
        viewModel.requestRemoveProject(worktree: "/one")
        #expect(viewModel.pendingProjectRemoval?.worktree == "/one")
        #expect(viewModel.pendingProjectRemoval?.displayName == "one")
    }

    @Test func dismissProjectRemoval_clearsPending() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        viewModel.requestRemoveProject(worktree: "/one")
        viewModel.dismissProjectRemoval()
        #expect(viewModel.pendingProjectRemoval == nil)
    }

    @Test func confirmRemoveProject_dropsWorktreeAndShowsSnackbar() async {
        let store = FakeConnectionProfileStore(initial: ["/one", "/two"])
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: store
        )
        await viewModel.refresh()
        viewModel.requestRemoveProject(worktree: "/one")
        await viewModel.confirmRemoveProject()

        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/two"])
        #expect(viewModel.pendingProjectRemoval == nil)
        guard case let .loaded(sections, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.map(\.worktree) == ["/two"])
        #expect(viewModel.removalSnackbar?.text == "Removed \"one\" from home")

        viewModel.consumeProjectRemovalSnackbar()
        #expect(viewModel.removalSnackbar == nil)
    }

    @Test func worktreesOfOneRepositoryShowAsOneProject() async {
        let store = FakeConnectionProfileStore(initial: ["/repo", "/other", "/repo-wt"])
        let repo = Project(id: "prj_repo", worktree: "/repo", displayName: "repo", isRepository: true)
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(
                projectsByDirectory: ["/repo": repo, "/repo-wt": repo]
            ),
            connectionProfileStore: store
        )
        await viewModel.refresh()

        let merged = await waitUntil { viewModel.homeProjects.count == 2 }
        #expect(merged)
        #expect(viewModel.homeProjects.map(\.key) == ["prj_repo", "prj:/other"])
        #expect(viewModel.homeProjects.first?.memberPaths == ["/repo", "/repo-wt"])
        #expect(viewModel.homeProjects.first?.worktrees.map(\.directory) == ["/repo", "/repo-wt"])
    }

    @Test func branchesOfWorktreesFoundAfterTheRefreshShowWithoutAPullToRefresh() async {
        // v2: the first inventory knows only the main checkout; the next one (after
        // POST /api/worktree/refresh) adds two worktrees while branch loads are still in flight.
        let worktrees = ScriptedWorktreeRepository(loads: [
            .loaded(directories: ["/repo"], listsSessions: false),
            .loaded(directories: ["/repo", "/wt/tax", "/wt/export"], listsSessions: false),
        ])
        let vcs = GatedVcs(
            branches: ["/repo": "main", "/wt/tax": "feature/tax-rounding", "/wt/export": "feature/export"],
            // The first ask for the main checkout is cancelled when the REST pool is replaced.
            failFirstCallFor: ["/repo"]
        )
        let sessionRepository = FakeSessionRepository(sessions: [
            Session(id: "s-tax", title: "Tax", slug: nil, projectID: nil, directory: "/wt/tax", parentID: nil, updatedAtEpochMs: 2),
            Session(id: "s-main", title: "Main", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 1),
        ])
        sessionRepository.vcsInfo = { directory in try await vcs.info(directory) }
        let repo = Project(id: "prj_repo", worktree: "/repo", displayName: "repo", isRepository: true)
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(projectsByDirectory: ["/repo": repo]),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"]),
            worktreeRepository: worktrees,
            branchRetryDelays: [.zero]
        )

        await viewModel.refresh()
        #expect(await waitUntil { vcs.waitingDirectories == ["/repo"] })
        // Home reloads (the inventory grows) while the first branch load is still waiting.
        await viewModel.refresh()
        #expect(await waitUntil { viewModel.homeProjects.first?.worktrees.count == 3 })
        #expect(await waitUntil { Set(vcs.waitingDirectories) == ["/repo", "/wt/tax", "/wt/export"] })
        vcs.open()

        let shown = await waitUntil {
            viewModel.homeProjects.first?.worktrees.map(\.branch) == ["main", "feature/export", "feature/tax-rounding"]
        }
        #expect(shown)
        guard case let .loaded(sections, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        let rows = sections.crossProjectSessionListSessions(projects: viewModel.homeProjects)
        #expect(rows.first { $0.id == "s-tax" }?.scopedLocation?.parts == [.branch("feature/tax-rounding"), .text("tax")])
        #expect(rows.first { $0.id == "s-main" }?.scopedLocation?.parts == [.branch("main")])
    }

    @Test func removingAMergedProjectRemovesEveryMemberPath() async {
        let store = FakeConnectionProfileStore(initial: ["/repo", "/other", "/repo-wt"])
        let repo = Project(id: "prj_repo", worktree: "/repo", displayName: "repo", isRepository: true)
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(projectsByDirectory: ["/repo": repo, "/repo-wt": repo]),
            connectionProfileStore: store
        )
        await viewModel.refresh()
        #expect(await waitUntil { viewModel.homeProjects.count == 2 })

        viewModel.requestRemoveProject(worktree: "prj_repo")
        #expect(viewModel.pendingProjectRemoval?.worktrees == ["/repo", "/repo-wt"])
        await viewModel.confirmRemoveProject()

        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/other"])
        #expect(viewModel.removalSnackbar?.text == "Removed \"repo\" from home")
    }

    @Test func addingAWorktreeOfAnAddedRepositoryJoinsIt() async {
        let store = FakeConnectionProfileStore(initial: ["/repo"])
        let repo = Project(id: "prj_repo", worktree: "/repo", displayName: "repo", isRepository: true)
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(projectsByDirectory: ["/repo": repo, "/repo-wt": repo]),
            connectionProfileStore: store
        )
        await viewModel.refresh()
        #expect(await waitUntil { viewModel.homeProjects.first?.key == "prj_repo" })

        await viewModel.addProject(worktree: "/repo-wt")

        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/repo", "/repo-wt"])
        #expect(viewModel.removalSnackbar?.text == "Same repo as repo. Showing it as a worktree there.")
        #expect(viewModel.homeProjects.map(\.key) == ["prj_repo"])
        #expect(viewModel.homeProjects.first?.memberPaths == ["/repo", "/repo-wt"])
    }

    @Test func addingAnotherGlobalFolderStaysSeparate() async {
        let store = FakeConnectionProfileStore(initial: ["/notes"])
        let global = Project(id: "global", worktree: "/", displayName: "/")
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(projectsByDirectory: ["/notes": global, "/scratch": global]),
            connectionProfileStore: store
        )
        await viewModel.refresh()

        await viewModel.addProject(worktree: "/scratch")

        #expect(viewModel.removalSnackbar == nil)
        #expect(await waitUntil { viewModel.homeProjects.map(\.key) == ["path:/notes", "path:/scratch"] })
    }

    @Test func confirmRemoveProject_lastProject_showsEmpty() async {
        let store = FakeConnectionProfileStore(initial: ["/one"])
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: store
        )
        await viewModel.refresh()
        viewModel.requestRemoveProject(worktree: "/one")
        await viewModel.confirmRemoveProject()

        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/").isEmpty)
        guard case .empty = viewModel.state else {
            Issue.record("Expected empty")
            return
        }
        #expect(viewModel.removalSnackbar?.text == "Removed \"one\" from home")
    }

    @Test func showAddProjectSheet_opensAtHomeWithSuggestions() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/Users/me", directory: "/Users/me", worktree: "/"),
            listings: [
                "/Users/me": [
                    folder("/Users/me/code"),
                    DirectoryNode(name: ".cache", absolutePath: "/Users/me/.cache", isDirectory: true),
                    DirectoryNode(name: "readme", absolutePath: "/Users/me/readme", isDirectory: false),
                ],
                "/Volumes": [folder("/Volumes/Backup"), folder("/Volumes/.timemachine")],
            ]
        )
        let projectRepository = FakeProjectRepository(projects: [
            Project(id: "global", worktree: "/", displayName: "/"),
            Project(id: "old", worktree: "/Users/me/web", displayName: "web", activityAtEpochMs: 1, isRepository: true),
            Project(id: "app", worktree: "/Users/me/app", displayName: "app", activityAtEpochMs: 2, isRepository: true),
            Project(id: "one", worktree: "/one", displayName: "one", activityAtEpochMs: 3, isRepository: true),
        ])
        let viewModel = makeViewModel(
            projectRepository: projectRepository,
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        guard case let .loaded(_, show, payload, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        let sheet = viewModel.addProjectSheet
        #expect(show)
        #expect(payload == sheet)
        #expect(sheet.fieldText == "/Users/me/")
        #expect(sheet.directory == "/Users/me")
        #expect(sheet.filter == "")
        #expect(sheet.entries.map(\.name) == ["code"])
        #expect(sheet.truncationHint == nil)
        #expect(sheet.upTarget == "/Users")
        #expect(sheet.addTarget == "/Users/me")
        #expect(sheet.addTitle == AddProjectSheetState.homeFolderTitle)
        #expect(sheet.addEnabled)
        #expect(!sheet.isBrowsing)
        #expect(sheet.showSuggestions)
        #expect(sheet.suggestions.map(\.path) == ["/Users/me/app", "/Users/me/web"])
        #expect(sheet.suggestions.first?.activityLabel != nil)
        #expect(sheet.folderSectionTitle == "In me")
        #expect(sheet.roots.map(\.label) == ["Home", "/", "Backup"])
        #expect(sheet.roots.map(\.path) == ["/Users/me", "/", "/Volumes/Backup"])
        #expect(sheet.roots.map(\.selected) == [true, false, false])
    }

    @Test func browseAddProjectFolders_opensTheBrowserAndBackReturnsToSuggestions() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(projects: []),
            filesystemRepository: FakeFilesystemRepository(
                paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
                listings: ["/home": [folder("/home/code")]]
            )
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()

        viewModel.browseAddProjectFolders()
        #expect(viewModel.isAddProjectPresented)
        #expect(viewModel.addProjectSheet.isBrowsing)
        #expect(viewModel.addProjectSheet.fieldText == "/home/")

        viewModel.closeAddProjectBrowser()
        #expect(viewModel.isAddProjectPresented)
        #expect(!viewModel.addProjectSheet.isBrowsing)
        #expect(viewModel.addProjectSheet.fieldText == "/home/")
    }

    @Test func closeAddProjectBrowser_afterEnteringAFolder_restoresHomeSuggestions() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(projects: [
                Project(id: "web", worktree: "/Users/me/web", displayName: "web", activityAtEpochMs: 5, isRepository: true),
            ]),
            filesystemRepository: FakeFilesystemRepository(
                paths: ServerPaths(home: "/Users/me", directory: "/Users/me", worktree: "/"),
                listings: [
                    "/Users/me": [folder("/Users/me/code")],
                    "/Users/me/code": [folder("/Users/me/code/app")],
                ]
            ),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        #expect(viewModel.addProjectSheet.showSuggestions)

        viewModel.browseAddProjectFolders()
        await viewModel.openAddProjectFolder(path: "/Users/me/code")
        #expect(viewModel.addProjectSheet.directory == "/Users/me/code")
        #expect(!viewModel.addProjectSheet.showSuggestions)

        viewModel.closeAddProjectBrowser()
        #expect(viewModel.isAddProjectPresented)
        #expect(!viewModel.addProjectSheet.isBrowsing)
        #expect(viewModel.addProjectSheet.fieldText == "/Users/me/")
        #expect(viewModel.addProjectSheet.directory == "/Users/me")
        #expect(viewModel.addProjectSheet.showSuggestions)
        #expect(viewModel.addProjectSheet.suggestions.map(\.path) == ["/Users/me/web"])
    }

    @Test func addProjectField_filtersLocallyWithoutRefetching() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: [
                "/home": [folder("/home/code"), folder("/home/Codex"), folder("/home/docs")],
            ]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(projects: [
                Project(id: "app", worktree: "/home/app", displayName: "app", activityAtEpochMs: 1, isRepository: true),
            ]),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()

        viewModel.updateAddProjectField("/home/co")
        var sheet = viewModel.addProjectSheet
        #expect(sheet.directory == "/home")
        #expect(sheet.filter == "co")
        #expect(sheet.entries.map(\.name) == ["code", "Codex"])
        #expect(sheet.showSuggestions == false)
        #expect(sheet.folderSectionTitle == nil)
        #expect(sheet.upTarget == "/home")
        #expect(sheet.addTarget == nil)
        #expect(sheet.addTitle == AddProjectSheetState.pickFolderTitle)
        #expect(sheet.addEnabled == false)
        #expect(sheet.roots.allSatisfy { !$0.selected })

        viewModel.updateAddProjectField("/home/zz")
        #expect(viewModel.addProjectSheet.entries.isEmpty)
        #expect(viewModel.addProjectSheet.emptyMessage == "Nothing in home starts with \u{201C}zz\u{201D}")

        viewModel.updateAddProjectField("/home/Code")
        #expect(viewModel.addProjectSheet.addTarget == nil)

        viewModel.updateAddProjectField("/home/code")
        sheet = viewModel.addProjectSheet
        #expect(sheet.addTarget == "/home/code")
        #expect(sheet.addTitle == AddProjectSheetState.addThisFolderTitle)
        #expect(sheet.addEnabled)
        #expect(filesystem.requestedPaths.filter { $0 == "/home" }.count == 1)
    }

    @Test func addProjectField_filtersTheFullListingBeforeTheCap() async {
        let folders = (0 ..< 250).map { folder(String(format: "/home/f%03d", $0)) }
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: [
                "/home": folders.reversed() + [folder("/home/.config"), folder("/home/.cache")],
            ]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()

        var sheet = viewModel.addProjectSheet
        #expect(sheet.entries.count == DirectoryListingPolicy.maxVisibleDirectories)
        #expect(sheet.entries.first?.name == "f000")
        #expect(sheet.entries.last?.name == "f199")
        #expect(sheet.truncationHint == "Showing the first 200 folders. Type more of the name to narrow the list.")
        #expect(sheet.truncationHint == AddProjectSheetState.truncatedListingHint)
        #expect(sheet.emptyMessage == nil)

        viewModel.updateAddProjectField("/home/f24")
        sheet = viewModel.addProjectSheet
        #expect(sheet.entries.map(\.name) == (240 ... 249).map { "f\($0)" })
        #expect(sheet.truncationHint == nil)
        #expect(sheet.emptyMessage == nil)
        #expect(sheet.addTarget == nil)
        #expect(sheet.addEnabled == false)

        viewModel.updateAddProjectField("/home/f249")
        sheet = viewModel.addProjectSheet
        #expect(sheet.entries.map(\.name) == ["f249"])
        #expect(sheet.addTarget == "/home/f249")
        #expect(sheet.addTitle == AddProjectSheetState.addThisFolderTitle)
        #expect(sheet.addEnabled)

        viewModel.updateAddProjectField("/home/.")
        sheet = viewModel.addProjectSheet
        #expect(sheet.directory == "/home")
        #expect(sheet.filter == ".")
        #expect(sheet.entries.map(\.name) == [".cache", ".config"])
        #expect(sheet.addTarget == nil)

        viewModel.updateAddProjectField("/home/.config")
        sheet = viewModel.addProjectSheet
        #expect(sheet.entries.map(\.name) == [".config"])
        #expect(sheet.addTarget == "/home/.config")
        #expect(sheet.addTitle == AddProjectSheetState.addThisFolderTitle)
        #expect(sheet.addEnabled)

        viewModel.updateAddProjectField("/home/f3")
        sheet = viewModel.addProjectSheet
        #expect(sheet.entries.isEmpty)
        #expect(sheet.truncationHint == nil)
        #expect(sheet.emptyMessage == "Nothing in home starts with \u{201C}f3\u{201D}")
        #expect(filesystem.requestedPaths.filter { $0 == "/home" }.count == 1)
    }

    @Test func addProjectField_listsANewFolderAfterThePause() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: [
                "/home": [folder("/home/code")],
                "/home/code": [folder("/home/code/app")],
            ]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()

        viewModel.updateAddProjectField("/home/code/")
        #expect(viewModel.addProjectSheet.isLoading)
        let listed = await waitUntil { viewModel.addProjectSheet.entries.map(\.name) == ["app"] }
        #expect(listed)
        #expect(viewModel.addProjectSheet.isLoading == false)

        viewModel.updateAddProjectField("/home/")
        #expect(viewModel.addProjectSheet.entries.map(\.name) == ["code"])
        #expect(viewModel.addProjectSheet.isLoading == false)
        #expect(filesystem.requestedPaths.filter { $0 == "/home" }.count == 1)
    }

    @Test func openAddProjectFolder_listsTheTappedFolder() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: [
                "/home": [folder("/home/code")],
                "/home/code": [],
            ]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        await viewModel.openAddProjectFolder(path: "/home/code")
        let sheet = viewModel.addProjectSheet
        #expect(sheet.fieldText == "/home/code/")
        #expect(sheet.directory == "/home/code")
        #expect(sheet.entries.isEmpty)
        #expect(sheet.emptyMessage == AddProjectSheetState.noSubfoldersMessage)
        #expect(sheet.addTarget == "/home/code")
        #expect(sheet.addTitle == AddProjectSheetState.addThisFolderTitle)
        #expect(sheet.upTarget == "/home")
    }

    @Test func addProjectUp_goesFromHomeToRootAndStops() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: ["/home": [folder("/home/code")], "/": [folder("/home")]]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()

        viewModel.updateAddProjectField("/home/co")
        await viewModel.addProjectUp()
        #expect(viewModel.addProjectSheet.fieldText == "/home/")

        await viewModel.addProjectUp()
        var sheet = viewModel.addProjectSheet
        #expect(sheet.fieldText == "/")
        #expect(sheet.directory == "/")
        #expect(sheet.entries.map(\.name) == ["home"])
        #expect(sheet.upTarget == nil)
        #expect(sheet.roots.first { $0.path == "/" }?.selected == true)

        await viewModel.addProjectUp()
        sheet = viewModel.addProjectSheet
        #expect(sheet.fieldText == "/")
        #expect(sheet.directory == "/")
    }

    @Test func windowsHome_buildsDriveChipsAndGoesUpToTheDriveRoot() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: #"C:\Users\me"#, directory: #"C:\Users\me"#, worktree: "/"),
            listings: [
                #"C:\Users\me"#: [],
                #"C:\Users"#: [folder(#"C:\Users\me"#)],
                #"C:\"#: [folder(#"C:\Users"#)],
            ]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(projects: [
                Project(id: "work", worktree: #"D:\work\app"#, displayName: "app", activityAtEpochMs: 1, isRepository: true),
                Project(id: "here", worktree: #"C:\Users\me\here"#, displayName: "here"),
            ]),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: [#"E:\added"#])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        var sheet = viewModel.addProjectSheet
        #expect(sheet.fieldText == #"C:\Users\me\"#)
        #expect(sheet.roots.map(\.label) == ["Home", "C:", "D:", "E:"])
        #expect(sheet.roots.map(\.path) == [#"C:\Users\me"#, #"C:\"#, #"D:\"#, #"E:\"#])
        #expect(filesystem.requestedPaths.contains("/Volumes") == false)

        await viewModel.addProjectUp()
        await viewModel.addProjectUp()
        sheet = viewModel.addProjectSheet
        #expect(sheet.fieldText == #"C:\"#)
        #expect(sheet.directory == #"C:\"#)
        #expect(sheet.entries.map(\.name) == ["Users"])
        #expect(sheet.upTarget == nil)
        #expect(sheet.roots.first { $0.label == "C:" }?.selected == true)
    }

    @Test func windowsField_typedDriveListsItAndAddsAChip() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: #"C:\Users\me"#, directory: #"C:\Users\me"#, worktree: "/"),
            listings: [
                #"C:\Users\me"#: [],
                #"D:\"#: [folder(#"D:\code"#)],
            ]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        #expect(viewModel.addProjectSheet.roots.map(\.label) == ["Home", "C:"])

        viewModel.updateAddProjectField("d:")
        let listed = await waitUntil { viewModel.addProjectSheet.entries.map(\.name) == ["code"] }
        #expect(listed)
        var sheet = viewModel.addProjectSheet
        #expect(sheet.directory == #"D:\"#)
        #expect(sheet.upTarget == nil)
        #expect(sheet.roots.map(\.label) == ["Home", "C:", "D:"])
        #expect(sheet.roots.last?.selected == true)

        viewModel.updateAddProjectField(#"D:\CODE"#)
        sheet = viewModel.addProjectSheet
        #expect(sheet.addTarget == #"D:\code"#)
        #expect(sheet.addTitle == AddProjectSheetState.addThisFolderTitle)

        await viewModel.selectAddProjectRoot(path: #"C:\Users\me"#)
        #expect(viewModel.addProjectSheet.fieldText == #"C:\Users\me\"#)
        #expect(viewModel.addProjectSheet.roots.first?.selected == true)
    }

    @Test func windowsField_unlistableDriveDoesNotBecomeAChip() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: #"c:\Users\me"#, directory: #"c:\Users\me"#, worktree: "/"),
            listings: [#"C:\Users\me"#: []],
            failingPaths: [#"X:\"#]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(projects: [
                Project(id: "app", worktree: #"C:\Users\me\app"#, displayName: "app", activityAtEpochMs: 1, isRepository: true),
            ]),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        var sheet = viewModel.addProjectSheet
        #expect(sheet.homePath == #"C:\Users\me"#)
        #expect(sheet.fieldText == #"C:\Users\me\"#)
        #expect(sheet.roots.first?.selected == true)
        #expect(sheet.showSuggestions)

        viewModel.updateAddProjectField("X:")
        let failed = await waitUntil { viewModel.addProjectSheet.listError != nil }
        #expect(failed)
        sheet = viewModel.addProjectSheet
        #expect(sheet.listError == AddProjectSheetState.listErrorMessage)
        #expect(sheet.roots.map(\.label) == ["Home", "C:"])
    }

    @Test func addTarget_alreadyOnHomeIsDisabled() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: ["/home": [folder("/home/app"), folder("/home/web")]]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(projects: [
                Project(id: "app", worktree: "/home/app", displayName: "app", activityAtEpochMs: 1, isRepository: true),
            ]),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/home/app"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        var sheet = viewModel.addProjectSheet
        #expect(sheet.entries.map(\.isAdded) == [true, false])
        #expect(sheet.suggestions.isEmpty)
        #expect(sheet.showSuggestions == false)

        viewModel.updateAddProjectField("/home/app")
        sheet = viewModel.addProjectSheet
        #expect(sheet.addTarget == "/home/app")
        #expect(sheet.addTitle == AddProjectSheetState.alreadyAddedTitle)
        #expect(sheet.addEnabled == false)
        await viewModel.confirmAddProject()
        #expect(viewModel.isAddProjectPresented)
    }

    @Test func addProjectRows_loadCountsAndGitTagsWhenTheyAppear() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: [
                "/home": [folder("/home/code"), folder("/home/empty"), folder("/home/web")],
                "/home/code": [folder("/home/code/a"), folder("/home/code/b"), folder("/home/code/.git")],
                "/home/web": [folder("/home/web/src")],
            ],
            gitPaths: ["/home/code"]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(projects: [
                Project(id: "web", worktree: "/home/web", displayName: "web", activityAtEpochMs: 1, isRepository: true),
            ]),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/home/code"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        viewModel.browseAddProjectFolders()

        var rows = viewModel.addProjectSheet.entries
        #expect(rows.map(\.subtitle) == ["On Home", nil, nil])
        // A server project tracked by git shows its tag before its listing loads.
        #expect(rows.map(\.isGitRepository) == [false, false, true])

        for row in rows { viewModel.addProjectFolderRowAppeared(path: row.path) }
        #expect(await waitUntil { viewModel.addProjectSheet.entries.allSatisfy { $0.folderCount != nil } })
        rows = viewModel.addProjectSheet.entries
        #expect(rows.map(\.folderCount) == [2, 0, 1])
        #expect(rows.map(\.subtitle) == ["On Home \u{00B7} 2 folders", "Empty", "1 folder"])
        #expect(rows.map(\.isGitRepository) == [true, false, true])

        // Seen again, or entered, the folder comes from the cache.
        viewModel.addProjectFolderRowAppeared(path: "/home/code")
        await viewModel.openAddProjectFolder(path: "/home/code")
        #expect(viewModel.addProjectSheet.entries.map(\.name) == ["a", "b"])
        #expect(viewModel.addProjectSheet.barTarget == AddProjectBarTarget(name: "code", path: "/home/code", isGitRepository: true))
        #expect(filesystem.requestedPaths.filter { $0 == "/home/code" }.count == 1)
    }

    @Test func addProjectRows_loadAtMostFourAtOnceAndSkipRowsThatLeft() async {
        let names = (1 ... 6).map { "/home/f\($0)" }
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: ["/home": names.map(folder)]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        viewModel.browseAddProjectFolders()
        filesystem.heldPaths = Set(names)

        for path in names { viewModel.addProjectFolderRowAppeared(path: path) }
        #expect(await waitUntil { filesystem.heldCount == 4 })
        #expect(filesystem.requestedPaths.filter(names.contains) == Array(names.prefix(4)))

        viewModel.addProjectFolderRowDisappeared(path: "/home/f5")
        filesystem.release("/home/f1")
        #expect(await waitUntil { filesystem.requestedPaths.contains("/home/f6") })
        for path in names { filesystem.release(path) }
        #expect(await waitUntil { viewModel.addProjectSheet.entries.filter { $0.folderCount != nil }.count == 5 })
        #expect(!filesystem.requestedPaths.contains("/home/f5"))
        #expect(filesystem.maxInFlight == AddProjectSheetState.folderSummaryConcurrency)
    }

    @Test func addProjectRows_failedListingIsNotRetriedOrShownAsAnError() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: ["/home": [folder("/home/locked")]],
            failingPaths: ["/home/locked"]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        viewModel.browseAddProjectFolders()

        viewModel.addProjectFolderRowAppeared(path: "/home/locked")
        #expect(await waitUntil { filesystem.requestedPaths.contains("/home/locked") })
        try? await Task.sleep(for: .milliseconds(20))
        viewModel.addProjectFolderRowAppeared(path: "/home/locked")
        #expect(viewModel.addProjectSheet.entries.first?.subtitle == nil)
        #expect(viewModel.addProjectSheet.listError == nil)
        #expect(filesystem.requestedPaths.filter { $0 == "/home/locked" }.count == 1)

        // Entering the folder still asks the server, and its failure shows with Retry.
        await viewModel.openAddProjectFolder(path: "/home/locked")
        #expect(filesystem.requestedPaths.filter { $0 == "/home/locked" }.count == 2)
        #expect(viewModel.addProjectSheet.listError == AddProjectSheetState.listErrorMessage)
        #expect(!viewModel.addProjectSheet.pathNotFound)
    }

    @Test func submitAddProjectField_opensTheTypedOrOnlyMatchingFolderAndNeverAdds() async {
        let store = FakeConnectionProfileStore(initial: ["/one"])
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: [
                "/home": [folder("/home/code"), folder("/home/Codex"), folder("/home/docs")],
                "/home/code": [folder("/home/code/app")],
                "/home/docs": [],
            ]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            filesystemRepository: filesystem,
            connectionProfileStore: store
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        viewModel.browseAddProjectFolders()

        // No filter: Return has nothing to open.
        #expect(viewModel.addProjectSheet.goTarget == nil)
        await viewModel.submitAddProjectField()
        #expect(viewModel.addProjectSheet.fieldText == "/home/")

        // Several matches: Return stays put.
        viewModel.updateAddProjectField("/home/co")
        #expect(viewModel.addProjectSheet.goTarget == nil)
        await viewModel.submitAddProjectField()
        #expect(viewModel.addProjectSheet.fieldText == "/home/co")

        // The only match opens.
        viewModel.updateAddProjectField("/home/d")
        #expect(viewModel.addProjectSheet.goTarget == "/home/docs")
        await viewModel.submitAddProjectField()
        #expect(viewModel.addProjectSheet.fieldText == "/home/docs/")
        #expect(viewModel.addProjectSheet.directory == "/home/docs")

        // An exact name opens even when other names start with it.
        viewModel.updateAddProjectField("/home/code")
        #expect(viewModel.addProjectSheet.goTarget == "/home/code")
        await viewModel.submitAddProjectField()
        #expect(viewModel.addProjectSheet.directory == "/home/code")
        #expect(viewModel.addProjectSheet.entries.map(\.name) == ["app"])

        #expect(viewModel.isAddProjectPresented)
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/one"])
    }

    @Test func addProjectBar_namesTheTargetAndGivesTheDisabledReason() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: [
                "/home": [folder("/home/code"), folder("/home/Codex")],
                "/home/code": [],
            ]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        viewModel.browseAddProjectFolders()

        var sheet = viewModel.addProjectSheet
        #expect(sheet.barTarget == AddProjectBarTarget(name: "home", path: "/home", isGitRepository: false))
        #expect(sheet.addTitle == AddProjectSheetState.homeFolderTitle)
        #expect(sheet.addEnabled)

        viewModel.updateAddProjectField("/home/co")
        sheet = viewModel.addProjectSheet
        #expect(sheet.barTarget?.path == "/home")
        #expect(sheet.addTitle == "Pick a folder")
        #expect(!sheet.addEnabled)

        viewModel.updateAddProjectField("/home/zz")
        sheet = viewModel.addProjectSheet
        #expect(sheet.addTitle == "No folder named \u{201C}zz\u{201D}")
        #expect(sheet.emptyMessage == "Nothing in home starts with \u{201C}zz\u{201D}")
        #expect(!sheet.addEnabled)

        viewModel.updateAddProjectField("/home/code")
        sheet = viewModel.addProjectSheet
        #expect(sheet.barTarget == AddProjectBarTarget(name: "code", path: "/home/code", isGitRepository: false))
        #expect(sheet.addTitle == "Add this folder")
        #expect(sheet.addEnabled)

        // While a folder lists, the button keeps its title but waits.
        filesystem.heldPaths = ["/home/Codex"]
        viewModel.updateAddProjectField("/home/Codex/")
        #expect(viewModel.addProjectSheet.isLoading)
        #expect(viewModel.addProjectSheet.addTitle == "Add this folder")
        #expect(!viewModel.addProjectSheet.addEnabled)
        filesystem.release("/home/Codex")
    }

    @Test func pathNotFound_showsOneLineAndDisablesAdd() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: ["/home": [folder("/home/code")]],
            missingPaths: ["/srv/apps"]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        viewModel.browseAddProjectFolders()

        viewModel.updateAddProjectField("/srv/apps/")
        #expect(await waitUntil { viewModel.addProjectSheet.pathNotFound })
        var sheet = viewModel.addProjectSheet
        #expect(sheet.emptyMessage == "Path not found")
        #expect(sheet.listError == nil)
        #expect(sheet.isLoading == false)
        #expect(sheet.entries.isEmpty)
        #expect(sheet.addTitle == AddProjectSheetState.pathNotFoundTitle)
        #expect(!sheet.addEnabled)
        #expect(sheet.goTarget == nil)
        #expect(sheet.barTarget?.path == "/srv/apps")

        // Typing on inside the missing folder asks the server nothing new.
        viewModel.updateAddProjectField("/srv/apps/x")
        try? await Task.sleep(for: .milliseconds(20))
        sheet = viewModel.addProjectSheet
        #expect(sheet.pathNotFound)
        #expect(sheet.addTitle == AddProjectSheetState.pathNotFoundTitle)
        #expect(filesystem.requestedPaths.filter { $0 == "/srv/apps" }.count == 1)
    }

    @Test func confirmAddProject_validatesAndFocuses() async {
        let store = FakeConnectionProfileStore(initial: ["/one"])
        let projectRepository = FakeProjectRepository()
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: ["/home": []]
        )
        let viewModel = makeViewModel(
            projectRepository: projectRepository,
            filesystemRepository: filesystem,
            connectionProfileStore: store
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        await viewModel.confirmAddProject()
        guard case let .loaded(_, show, _, createError) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(show == false)
        #expect(viewModel.isAddProjectPresented == false)
        #expect(createError == nil)
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/").contains("/home"))
        #expect(projectRepository.validatedDirectories.contains("/home"))
        #expect(viewModel.pendingSelectedFilterId == "/home")
    }

    @Test func confirmAddProject_persistsSelectedPathEvenWhenCurrentResolvesGlobal() async {
        let store = FakeConnectionProfileStore(initial: ["/one"])
        let projectRepository = FakeProjectRepository(
            projectsByDirectory: [
                "/home": Project(id: "global", worktree: "/", displayName: "/"),
            ]
        )
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: ["/home": []]
        )
        let viewModel = makeViewModel(
            projectRepository: projectRepository,
            filesystemRepository: filesystem,
            connectionProfileStore: store
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        await viewModel.confirmAddProject()
        guard case let .loaded(_, show, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(show == false)
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/one", "/home"])
    }

    @Test func confirmAddProject_nestedPathPersistsSelectedDirectory() async {
        let store = FakeConnectionProfileStore(initial: ["/one"])
        let projectRepository = FakeProjectRepository(
            projectsByDirectory: [
                "/repo/android": Project(id: "repo", worktree: "/repo", displayName: "repo"),
            ]
        )
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/repo", directory: "/repo", worktree: "/"),
            listings: ["/repo": [folder("/repo/android")]]
        )
        let viewModel = makeViewModel(
            projectRepository: projectRepository,
            filesystemRepository: filesystem,
            connectionProfileStore: store
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        viewModel.updateAddProjectField("/repo/android")
        await viewModel.confirmAddProject()
        guard case let .loaded(_, show, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(show == false)
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/one", "/repo/android"])
        #expect(projectRepository.validatedDirectories.contains("/repo/android"))
    }

    @Test func confirmAddProject_validationErrorShowsOnTheField() async {
        let store = FakeConnectionProfileStore(initial: ["/one"])
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: ["/home": []]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(failingDirectories: ["/home"]),
            filesystemRepository: filesystem,
            connectionProfileStore: store
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        await viewModel.confirmAddProject()
        #expect(viewModel.isAddProjectPresented)
        #expect(viewModel.addProjectSheet.fieldError == "Not a project")
        #expect(viewModel.addProjectSheet.isValidating == false)
        #expect(viewModel.addProjectSheet.addEnabled)
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/one"])
        viewModel.updateAddProjectField("/home/x")
        #expect(viewModel.addProjectSheet.fieldError == nil)
    }

    @Test func selectAddProjectSuggestion_addsTheServerProject() async {
        let store = FakeConnectionProfileStore(initial: ["/one"])
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: ["/home": []]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(projects: [
                Project(id: "app", worktree: "/srv/app", displayName: "app", activityAtEpochMs: 1, isRepository: true),
            ]),
            filesystemRepository: filesystem,
            connectionProfileStore: store
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        #expect(viewModel.addProjectSheet.suggestions.map(\.name) == ["app"])
        await viewModel.selectAddProjectSuggestion(path: "/srv/app")
        #expect(viewModel.isAddProjectPresented == false)
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/one", "/srv/app"])
        #expect(viewModel.pendingSelectedFilterId == "/srv/app")
    }

    @Test func listingError_showsRetryAndRecovers() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: ["/home": [folder("/home/code")]],
            failingPaths: ["/home"]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        var sheet = viewModel.addProjectSheet
        #expect(sheet.listError == AddProjectSheetState.listErrorMessage)
        #expect(sheet.isLoading == false)
        #expect(sheet.addEnabled == false)

        viewModel.updateAddProjectField("/home/c")
        #expect(filesystem.requestedPaths.filter { $0 == "/home" }.count == 1)

        filesystem.failingPaths = []
        await viewModel.retryAddProjectListing()
        sheet = viewModel.addProjectSheet
        #expect(sheet.listError == nil)
        #expect(sheet.entries.map(\.name) == ["code"])
    }

    @Test func dismissAddProjectSheet_resetsTheSheet() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: ["/home": [folder("/home/code")]]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        viewModel.updateAddProjectField("/home/co")
        viewModel.dismissAddProjectSheet()
        #expect(viewModel.isAddProjectPresented == false)
        #expect(viewModel.addProjectSheet == .empty)
        guard case let .loaded(_, show, payload, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(show == false)
        #expect(payload == nil)

        await viewModel.showAddProjectSheet()
        #expect(viewModel.addProjectSheet.fieldText == "/home/")
    }

    @Test func createSession_usesFocusedWorktreeAndClearsError() async {
        let sessionRepository = FakeSessionRepository()
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        #expect(viewModel.revealNewestToken == 0)
        await viewModel.createSession(in: "/repo")
        #expect(sessionRepository.createdDirectories.isEmpty)
        #expect(viewModel.revealNewestToken == 1)
        #expect(viewModel.pendingSessionOpen?.id == OptimisticSessionCreate.placeholderSessionID)
        #expect(viewModel.pendingSessionOpen?.worktree == "/repo")
        #expect(viewModel.pendingSessionOpen?.optimisticNavigationInstanceID != nil)
        guard case let .loaded(_, _, _, createError) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(createError == nil)
    }

    @Test func createSession_assignsUniqueOptimisticNavigationInstanceIDs() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        await viewModel.createSession(in: "/repo")
        let first = viewModel.pendingSessionOpen?.optimisticNavigationInstanceID
        viewModel.consumePendingSessionOpen()
        await viewModel.createSession(in: "/repo")
        let second = viewModel.pendingSessionOpen?.optimisticNavigationInstanceID
        #expect(first != nil)
        #expect(second != nil)
        #expect(first != second)
    }

    @Test func homeReveal_afterStaleAbsenceIncrementsRevealToken() async {
        HomeDisplayPreferences.shared.setLastHomeHiddenAtEpochMs(nil)
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.markHomeHidden(nowEpochMs: 0)
        viewModel.onHomeRevealed(nowEpochMs: SessionListScrollPin.staleAfterMs)
        #expect(viewModel.revealNewestToken == 1)
    }

    @Test func homeReveal_beforeStaleAbsenceKeepsScroll() async {
        HomeDisplayPreferences.shared.setLastHomeHiddenAtEpochMs(nil)
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.markHomeHidden(nowEpochMs: 0)
        viewModel.onHomeRevealed(nowEpochMs: SessionListScrollPin.staleAfterMs - 1)
        #expect(viewModel.revealNewestToken == 0)
    }

    @Test func onSessionLongPress_opensSheetWithoutFetchingMessages() async {
        let messageRepository = FakeMessageRepository(messages: [userMessage(id: "m1", text: "Hello")])
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            messageRepository: messageRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")

        #expect(viewModel.sessionActionsSheetVisible)
        #expect(viewModel.sessionActionsTarget?.sessionId == "s1")
        #expect(viewModel.sessionActionsTarget?.title == "Alpha")
        let fetched = await waitUntil(timeoutMs: 200) { messageRepository.listCallCount > 0 }
        #expect(fetched == false)
    }

    @Test func openSessionFork_allowsForkWhenSessionHasVisibleUserMessage() async {
        let messageRepository = FakeMessageRepository(messages: [userMessage(id: "m1", text: "Hello")])
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            messageRepository: messageRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.openSessionFork()

        #expect(viewModel.sessionForkDialog.isVisible)
        #expect(await waitUntil { messageRepository.listCallCount == 1 })
        let blocked = await waitUntil(timeoutMs: 200) { viewModel.sessionForkDialog.blockedMessage != nil }
        #expect(blocked == false)
    }

    @Test func openSessionFork_blocksDialogWithoutUserMessages() async {
        let sessionRepository = FakeSessionRepository()
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.openSessionFork()

        #expect(await waitUntil { viewModel.sessionForkDialog.blockedMessage == "Nothing to fork yet" })
        #expect(viewModel.sessionForkDialog.isVisible)
        await viewModel.confirmSessionFork()
        #expect(sessionRepository.forkedIDs.isEmpty)
    }

    @Test func confirmSessionFork_beforeCheckFinishesWaitsAndBlocksEmptySession() async {
        let sessionRepository = FakeSessionRepository()
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.openSessionFork()
        await viewModel.confirmSessionFork()

        #expect(sessionRepository.forkedIDs.isEmpty)
        #expect(viewModel.sessionForkDialog.isSubmitting == false)
        #expect(viewModel.sessionForkDialog.blockedMessage == "Nothing to fork yet")
    }

    @Test func openSessionFork_checkFailureLeavesForkToServer() async {
        let sessionRepository = FakeSessionRepository()
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            messageRepository: FakeMessageRepository(listError: TestError.message("offline")),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.openSessionFork()
        await viewModel.confirmSessionFork()

        #expect(viewModel.sessionForkDialog.blockedMessage == nil)
        #expect(sessionRepository.forkedIDs == ["s1"])
    }

    @Test func dismissSessionActions_clearsTarget() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.dismissSessionActions()

        #expect(viewModel.sessionActionsSheetVisible == false)
        #expect(viewModel.sessionActionsTarget == nil)
    }

    @Test func confirmSessionRename_updatesSessionAndClosesDialog() async {
        let sessionRepository = FakeSessionRepository()
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.openSessionRename()
        #expect(viewModel.sessionRenameDialog.draftTitle == "Alpha")
        #expect(viewModel.sessionActionsSheetVisible == false)

        viewModel.updateSessionRenameDraft("Beta")
        await viewModel.confirmSessionRename()

        #expect(sessionRepository.updatedTitles.map(\.title) == ["Beta"])
        #expect(viewModel.sessionRenameDialog.isVisible == false)
        #expect(viewModel.sessionActionsTarget == nil)
    }

    @Test func confirmSessionRename_emptyTitleShowsDialogError() async {
        let sessionRepository = FakeSessionRepository()
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.openSessionRename()
        viewModel.updateSessionRenameDraft("   ")
        await viewModel.confirmSessionRename()

        #expect(sessionRepository.updatedTitles.isEmpty)
        #expect(viewModel.sessionRenameDialog.isVisible)
        #expect(viewModel.sessionRenameDialog.errorMessage == "Enter a session title")
    }

    @Test func confirmSessionRename_unchangedTitleClosesWithoutRequest() async {
        let sessionRepository = FakeSessionRepository()
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.openSessionRename()
        await viewModel.confirmSessionRename()

        #expect(sessionRepository.updatedTitles.isEmpty)
        #expect(viewModel.sessionRenameDialog.isVisible == false)
        #expect(viewModel.sessionActionsTarget == nil)
    }

    @Test func confirmSessionRename_failureKeepsDialogError() async {
        let sessionRepository = FakeSessionRepository(updateError: TestError.message("nope"))
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.openSessionRename()
        viewModel.updateSessionRenameDraft("Beta")
        await viewModel.confirmSessionRename()

        #expect(viewModel.sessionRenameDialog.isVisible)
        #expect(viewModel.sessionRenameDialog.isSubmitting == false)
        #expect(viewModel.sessionRenameDialog.errorMessage == "nope")
    }

    @Test func openSessionFork_suggestsForkTitle() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.openSessionFork()

        #expect(viewModel.sessionForkDialog.draftTitle == "Fork of Alpha")
        #expect(ProjectListHomeViewModel.suggestedForkTitle(for: "  ") == "Forked session")
    }

    @Test func confirmSessionFork_openChoiceQueuesPendingSessionOpen() async {
        let sessionRepository = FakeSessionRepository()
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            messageRepository: FakeMessageRepository(messages: [userMessage(id: "m1", text: "Hello")]),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.openSessionFork()
        await viewModel.confirmSessionFork()

        #expect(sessionRepository.forkedIDs == ["s1"])
        #expect(sessionRepository.updatedTitles.map(\.id) == ["forked"])
        #expect(viewModel.sessionForkResult.isVisible)
        #expect(viewModel.sessionForkResult.forkedTitle == "Fork of Alpha")

        viewModel.chooseSessionForkOpen()
        #expect(viewModel.pendingSessionOpen?.id == "forked")
        #expect(viewModel.pendingSessionOpen?.worktree == "/repo")
        #expect(viewModel.sessionForkResult.isVisible == false)
    }

    @Test func confirmSessionFork_stayChoiceKeepsHome() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            messageRepository: FakeMessageRepository(messages: [userMessage(id: "m1", text: "Hello")]),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.openSessionFork()
        await viewModel.confirmSessionFork()
        viewModel.chooseSessionForkStay()

        #expect(viewModel.pendingSessionOpen == nil)
        #expect(viewModel.sessionForkResult.isVisible == false)
        #expect(viewModel.sessionActionsTarget == nil)
    }

    @Test func confirmSessionFork_failureKeepsDialogError() async {
        let sessionRepository = FakeSessionRepository(forkError: TestError.message("no fork"))
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            messageRepository: FakeMessageRepository(messages: [userMessage(id: "m1", text: "Hello")]),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.openSessionFork()
        await viewModel.confirmSessionFork()

        #expect(viewModel.sessionForkDialog.isVisible)
        #expect(viewModel.sessionForkDialog.errorMessage == "no fork")
        #expect(viewModel.sessionForkResult.isVisible == false)
    }

    @Test func archiveSessionAction_archivesAndClearsTarget() async {
        let sessionRepository = FakeSessionRepository()
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        await viewModel.archiveSessionAction()

        #expect(sessionRepository.archivedIDs == ["s1"])
        #expect(viewModel.sessionActionsTarget == nil)
        #expect(viewModel.removalSnackbar == nil)
    }

    @Test func archiveSessionAction_failureShowsSnackbar() async {
        let sessionRepository = FakeSessionRepository(archiveError: TestError.message("boom"))
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        await viewModel.archiveSessionAction()

        #expect(viewModel.removalSnackbar?.text == "Couldn't archive session.")
        #expect(viewModel.sessionActionInFlight == false)
        #expect(viewModel.sessionActionsTarget == nil)
    }

    @Test func confirmDeleteSessionAction_deletesAfterConfirmation() async {
        let sessionRepository = FakeSessionRepository()
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.requestDeleteSessionAction()
        #expect(viewModel.sessionDeleteConfirmVisible)
        #expect(sessionRepository.deletedIDs.isEmpty)

        await viewModel.confirmDeleteSessionAction()

        #expect(sessionRepository.deletedIDs == ["s1"])
        #expect(viewModel.sessionDeleteConfirmVisible == false)
        #expect(viewModel.sessionActionsTarget == nil)
        #expect(viewModel.removalSnackbar == nil)
    }

    @Test func confirmDeleteSessionAction_failureShowsSnackbar() async {
        let sessionRepository = FakeSessionRepository(deleteError: TestError.message("boom"))
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")
        viewModel.requestDeleteSessionAction()
        await viewModel.confirmDeleteSessionAction()

        #expect(viewModel.removalSnackbar?.text == "Couldn't delete session.")
        #expect(viewModel.sessionActionsTarget == nil)
    }

    @Test func returningToHome_readyIdsBeforeStatuses_doesNotFlashReady() async {
        let sessions = [
            Session(id: "ses-1", title: "Alpha", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 1),
        ]
        let sessionLive = ControllableSessionLiveStore()
        let statusLive = ControllableStatusLiveStore()
        let readyLive = ControllableReadyStore()
        sessionLive.emit(directory: "/repo", sessions: sessions)
        readyLive.emit(directory: "/repo", ids: ["ses-1"])
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: sessions),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"]),
            sessionLiveStoreOverride: sessionLive,
            sessionStatusLiveStoreOverride: statusLive,
            sessionReadyStoreOverride: readyLive
        )

        await viewModel.refresh()
        var flashedReady = false
        let deadline = Date().addingTimeInterval(0.4)
        while Date() < deadline {
            if homeSession(viewModel, id: "ses-1")?.isReady == true,
               homeSession(viewModel, id: "ses-1")?.statusLabel == nil
            {
                flashedReady = true
                break
            }
            try? await Task.sleep(for: .milliseconds(10))
        }
        #expect(!flashedReady)

        statusLive.emit(
            directory: "/repo",
            statuses: ["ses-1": SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
        )
        let working = await waitUntil {
            homeSession(viewModel, id: "ses-1")?.statusLabel == "Working"
        }
        #expect(working)
    }

    @Test func refreshWhileLive_doesNotClobberWorkingStatus() async {
        let sessions = [
            Session(id: "ses-1", title: "Alpha", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 1),
        ]
        let sessionLive = ControllableSessionLiveStore()
        let statusLive = ControllableStatusLiveStore()
        let readyLive = ControllableReadyStore()
        sessionLive.emit(directory: "/repo", sessions: sessions)
        readyLive.emit(directory: "/repo", ids: ["ses-1"])
        statusLive.emit(
            directory: "/repo",
            statuses: ["ses-1": SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: sessions),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"]),
            sessionLiveStoreOverride: sessionLive,
            sessionStatusLiveStoreOverride: statusLive,
            sessionReadyStoreOverride: readyLive
        )
        await viewModel.refresh()
        let working = await waitUntil {
            homeSession(viewModel, id: "ses-1")?.statusLabel == "Working"
        }
        #expect(working)

        await viewModel.refresh()
        #expect(homeSession(viewModel, id: "ses-1")?.statusLabel == "Working")
        #expect(homeSession(viewModel, id: "ses-1")?.isReady == true)
    }

    @Test func liveStatus_mapsRetryingAndHidesUntilSeeded() async {
        let sessions = [
            Session(id: "ses-1", title: "Alpha", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 1),
        ]
        let sessionLive = ControllableSessionLiveStore()
        let statusLive = ControllableStatusLiveStore()
        let readyLive = ControllableReadyStore()
        sessionLive.emit(directory: "/repo", sessions: sessions)
        readyLive.emit(directory: "/repo", ids: ["ses-1"])
        statusLive.emit(
            directory: "/repo",
            snapshot: SessionActivitySnapshot(entries: [:], seeded: false)
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: sessions),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"]),
            sessionLiveStoreOverride: sessionLive,
            sessionStatusLiveStoreOverride: statusLive,
            sessionReadyStoreOverride: readyLive
        )
        await viewModel.refresh()
        let unseeded = await waitUntil {
            homeSession(viewModel, id: "ses-1") != nil
        }
        #expect(unseeded)
        #expect(homeSession(viewModel, id: "ses-1")?.statusLabel == nil)
        #expect(homeSession(viewModel, id: "ses-1")?.isReady == false)

        statusLive.emit(
            directory: "/repo",
            statuses: ["ses-1": SessionStatus(sessionID: "ses-1", raw: ["type": "retry", "attempt": "2"])]
        )
        let retrying = await waitUntil {
            homeSession(viewModel, id: "ses-1")?.statusLabel == "Retrying · 2"
        }
        #expect(retrying)
        #expect(homeSession(viewModel, id: "ses-1")?.isReady == true)
    }

    @Test func liveStatus_cachedRunningMapsStaleUntilServer() async {
        let sessions = [
            Session(id: "ses-1", title: "Alpha", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 1),
        ]
        let sessionLive = ControllableSessionLiveStore()
        let statusLive = ControllableStatusLiveStore()
        let readyLive = ControllableReadyStore()
        sessionLive.emit(directory: "/repo", sessions: sessions)
        readyLive.emit(directory: "/repo", ids: [])
        statusLive.emit(
            directory: "/repo",
            snapshot: SessionActivitySnapshot(
                entries: [
                    "ses-1": SessionActivityEntry(activity: .running, source: .cached, observedAtMs: 1),
                ],
                seeded: false,
                fromCache: true
            )
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: sessions),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"]),
            sessionLiveStoreOverride: sessionLive,
            sessionStatusLiveStoreOverride: statusLive,
            sessionReadyStoreOverride: readyLive
        )
        await viewModel.refresh()
        let cached = await waitUntil {
            homeSession(viewModel, id: "ses-1")?.statusLabel == "Working"
                && homeSession(viewModel, id: "ses-1")?.statusIsStale == true
        }
        #expect(cached)
        #expect(homeSession(viewModel, id: "ses-1")?.isReady == false)

        statusLive.emit(
            directory: "/repo",
            statuses: ["ses-1": SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
        )
        let live = await waitUntil {
            homeSession(viewModel, id: "ses-1")?.statusLabel == "Working"
                && homeSession(viewModel, id: "ses-1")?.statusIsStale == false
        }
        #expect(live)
    }

    // MARK: - Server unread (v2 session.viewed)

    @Test func v2_serverUnreadMapsOutcomeAndIgnoresReadyIds() async {
        var done = Session(id: "ses-done", title: "Done", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 4)
        done.idleAtEpochMs = 100
        // Missing outcome (older v2 server) reads as succeeded → Done.
        done.idleOutcome = nil
        var failed = Session(id: "ses-failed", title: "Failed", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 3)
        failed.idleAtEpochMs = 100
        failed.viewedAtEpochMs = 50
        failed.idleOutcome = .failed
        var stopped = Session(id: "ses-stopped", title: "Stopped", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 2)
        stopped.idleAtEpochMs = 100
        stopped.idleOutcome = .interrupted
        var read = Session(id: "ses-read", title: "Read", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 1)
        read.idleAtEpochMs = 100
        read.viewedAtEpochMs = 100
        read.idleOutcome = .succeeded
        let sessions = [done, failed, stopped, read]
        let sessionLive = ControllableSessionLiveStore()
        let statusLive = ControllableStatusLiveStore()
        let readyLive = ControllableReadyStore()
        sessionLive.emit(directory: "/repo", sessions: sessions)
        // Local Ready ids are v1 only; v2 must ignore them.
        readyLive.emit(directory: "/repo", ids: ["ses-read"])
        statusLive.emit(directory: "/repo", statuses: [:])
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: sessions),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"]),
            serverProtocol: .v2,
            sessionLiveStoreOverride: sessionLive,
            sessionStatusLiveStoreOverride: statusLive,
            sessionReadyStoreOverride: readyLive
        )

        await viewModel.refresh()
        let mapped = await waitUntil {
            homeSession(viewModel, id: "ses-done")?.sessionListStatus() == .done
        }
        #expect(mapped)
        #expect(homeSession(viewModel, id: "ses-done")?.unreadOutcome == .succeeded)
        #expect(homeSession(viewModel, id: "ses-read")?.unreadOutcome == nil)
        #expect(homeSession(viewModel, id: "ses-failed")?.sessionListStatus() == .failed)
        #expect(homeSession(viewModel, id: "ses-stopped")?.sessionListStatus() == .stopped)
        #expect(homeSession(viewModel, id: "ses-read")?.sessionListStatus() == .idle)
        #expect(homeSession(viewModel, id: "ses-read")?.isReady == false)
    }

    @Test func v2_runningWinsOverServerUnread() async {
        var session = Session(id: "ses-1", title: "Alpha", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 1)
        session.idleAtEpochMs = 100
        let sessionLive = ControllableSessionLiveStore()
        let statusLive = ControllableStatusLiveStore()
        sessionLive.emit(directory: "/repo", sessions: [session])
        statusLive.emit(
            directory: "/repo",
            statuses: ["ses-1": SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: [session]),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"]),
            serverProtocol: .v2,
            sessionLiveStoreOverride: sessionLive,
            sessionStatusLiveStoreOverride: statusLive,
            sessionReadyStoreOverride: ControllableReadyStore()
        )

        await viewModel.refresh()
        let running = await waitUntil {
            homeSession(viewModel, id: "ses-1")?.sessionListStatus() == .running
        }
        #expect(running)
    }

    @Test func v2_serverUnreadHiddenUntilStatusSeeded() async {
        var session = Session(id: "ses-1", title: "Alpha", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 1)
        session.idleAtEpochMs = 100
        let sessionLive = ControllableSessionLiveStore()
        let statusLive = ControllableStatusLiveStore()
        sessionLive.emit(directory: "/repo", sessions: [session])
        statusLive.emit(directory: "/repo", snapshot: SessionActivitySnapshot(entries: [:], seeded: false))
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: [session]),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"]),
            serverProtocol: .v2,
            sessionLiveStoreOverride: sessionLive,
            sessionStatusLiveStoreOverride: statusLive,
            sessionReadyStoreOverride: ControllableReadyStore()
        )

        await viewModel.refresh()
        let listed = await waitUntil { homeSession(viewModel, id: "ses-1") != nil }
        #expect(listed)
        #expect(homeSession(viewModel, id: "ses-1")?.sessionListStatus() == .idle)

        statusLive.emit(directory: "/repo", statuses: [:])
        let done = await waitUntil {
            homeSession(viewModel, id: "ses-1")?.sessionListStatus() == .done
        }
        #expect(done)
    }

    @Test func v1_keepsLocalReadyAndIgnoresServerFields() async {
        var session = Session(id: "ses-1", title: "Alpha", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 1)
        // A v1 session never carries these, but even if it did v1 must not read them.
        session.idleAtEpochMs = 100
        session.idleOutcome = .failed
        let sessionLive = ControllableSessionLiveStore()
        let statusLive = ControllableStatusLiveStore()
        let readyLive = ControllableReadyStore()
        sessionLive.emit(directory: "/repo", sessions: [session])
        readyLive.emit(directory: "/repo", ids: ["ses-1"])
        statusLive.emit(directory: "/repo", statuses: [:])
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: [session]),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"]),
            sessionLiveStoreOverride: sessionLive,
            sessionStatusLiveStoreOverride: statusLive,
            sessionReadyStoreOverride: readyLive
        )

        await viewModel.refresh()
        let ready = await waitUntil {
            homeSession(viewModel, id: "ses-1")?.sessionListStatus() == .ready
        }
        #expect(ready)
        #expect(homeSession(viewModel, id: "ses-1")?.unreadOutcome == nil)
    }

    // MARK: - First load of a newly added project (#741)

    @Test func addProject_newProject_exposesLoadingAfterGraceThenLoadsRows() async {
        let stores = ProjectLoadStores()
        let viewModel = stores.viewModel(
            initial: ["/one", "/three"],
            timing: ProjectLoadTiming(grace: .milliseconds(400), minimumVisible: .zero, slowThreshold: .seconds(60), timeout: .seconds(60))
        )
        await viewModel.refresh()

        await viewModel.addProject(worktree: "/two")
        // Home is on All (three projects); the new project takes the scope anyway.
        #expect(viewModel.pendingSelectedFilterId == "/two")
        #expect(viewModel.pendingSelectedFilterOverridesAll)
        // Inside the grace delay nothing reads as loading yet.
        #expect(viewModel.projectLoadStates["/two"] == nil)
        #expect(await waitUntil { viewModel.projectLoadStates["/two"] == .loading(isSlow: false) })

        // A cached publish is not the server answer.
        stores.sessions.emit(directory: "/two", sessions: [], isFromCache: true)
        try? await Task.sleep(for: .milliseconds(50))
        #expect(viewModel.projectLoadStates["/two"] == .loading(isSlow: false))

        stores.sessions.emit(directory: "/two", sessions: [projectLoadSession("two-1", directory: "/two")])
        #expect(await waitUntil {
            viewModel.projectLoadStates["/two"] == nil && homeSession(viewModel, id: "two-1") != nil
        })
    }

    @Test func addProject_loadInsideGrace_neverExposesLoading() async {
        let stores = ProjectLoadStores()
        let viewModel = stores.viewModel(
            initial: ["/one"],
            timing: ProjectLoadTiming(grace: .milliseconds(300), minimumVisible: .milliseconds(500), slowThreshold: .seconds(60), timeout: .seconds(60))
        )
        await viewModel.refresh()

        await viewModel.addProject(worktree: "/two")
        stores.sessions.emit(directory: "/two", sessions: [projectLoadSession("two-1", directory: "/two")])

        var exposed = false
        let deadline = Date().addingTimeInterval(0.6)
        while Date() < deadline {
            if viewModel.projectLoadStates["/two"] != nil { exposed = true }
            try? await Task.sleep(for: .milliseconds(5))
        }
        #expect(!exposed)
        #expect(homeSession(viewModel, id: "two-1") != nil)
    }

    @Test func addProject_zeroSessions_clearsLoadingToEmptyProject() async {
        let stores = ProjectLoadStores()
        let viewModel = stores.viewModel(initial: ["/one"], timing: .immediate)
        await viewModel.refresh()

        await viewModel.addProject(worktree: "/two")
        #expect(await waitUntil { viewModel.projectLoadStates["/two"] == .loading(isSlow: false) })

        stores.sessions.emit(directory: "/two", sessions: [])
        #expect(await waitUntil { viewModel.projectLoadStates["/two"] == nil })
        guard case let .loaded(sections, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.first { $0.worktree == "/two" }?.allSessions.isEmpty == true)
    }

    @Test func addProject_exposedLoadingStaysForMinimumVisibleTime() async {
        let stores = ProjectLoadStores()
        let viewModel = stores.viewModel(
            initial: ["/one"],
            timing: ProjectLoadTiming(grace: .zero, minimumVisible: .milliseconds(300), slowThreshold: .seconds(60), timeout: .seconds(60))
        )
        await viewModel.refresh()

        await viewModel.addProject(worktree: "/two")
        #expect(await waitUntil { viewModel.projectLoadStates["/two"] == .loading(isSlow: false) })
        let shown = Date()

        stores.sessions.emit(directory: "/two", sessions: [projectLoadSession("two-1", directory: "/two")])
        // Rows land at once; the loading marker holds for the rest of its minimum time.
        #expect(await waitUntil { homeSession(viewModel, id: "two-1") != nil })
        #expect(viewModel.projectLoadStates["/two"] == .loading(isSlow: false))
        #expect(await waitUntil { viewModel.projectLoadStates["/two"] == nil })
        #expect(Date().timeIntervalSince(shown) >= 0.25)
    }

    @Test func addProject_storeFailure_failsThenRetryLoads() async {
        let stores = ProjectLoadStores()
        let viewModel = stores.viewModel(initial: ["/one"], timing: .immediate)
        await viewModel.refresh()

        await viewModel.addProject(worktree: "/two")
        stores.sessions.emit(directory: "/two", sessions: [], isFromCache: true, loadFailed: true)
        #expect(await waitUntil {
            viewModel.projectLoadStates["/two"] == .failed(message: ProjectLoadState.failedMessage)
        })
        #expect(ProjectLoadState.failedMessage == "Couldn\u{2019}t load sessions")

        await viewModel.retryProjectLoad(worktree: "/two")
        #expect(viewModel.projectLoadStates["/two"] == .loading(isSlow: false))
        #expect(stores.sessions.invalidatedDirectories.contains("/two"))

        stores.sessions.emit(directory: "/two", sessions: [projectLoadSession("two-1", directory: "/two")])
        #expect(await waitUntil {
            viewModel.projectLoadStates["/two"] == nil && homeSession(viewModel, id: "two-1") != nil
        })
    }

    @Test func addProject_failedThenAutomaticSuccess_loads() async {
        let stores = ProjectLoadStores()
        let viewModel = stores.viewModel(initial: ["/one"], timing: .immediate)
        await viewModel.refresh()

        await viewModel.addProject(worktree: "/two")
        stores.sessions.emit(directory: "/two", sessions: [], isFromCache: true, loadFailed: true)
        #expect(await waitUntil {
            viewModel.projectLoadStates["/two"] == .failed(message: ProjectLoadState.failedMessage)
        })

        // The coordinator's own retry succeeds later.
        stores.sessions.emit(directory: "/two", sessions: [])
        #expect(await waitUntil { viewModel.projectLoadStates["/two"] == nil })
    }

    @Test func addProject_slowLoad_marksSlowAfterThreshold() async {
        let stores = ProjectLoadStores()
        let viewModel = stores.viewModel(
            initial: ["/one"],
            timing: ProjectLoadTiming(grace: .zero, minimumVisible: .zero, slowThreshold: .milliseconds(150), timeout: .seconds(60))
        )
        await viewModel.refresh()

        await viewModel.addProject(worktree: "/two")
        #expect(await waitUntil { viewModel.projectLoadStates["/two"] == .loading(isSlow: false) })
        #expect(await waitUntil { viewModel.projectLoadStates["/two"] == .loading(isSlow: true) })
    }

    @Test func addProject_noOutcome_failsAfterSafetyTimeout() async {
        let stores = ProjectLoadStores()
        let viewModel = stores.viewModel(
            initial: ["/one"],
            timing: ProjectLoadTiming(grace: .zero, minimumVisible: .zero, slowThreshold: .seconds(60), timeout: .milliseconds(200))
        )
        await viewModel.refresh()

        await viewModel.addProject(worktree: "/two")
        #expect(await waitUntil { viewModel.projectLoadStates["/two"] == .loading(isSlow: false) })
        #expect(await waitUntil {
            viewModel.projectLoadStates["/two"] == .failed(message: ProjectLoadState.failedMessage)
        })
    }

    @Test func removeProject_clearsItsLoadState() async {
        let stores = ProjectLoadStores()
        let viewModel = stores.viewModel(initial: ["/one"], timing: .immediate)
        await viewModel.refresh()

        await viewModel.addProject(worktree: "/two")
        stores.sessions.emit(directory: "/two", sessions: [], isFromCache: true, loadFailed: true)
        #expect(await waitUntil { viewModel.projectLoadStates["/two"] != nil })

        viewModel.requestRemoveProject(worktree: "/two")
        await viewModel.confirmRemoveProject()
        #expect(viewModel.projectLoadStates.isEmpty)
    }

    @Test func homeChromeProjects_carryLoadIntoScopeTabs() {
        let section = ProjectSectionItem(
            id: "/two",
            worktree: "/two",
            displayName: "two",
            pathSubtitle: "/two",
            letter: "T",
            letterAccent: .teal,
            allSessions: [],
            loadError: nil
        )
        let projects = HomeProjectGrouping.group(addedPaths: [section.worktree], resolutions: [:])
        let load = SessionListHomeChrome.projectLoad(projects[0], loadStates: ["/two": .loading(isSlow: true)])
        #expect(load == .loading(isSlow: true))
        let tabs = SessionListHomeChrome.filterTabs(
            projects: projects,
            sessions: [section].crossProjectSessionListSessions(projects: projects),
            loads: [projects[0].key: load].compactMapValues { $0 }
        )
        #expect(tabs.first?.load == .loading(isSlow: true))
        #expect(tabs.first?.load?.subtitle == "Still loading\u{2026}")
    }
}

@MainActor
private func homeSession(_ viewModel: ProjectListHomeViewModel, id: String) -> SessionListItem? {
    guard case let .loaded(sections, _, _, _) = viewModel.state else { return nil }
    return sections.flatMap(\.allSessions).first { $0.id == id }
}

@MainActor
private func makeViewModel(
    projectRepository: any ProjectRepository,
    filesystemRepository: any FilesystemRepository = FakeFilesystemRepository(),
    sessionRepository: any SessionRepository = FakeSessionRepository(),
    messageRepository: any MessageRepository = FakeMessageRepository(),
    connectionProfileStore: FakeConnectionProfileStore = FakeConnectionProfileStore(),
    eventStream: FakeForegroundEventStream? = nil,
    serverProtocol: ServerProtocol? = nil,
    invalidationAckTimeoutMs: Int = LiveStoreRefreshPolicy.invalidationAckTimeoutMs,
    sessionLiveStoreOverride: (any SessionLiveStore)? = nil,
    sessionStatusLiveStoreOverride: (any SessionStatusLiveStore)? = nil,
    sessionReadyStoreOverride: (any SessionReadyStore)? = nil,
    projectLoadTiming: ProjectLoadTiming = .standard,
    worktreeRepository: any WorktreeRepository = UnavailableWorktreeRepository(),
    branchRetryDelays: [Duration] = [.seconds(1), .seconds(3)]
) -> ProjectListHomeViewModel {
    let eventStream = eventStream ?? FakeForegroundEventStream()
    // Mutations (createSession) require Connected; default fakes start Idle.
    eventStream.setConnectionState(.connected)
    let manager = ActiveConnectionManager()
    manager.apply(
        ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: ""),
        serverProtocol: serverProtocol
    )
    let coordinator = ConnectionSessionCoordinator(
        activeConnectionManager: manager,
        connectionProfileStore: connectionProfileStore
    )
    let sessionStatusLiveStore = sessionStatusLiveStoreOverride ?? DefaultSessionStatusLiveStore(
        sessionRepository: sessionRepository,
        eventStream: eventStream,
        invalidationAckTimeoutMs: invalidationAckTimeoutMs
    )
    let sessionLiveStore = sessionLiveStoreOverride ?? DefaultSessionLiveStore(
        sessionRepository: sessionRepository,
        eventStream: eventStream,
        sessionCache: NoOpSessionCache.shared,
        invalidationAckTimeoutMs: invalidationAckTimeoutMs
    )
    let sessionReadyStore = sessionReadyStoreOverride ?? DefaultSessionReadyStore(
        sessionStatusLiveStore: sessionStatusLiveStore,
        eventStream: eventStream
    )
    return ProjectListHomeViewModel(
        projectRepository: projectRepository,
        filesystemRepository: filesystemRepository,
        sessionRepository: sessionRepository,
        messageRepository: messageRepository,
        connectionProfileStore: connectionProfileStore,
        activeConnectionManager: manager,
        sessionLiveStore: sessionLiveStore,
        sessionStatusLiveStore: sessionStatusLiveStore,
        sessionReadyStore: sessionReadyStore,
        connectionSessionCoordinator: coordinator,
        addProjectListingDebounce: .zero,
        projectLoadTiming: projectLoadTiming,
        worktreeRepository: worktreeRepository,
        branchRetryDelays: branchRetryDelays
    )
}

private struct HomeModelRepository: ModelRepository {
    let catalog: ModelSelectionCatalog

    func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog { catalog }
}

@MainActor
private final class HomeSelectionStore: SessionModelSelectionStore, @unchecked Sendable {
    private var sessions: [String: SessionModelSelection] = [:]
    private var preferences: [String: SessionModelSelection] = [:]

    func load(serverBaseURL: String, worktree: String, sessionID: String) async -> SessionModelSelection? { nil }
    func save(serverBaseURL: String, worktree: String, sessionID: String, selection: SessionModelSelection) async {}
    func clearAll() async {}

    func loadSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async -> SessionModelSelection? {
        sessions[key(connectionIdentity, worktree, sessionID)]
    }

    func saveSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String,
        selection: SessionModelSelection
    ) async {
        sessions[key(connectionIdentity, worktree, sessionID)] = selection
    }

    func deleteSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async {
        sessions.removeValue(forKey: key(connectionIdentity, worktree, sessionID))
    }

    func deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionID: String
    ) async {
        let prefix = [connectionIdentity.serverBaseURL, connectionIdentity.username].joined(separator: "|")
        sessions.keys
            .filter { $0.hasPrefix(prefix) && $0.hasSuffix("|\(sessionID)") }
            .forEach { sessions.removeValue(forKey: $0) }
    }

    func loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String
    ) async -> SessionModelSelection? {
        preferences[key(connectionIdentity, worktree)]
    }

    func saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection
    ) async {
        preferences[key(connectionIdentity, worktree)] = selection
    }

    private func key(_ identity: ModelSelectionConnectionIdentity, _ values: String...) -> String {
        ([identity.serverBaseURL, identity.username] + values).joined(separator: "|")
    }
}

private final class FakeProjectRepository: ProjectRepository, @unchecked Sendable {
    let shouldFailProbe: Bool
    let bootWorktree: String
    let projects: [Project]
    let projectsByDirectory: [String: Project]
    let failingDirectories: Set<String>
    private(set) var validatedDirectories: [String?] = []

    init(
        shouldFailProbe: Bool = false,
        bootWorktree: String = "/boot",
        projects: [Project] = [],
        projectsByDirectory: [String: Project] = [:],
        failingDirectories: Set<String> = []
    ) {
        self.shouldFailProbe = shouldFailProbe
        self.bootWorktree = bootWorktree
        self.projects = projects
        self.projectsByDirectory = projectsByDirectory
        self.failingDirectories = failingDirectories
    }

    func probeServer() async throws {
        if shouldFailProbe { throw ServerProbeError.transport("down") }
    }

    func listProjects() async throws -> [Project] { projects }

    func getCurrentProject(directory: String?) async throws -> Project {
        validatedDirectories.append(directory)
        if let directory, failingDirectories.contains(directory) {
            throw TestError.message("Not a project")
        }
        if let directory, let project = projectsByDirectory[directory] {
            return project
        }
        // Every folder is its own project unless a test maps it.
        return Project(id: "prj:" + (directory ?? bootWorktree), worktree: directory ?? bootWorktree, displayName: "demo")
    }
}

private final class FakeFilesystemRepository: FilesystemRepository, @unchecked Sendable {
    let paths: ServerPaths
    var listings: [String: [DirectoryNode]]
    var failingPaths: Set<String>
    var missingPaths: Set<String>
    var gitPaths: Set<String>
    /// Listings of these paths wait until `release(_:)`.
    var heldPaths: Set<String> = []
    private(set) var requestedPaths: [String] = []
    private(set) var inFlight = 0
    private(set) var maxInFlight = 0
    private var held: [String: CheckedContinuation<Void, Never>] = [:]

    init(
        paths: ServerPaths = ServerPaths(home: "/home", directory: "/home", worktree: "/"),
        listings: [String: [DirectoryNode]] = [:],
        failingPaths: Set<String> = [],
        missingPaths: Set<String> = [],
        gitPaths: Set<String> = []
    ) {
        self.paths = paths
        self.listings = listings
        self.failingPaths = failingPaths
        self.missingPaths = missingPaths
        self.gitPaths = gitPaths
    }

    func getPaths() async throws -> ServerPaths { paths }

    func listDirectory(path: String) async throws -> DirectoryListing {
        requestedPaths.append(path)
        inFlight += 1
        maxInFlight = max(maxInFlight, inFlight)
        defer { inFlight -= 1 }
        if heldPaths.contains(path) {
            await withCheckedContinuation { held[path] = $0 }
        }
        if missingPaths.contains(path) { throw FilesystemError.directoryNotFound }
        if failingPaths.contains(path) { throw TestError.message("Listing failed") }
        return DirectoryListing(
            folders: DirectoryListingPolicy.directories(listings[path] ?? []),
            isGitRepository: gitPaths.contains(path)
        )
    }

    var heldCount: Int { held.count }

    func release(_ path: String) {
        heldPaths.remove(path)
        held.removeValue(forKey: path)?.resume()
    }
}

private func folder(_ path: String) -> DirectoryNode {
    DirectoryNode(name: ServerPathPolicy.name(path), absolutePath: path, isDirectory: true)
}

private final class FakeConnectionProfileStore: ConnectionProfileStore, @unchecked Sendable {
    private var added: [String]

    init(initial: [String] = []) {
        self.added = initial
    }

    func activeProfile() async -> ConnectionProfile? {
        ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: "")
    }

    func saveProfile(_ profile: ConnectionProfile) async {}

    func addedWorktrees(serverBaseURL: String) async -> [String] { added }

    func addWorktree(serverBaseURL: String, worktree: String) async {
        if !added.contains(worktree) { added.append(worktree) }
    }

    func removeWorktree(serverBaseURL: String, worktree: String) async {
        added.removeAll { $0 == worktree }
    }

    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {
        var seen = Set<String>()
        added = worktrees.filter { seen.insert($0).inserted }
    }

    func clearAll() async {
        added.removeAll()
    }
}

private struct SlowFirstProjectRepository: ProjectRepository {
    func probeServer() async throws {}

    func listProjects() async throws -> [Project] { [] }

    func getCurrentProject(directory: String?) async throws -> Project {
        if directory == "/slow" {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        return Project(
            id: directory ?? "p",
            worktree: directory ?? "/unknown",
            displayName: ProjectDomainAdapter.displayName(from: directory ?? "/unknown")
        )
    }
}

private struct SlowFirstSessionRepository: SessionRepository {
    func listSessions(directory: String?) async throws -> [Session] {
        if directory == "/slow" {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        return [
            Session(
                id: "1",
                title: "Session",
                slug: nil,
                projectID: nil,
                directory: directory,
                parentID: nil,
                updatedAtEpochMs: 1
            ),
        ]
    }

    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?, directory: String?) async throws -> Session { fatalError() }
    func archiveSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { fatalError() }
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] { fatalError() }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id: String) async throws -> Bool { fatalError() }
    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory: String?
    ) async throws -> Bool { fatalError() }
}

private final class FakeSessionRepository: SessionRepository, @unchecked Sendable {
    let sessions: [Session]
    let createError: Error?
    let updateError: Error?
    let archiveError: Error?
    let deleteError: Error?
    let forkError: Error?
    private(set) var createdDirectories: [String?] = []
    private var holdNextList = false
    private var blockedListContinuation: CheckedContinuation<Void, Never>?
    private let listLock = NSLock()
    private var listSessionsCallCount = 0
    private var listSessionsFailing = false

    var listCallCount: Int {
        listLock.lock()
        defer { listLock.unlock() }
        return listSessionsCallCount
    }

    var isListBlocked: Bool {
        listLock.lock()
        defer { listLock.unlock() }
        return blockedListContinuation != nil
    }

    func holdNextListSessions() {
        holdNextList = true
    }

    /// While set, `listSessions` throws (a server that cannot list this project).
    func setListSessionsFailing(_ failing: Bool) {
        listLock.lock()
        listSessionsFailing = failing
        listLock.unlock()
    }

    func resumeListSessions() {
        listLock.lock()
        let continuation = blockedListContinuation
        blockedListContinuation = nil
        listLock.unlock()
        continuation?.resume()
    }
    private(set) var updatedTitles: [(id: String, title: String?)] = []
    private(set) var archivedIDs: [String] = []
    private(set) var deletedIDs: [String] = []
    private(set) var forkedIDs: [String] = []
    /// Answers `getVcsInfo`; nil keeps the protocol default (404).
    var vcsInfo: (@Sendable (String) async throws -> VcsInfo)?

    func getVcsInfo(directory: String?) async throws -> VcsInfo {
        guard let vcsInfo else { throw SessionError.http(statusCode: 404, message: nil) }
        return try await vcsInfo(directory ?? "")
    }

    init(
        sessions: [Session] = [Session(id: "1", title: "Alpha", slug: nil, projectID: nil, directory: nil, parentID: nil, updatedAtEpochMs: 1)],
        createError: Error? = nil,
        updateError: Error? = nil,
        archiveError: Error? = nil,
        deleteError: Error? = nil,
        forkError: Error? = nil
    ) {
        self.sessions = sessions
        self.createError = createError
        self.updateError = updateError
        self.archiveError = archiveError
        self.deleteError = deleteError
        self.forkError = forkError
    }

    func listSessions(directory: String?) async throws -> [Session] {
        listLock.lock()
        listSessionsCallCount += 1
        listLock.unlock()
        if holdNextList {
            holdNextList = false
            await withCheckedContinuation { continuation in
                listLock.lock()
                blockedListContinuation = continuation
                listLock.unlock()
            }
        }
        listLock.lock()
        let failing = listSessionsFailing
        listLock.unlock()
        if failing { throw TestError.message("List failed") }
        return sessions
    }

    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session {
        createdDirectories.append(directory)
        if let createError { throw createError }
        return Session(id: "created", title: "New Session", slug: nil, projectID: nil, directory: directory, parentID: parentID, updatedAtEpochMs: 10)
    }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }

    func updateSession(id: String, title: String?, directory: String?) async throws -> Session {
        updatedTitles.append((id, title))
        if let updateError { throw updateError }
        return Session(
            id: id,
            title: title ?? "",
            slug: nil,
            projectID: nil,
            directory: directory,
            parentID: nil,
            updatedAtEpochMs: 20
        )
    }

    func archiveSession(id: String, directory: String?) async throws -> Session {
        archivedIDs.append(id)
        if let archiveError { throw archiveError }
        return Session(
            id: id,
            title: "Archived",
            slug: nil,
            projectID: nil,
            directory: directory,
            parentID: nil,
            updatedAtEpochMs: 30,
            archivedAtEpochMs: 30
        )
    }

    func deleteSession(id: String, directory: String?) async throws -> Bool {
        deletedIDs.append(id)
        if let deleteError { throw deleteError }
        return true
    }

    func listChildSessions(id: String) async throws -> [Session] { fatalError() }
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }

    func forkSession(id: String, atMessageID: String?) async throws -> Session {
        forkedIDs.append(id)
        if let forkError { throw forkError }
        return Session(
            id: "forked",
            title: "Forked",
            slug: nil,
            projectID: nil,
            directory: nil,
            parentID: nil,
            updatedAtEpochMs: 40
        )
    }

    func abortSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] { fatalError() }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id: String) async throws -> Bool { fatalError() }
    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory: String?
    ) async throws -> Bool { fatalError() }
}

private final class FakeMessageRepository: MessageRepository, @unchecked Sendable {
    let messages: [Message]
    let listError: Error?
    let catchUpChrome: Bool
    private(set) var listCallCount = 0

    init(messages: [Message] = [], listError: Error? = nil, catchUpChrome: Bool = false) {
        self.messages = messages
        self.listError = listError
        self.catchUpChrome = catchUpChrome
    }

    func observeCatchUpChrome() -> AsyncStream<Bool> {
        AsyncStream { continuation in
            continuation.yield(catchUpChrome)
            if !catchUpChrome {
                continuation.finish()
            }
        }
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        AsyncStream { $0.finish() }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { $0.finish() }
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] { messages }
    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] { [] }
    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage { fatalError() }
    func clearMessageCache() {}

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        listCallCount += 1
        if let listError { throw listError }
        return messages
    }

    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message { fatalError() }
    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message { fatalError() }
    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {}

    func runShellCommand(sessionID: String, options: ShellCommandOptions, directory: String?) async throws {}
}

private func userMessage(id: String, text: String) -> Message {
    Message(id: id, role: .user, text: text, model: nil, mode: nil, sentAtEpochMs: 1)
}

private enum TestError: LocalizedError {
    case message(String)

    var errorDescription: String? {
        switch self {
        case let .message(message):
            message
        }
    }
}

@MainActor
private func waitUntil(
    timeoutMs: Int = 2_000,
    predicate: @MainActor () -> Bool
) async -> Bool {
    let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
    while Date() < deadline {
        if predicate() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return predicate()
}

@MainActor
private final class ControllableSessionLiveStore: SessionLiveStore {
    private var latest: [String: SessionListSnapshot] = [:]
    private var continuations: [String: [UUID: AsyncStream<SessionListSnapshot>.Continuation]] = [:]
    private(set) var invalidatedDirectories: [String?] = []

    func emit(directory: String, sessions: [Session], isFromCache: Bool = false, loadFailed: Bool = false) {
        let snapshot = SessionListSnapshot(sessions: sessions, isFromCache: isFromCache, loadFailed: loadFailed)
        latest[directory] = snapshot
        continuations[directory]?.values.forEach { $0.yield(snapshot) }
    }

    func invalidate(directory: String?) async {
        invalidatedDirectories.append(directory)
    }

    func observeSessions(directory: String) -> AsyncStream<SessionListSnapshot> {
        AsyncStream { continuation in
            let id = UUID()
            continuations[directory, default: [:]][id] = continuation
            if let latest = latest[directory] {
                continuation.yield(latest)
            }
            continuation.onTermination = { _ in
                Task { @MainActor in
                    self.continuations[directory]?.removeValue(forKey: id)
                }
            }
        }
    }

    func clearCache() {}
}

@MainActor
private final class ControllableStatusLiveStore: SessionStatusLiveStore {
    private var latest: [String: SessionActivitySnapshot] = [:]
    private var continuations: [String: [UUID: AsyncStream<SessionActivitySnapshot>.Continuation]] = [:]

    func emit(directory: String, statuses: [String: SessionStatus], seeded: Bool = true) {
        var entries: [String: SessionActivityEntry] = [:]
        for (id, status) in statuses {
            let activity = SessionActivity(raw: status.raw)
            guard activity != .idle else { continue }
            entries[id] = SessionActivityEntry(activity: activity, source: .server, observedAtMs: 0)
        }
        emit(directory: directory, snapshot: SessionActivitySnapshot(entries: entries, seeded: seeded))
    }

    func emit(directory: String, snapshot: SessionActivitySnapshot) {
        latest[directory] = snapshot
        continuations[directory]?.values.forEach { $0.yield(snapshot) }
    }

    func observeActivity(directory: String) -> AsyncStream<SessionActivitySnapshot> {
        AsyncStream { continuation in
            let id = UUID()
            continuations[directory, default: [:]][id] = continuation
            if let latest = latest[directory] {
                continuation.yield(latest)
            }
            continuation.onTermination = { _ in
                Task { @MainActor in
                    self.continuations[directory]?.removeValue(forKey: id)
                }
            }
        }
    }

    func clearCache() {}
}

@MainActor
private final class ControllableReadyStore: SessionReadyStore {
    private var latest: [String: Set<String>] = [:]
    private var continuations: [String: [UUID: AsyncStream<Set<String>>.Continuation]] = [:]

    func emit(directory: String, ids: Set<String>) {
        latest[directory] = ids
        continuations[directory]?.values.forEach { $0.yield(ids) }
    }

    func observeReadyIds(directory: String) -> AsyncStream<Set<String>> {
        AsyncStream { continuation in
            let id = UUID()
            continuations[directory, default: [:]][id] = continuation
            continuation.yield(latest[directory] ?? [])
            continuation.onTermination = { _ in
                Task { @MainActor in
                    self.continuations[directory]?.removeValue(forKey: id)
                }
            }
        }
    }

    func notifyPromptSent(sessionID: String, directory: String) {}
    func setFocusedSession(sessionID: String?, directory: String?) {}
    func releaseFocusedSession(sessionID: String) {}
    func bindProfile(profileID: String?) {}
    func clearCache() {}
}

private extension ProjectLoadTiming {
    /// Loading shows at once and clears at once; slow and timeout stay out of the way.
    static let immediate = ProjectLoadTiming(grace: .zero, minimumVisible: .zero, slowThreshold: .seconds(60), timeout: .seconds(60))
}

private func projectLoadSession(_ id: String, directory: String) -> Session {
    Session(id: id, title: id, slug: nil, projectID: nil, directory: directory, parentID: nil, updatedAtEpochMs: 1)
}

/// Controllable live stores for first-load tests, with `/one` already listed.
@MainActor
private final class ProjectLoadStores {
    let sessions = ControllableSessionLiveStore()
    let statuses = ControllableStatusLiveStore()
    let ready = ControllableReadyStore()

    init() {
        sessions.emit(directory: "/one", sessions: [projectLoadSession("one-1", directory: "/one")])
        statuses.emit(directory: "/one", statuses: [:])
    }

    func viewModel(initial: [String], timing: ProjectLoadTiming) -> ProjectListHomeViewModel {
        makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: initial),
            sessionLiveStoreOverride: sessions,
            sessionStatusLiveStoreOverride: statuses,
            sessionReadyStoreOverride: ready,
            projectLoadTiming: timing
        )
    }
}

@MainActor
struct SessionLiveStoreLoadOutcomeTests {
    @Test func failedServerLoadPublishesFailureAndLaterSuccessClearsIt() async {
        let repository = FakeSessionRepository(sessions: [projectLoadSession("1", directory: "/repo")])
        repository.setListSessionsFailing(true)
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let store = DefaultSessionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream,
            sessionCache: NoOpSessionCache.shared
        )
        let collector = SessionListSnapshotCollector(stream: store.observeSessions(directory: "/repo"))
        defer { collector.cancel() }

        #expect(await waitUntil(timeoutMs: 3_000) { collector.snapshots.contains { $0.loadFailed } })
        let failed = collector.snapshots.first { $0.loadFailed }
        #expect(failed?.isFromCache == true)
        #expect(failed?.sessions.isEmpty == true)

        // The coordinator's backoff retry now succeeds.
        repository.setListSessionsFailing(false)
        #expect(await waitUntil(timeoutMs: 5_000) {
            collector.snapshots.last.map { !$0.loadFailed && !$0.isFromCache } == true
        })
        #expect(collector.snapshots.last?.sessions.map(\.id) == ["1"])
    }
}

@MainActor
private final class SessionListSnapshotCollector {
    private(set) var snapshots: [SessionListSnapshot] = []
    private var task: Task<Void, Never>?

    init(stream: AsyncStream<SessionListSnapshot>) {
        task = Task { @MainActor [weak self] in
            for await snapshot in stream {
                self?.snapshots.append(snapshot)
            }
        }
    }

    func cancel() {
        task?.cancel()
    }
}

/// Worktree inventories handed out in order; the last one repeats.
private final class ScriptedWorktreeRepository: WorktreeRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var loads: [WorktreeLoad]

    init(loads: [WorktreeLoad]) {
        self.loads = loads
    }

    private func next() -> WorktreeLoad {
        lock.withLock {
            loads.count > 1 ? loads.removeFirst() : (loads.first ?? .unloaded)
        }
    }

    func load(addedPath: String, projectID: String?) async -> WorktreeLoad { next() }
    func loadRefreshed(addedPath: String, projectID: String?) async -> WorktreeLoad { next() }
    func refresh(projectID: String) async -> Bool { false }
    func refetch(projectID: String) async -> WorktreeLoad { next() }
    func onDisconnected() {}
    func onConnection(key: String) {}
}

/// `getVcsInfo` that holds every call until `open()`, then answers newest first. Directories in
/// `failFirstCallFor` fail their first call (a request cancelled mid-flight).
private final class GatedVcs: @unchecked Sendable {
    private let lock = NSLock()
    private let branches: [String: String]
    private var failFirst: Set<String>
    private var isOpen = false
    private var waiting: [(directory: String, continuation: CheckedContinuation<Void, Never>)] = []

    init(branches: [String: String], failFirstCallFor: Set<String> = []) {
        self.branches = branches
        self.failFirst = failFirstCallFor
    }

    var waitingDirectories: [String] {
        lock.withLock { waiting.map(\.directory) }
    }

    func open() {
        let released = lock.withLock {
            isOpen = true
            defer { waiting.removeAll() }
            return waiting.map(\.continuation)
        }
        // Newest first: a stale request answers last.
        released.reversed().forEach { $0.resume() }
    }

    func info(_ directory: String) async throws -> VcsInfo {
        let fails = lock.withLock { failFirst.remove(directory) != nil }
        let mustWait = lock.withLock { !isOpen }
        if mustWait {
            await withCheckedContinuation { continuation in
                let resumeNow = lock.withLock {
                    if isOpen { return true }
                    waiting.append((directory, continuation))
                    return false
                }
                if resumeNow { continuation.resume() }
            }
        }
        if fails { throw SessionError.transport("cancelled") }
        return VcsInfo(currentBranch: branches[directory], defaultBranch: "main")
    }
}
