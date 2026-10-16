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
            Project(id: "old", worktree: "/Users/me/web", displayName: "web", activityAtEpochMs: 1),
            Project(id: "app", worktree: "/Users/me/app", displayName: "app", activityAtEpochMs: 2),
            Project(id: "one", worktree: "/one", displayName: "one", activityAtEpochMs: 3),
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
        #expect(sheet.showSuggestions)
        #expect(sheet.suggestions.map(\.path) == ["/Users/me/app", "/Users/me/web"])
        #expect(sheet.suggestions.first?.activityLabel != nil)
        #expect(sheet.folderSectionTitle == "In me")
        #expect(sheet.roots.map(\.label) == ["Home", "/", "Backup"])
        #expect(sheet.roots.map(\.path) == ["/Users/me", "/", "/Volumes/Backup"])
        #expect(sheet.roots.map(\.selected) == [true, false, false])
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
                Project(id: "app", worktree: "/home/app", displayName: "app", activityAtEpochMs: 1),
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
        #expect(sheet.addTitle == "Add code")
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
        #expect(sheet.addTitle == "Add f249")
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
        #expect(sheet.addTitle == "Add .config")
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
        #expect(sheet.addTitle == "Add code")
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
                Project(id: "work", worktree: #"D:\work\app"#, displayName: "app", activityAtEpochMs: 1),
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
        #expect(sheet.addTitle == "Add code")

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
                Project(id: "app", worktree: #"C:\Users\me\app"#, displayName: "app", activityAtEpochMs: 1),
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
                Project(id: "app", worktree: "/home/app", displayName: "app", activityAtEpochMs: 1),
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
                Project(id: "app", worktree: "/srv/app", displayName: "app", activityAtEpochMs: 1),
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

    @Test func onSessionLongPress_opensSheetAndKeepsForkHiddenWithoutUserMessages() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")

        #expect(viewModel.sessionActionsSheetVisible)
        #expect(viewModel.sessionActionsTarget?.sessionId == "s1")
        #expect(viewModel.sessionActionsTarget?.title == "Alpha")
        let stayedHidden = await waitUntil(timeoutMs: 200) { viewModel.sessionActionsCanFork }
        #expect(stayedHidden == false)
    }

    @Test func onSessionLongPress_showsForkWhenSessionHasVisibleUserMessage() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            messageRepository: FakeMessageRepository(messages: [userMessage(id: "m1", text: "Hello")]),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")

        #expect(await waitUntil { viewModel.sessionActionsCanFork })
    }

    @Test func onSessionLongPress_hidesForkWhenMessageFetchFails() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            messageRepository: FakeMessageRepository(
                messages: [userMessage(id: "m1", text: "Hello")],
                listError: TestError.message("offline")
            ),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        viewModel.onSessionLongPress(sessionId: "s1", title: "Alpha", worktree: "/repo")

        let becameForkable = await waitUntil(timeoutMs: 200) { viewModel.sessionActionsCanFork }
        #expect(becameForkable == false)
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

    @Test func openCodeHome_pendingPermissionOutranksRunning() async {
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
            statuses: ["ses-1": SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
        )
        let permissions = RecordingPermissionLiveStore(requests: [
            PermissionRequest(
                id: "perm-1",
                sessionID: "ses-1",
                permission: "bash",
                patterns: ["git status"],
                metadata: [:],
                always: [],
                toolMessageID: nil,
                toolCallID: nil
            ),
        ])
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: sessions),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"]),
            sessionLiveStoreOverride: sessionLive,
            sessionStatusLiveStoreOverride: statusLive,
            sessionReadyStoreOverride: readyLive,
            permissionLiveStore: permissions
        )
        await viewModel.refresh()
        let waiting = await waitUntil {
            homeSession(viewModel, id: "ses-1")?.sessionListStatus() == .needsApproval
        }
        #expect(waiting)
        #expect(permissions.observedDirectories == ["/repo"])
        #expect(permissions.notedSessionIDs["/repo"] == Set(["ses-1"]))

        permissions.emit([])
        let running = await waitUntil {
            homeSession(viewModel, id: "ses-1")?.sessionListStatus() == .running
        }
        #expect(running)
    }

    @Test func t3Home_doesNotFetchOpenCodePermissions() async {
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
            statuses: ["ses-1": SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
        )
        let permissions = RecordingPermissionLiveStore(requests: [
            PermissionRequest(
                id: "perm-1",
                sessionID: "ses-1",
                permission: "bash",
                patterns: [],
                metadata: [:],
                always: [],
                toolMessageID: nil,
                toolCallID: nil
            ),
        ])
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: sessions),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"]),
            sessionLiveStoreOverride: sessionLive,
            sessionStatusLiveStoreOverride: statusLive,
            sessionReadyStoreOverride: readyLive,
            permissionLiveStore: permissions,
            engine: .t3
        )
        await viewModel.refresh()
        let running = await waitUntil {
            homeSession(viewModel, id: "ses-1")?.statusLabel == "Working"
        }
        #expect(running)
        #expect(homeSession(viewModel, id: "ses-1")?.sessionListStatus() == .running)
        #expect(permissions.observedDirectories.isEmpty)
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

    @Test func homeSessionActions_chooseSettle_skipsBusyThread() async {
        let repository = FakeSessionRepository()
        let region = HomeSessionActions(sessionRepository: repository, messageRepository: FakeMessageRepository())
        var errors: [String] = []
        region.onLifecycleError = { errors.append($0) }
        region.begin(
            sessionId: "thr-1",
            title: "Busy",
            worktree: "/repo",
            canArchive: true,
            showsSettle: true,
            snoozeBlockReason: "Waiting on you",
            presentSheet: false
        )

        let settled = await region.chooseSettle()

        #expect(!settled)
        #expect(repository.settledIDs.isEmpty)
        #expect(errors.isEmpty)
        #expect(!region.actionInFlight)
    }

    @Test func homeSessionActions_settleBlockedByServer_explainsWhy() async {
        let repository = FakeSessionRepository()
        repository.settleError = SessionSettleBlockedError()
        let region = HomeSessionActions(sessionRepository: repository, messageRepository: FakeMessageRepository())
        var errors: [String] = []
        region.onLifecycleError = { errors.append($0) }
        region.begin(sessionId: "thr-1", title: "Idle", worktree: "/repo", canArchive: true, showsSettle: true)

        let settled = await region.chooseSettle()

        #expect(!settled)
        #expect(repository.settledIDs == ["thr-1"])
        #expect(errors == [SessionSettleBlockedError.message])
    }

    @Test func homeSessionActions_settleFailure_staysGenericForOtherErrors() async {
        let repository = FakeSessionRepository()
        repository.settleError = SessionError.transport("boom")
        let region = HomeSessionActions(sessionRepository: repository, messageRepository: FakeMessageRepository())
        var errors: [String] = []
        region.onLifecycleError = { errors.append($0) }
        region.begin(sessionId: "thr-1", title: "Idle", worktree: "/repo", canArchive: true, showsSettle: true)

        _ = await region.chooseSettle()

        #expect(errors == [HomeSessionActions.settleError])
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
    permissionLiveStore: (any PermissionLiveStore)? = nil,
    engine: ConnectionEngine = .opencode
) -> ProjectListHomeViewModel {
    let eventStream = eventStream ?? FakeForegroundEventStream()
    // Mutations (createSession) require Connected; default fakes start Idle.
    eventStream.setConnectionState(.connected)
    let manager = ActiveConnectionManager()
    manager.apply(
        ConnectionProfile(
            baseURL: "http://127.0.0.1:4096/",
            username: engine == .t3 ? "t3" : "opencode",
            password: "",
            engine: engine
        ),
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
        permissionLiveStore: permissionLiveStore,
        connectionSessionCoordinator: coordinator,
        addProjectListingDebounce: .zero
    )
}

@MainActor
private final class RecordingPermissionLiveStore: PermissionLiveStore {
    private var requests: [PermissionRequest]
    private var continuation: AsyncStream<[PermissionRequest]>.Continuation?
    private(set) var observedDirectories: [String] = []
    private(set) var notedSessionIDs: [String: Set<String>] = [:]

    init(requests: [PermissionRequest]) {
        self.requests = requests
    }

    func observePermissions(directory: String) -> AsyncStream<[PermissionRequest]> {
        observedDirectories.append(directory)
        let initial = requests
        return AsyncStream { continuation in
            self.continuation = continuation
            continuation.yield(initial)
        }
    }

    func emit(_ requests: [PermissionRequest]) {
        self.requests = requests
        continuation?.yield(requests)
    }

    func noteSessions(directory: String, sessionIDs: Set<String>) {
        notedSessionIDs[directory] = sessionIDs
    }

    func clearCache() {}
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
        return Project(id: "p1", worktree: directory ?? bootWorktree, displayName: "demo")
    }
}

private final class FakeFilesystemRepository: FilesystemRepository, @unchecked Sendable {
    let paths: ServerPaths
    let listings: [String: [DirectoryNode]]
    var failingPaths: Set<String>
    private(set) var requestedPaths: [String] = []

    init(
        paths: ServerPaths = ServerPaths(home: "/home", directory: "/home", worktree: "/"),
        listings: [String: [DirectoryNode]] = [:],
        failingPaths: Set<String> = []
    ) {
        self.paths = paths
        self.listings = listings
        self.failingPaths = failingPaths
    }

    func getPaths() async throws -> ServerPaths { paths }

    func listDirectory(path: String) async throws -> [DirectoryNode] {
        requestedPaths.append(path)
        if failingPaths.contains(path) { throw TestError.message("Listing failed") }
        return DirectoryListingPolicy.directories(listings[path] ?? [])
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
    private(set) var settledIDs: [String] = []
    var settleError: Error?

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

    func settleSession(id: String, directory: String?) async throws -> Bool {
        settledIDs.append(id)
        if let settleError { throw settleError }
        return true
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
        if let listError { throw listError }
        return messages
    }

    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message { fatalError() }
    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message { fatalError() }
    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {}
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

    func emit(directory: String, sessions: [Session]) {
        let snapshot = SessionListSnapshot(sessions: sessions)
        latest[directory] = snapshot
        continuations[directory]?.values.forEach { $0.yield(snapshot) }
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
