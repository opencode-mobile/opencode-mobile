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

    @Test func showAddProjectSheet_startsAtHomeAndListsDirectories() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: [
                "/home": [
                    DirectoryNode(name: "projects", absolutePath: "/home/projects", isDirectory: true),
                    DirectoryNode(name: ".cache", absolutePath: "/home/.cache", isDirectory: true),
                    DirectoryNode(name: "readme", absolutePath: "/home/readme", isDirectory: false),
                ],
            ]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        guard case let .loaded(_, show, navigator, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(show)
        #expect(navigator?.currentPath == "/home")
        #expect(navigator?.entries.map(\.name) == ["projects"])
        #expect(navigator?.canGoUp == true)
    }

    @Test func selectCurrentDirectory_validatesAndFocuses() async {
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
        await viewModel.selectCurrentDirectory()
        guard case let .loaded(_, show, _, createError) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(show == false)
        #expect(createError == nil)
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/").contains("/home"))
        #expect(projectRepository.validatedDirectories.contains("/home"))
        #expect(viewModel.pendingSelectedFilterId == "/home")
    }

    @Test func selectCurrentDirectory_persistsSelectedPathEvenWhenCurrentResolvesGlobal() async {
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
        await viewModel.selectCurrentDirectory()
        guard case let .loaded(_, show, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(show == false)
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/one", "/home"])
    }

    @Test func selectCurrentDirectory_nestedPathPersistsSelectedDirectory() async {
        let store = FakeConnectionProfileStore(initial: ["/one"])
        let projectRepository = FakeProjectRepository(
            projectsByDirectory: [
                "/repo/android": Project(id: "repo", worktree: "/repo", displayName: "repo"),
            ]
        )
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/repo/android", directory: "/repo/android", worktree: "/"),
            listings: ["/repo/android": []]
        )
        let viewModel = makeViewModel(
            projectRepository: projectRepository,
            filesystemRepository: filesystem,
            connectionProfileStore: store
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        await viewModel.selectCurrentDirectory()
        guard case let .loaded(_, show, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(show == false)
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/one", "/repo/android"])
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
}

@MainActor
private func makeViewModel(
    projectRepository: any ProjectRepository,
    filesystemRepository: any FilesystemRepository = FakeFilesystemRepository(),
    sessionRepository: any SessionRepository = FakeSessionRepository(),
    messageRepository: any MessageRepository = FakeMessageRepository(),
    connectionProfileStore: FakeConnectionProfileStore = FakeConnectionProfileStore(),
    eventStream: FakeForegroundEventStream? = nil,
    newSessionModelSelectionResolver: NewSessionModelSelectionResolver? = nil,
    invalidationAckTimeoutMs: Int = LiveStoreRefreshPolicy.invalidationAckTimeoutMs
) -> ProjectListHomeViewModel {
    let eventStream = eventStream ?? FakeForegroundEventStream()
    // Mutations (createSession) require Connected; default fakes start Idle.
    eventStream.setConnectionState(.connected)
    let manager = ActiveConnectionManager()
    manager.apply(ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: ""))
    let coordinator = ConnectionSessionCoordinator(
        activeConnectionManager: manager,
        connectionProfileStore: connectionProfileStore
    )
    let sessionStatusLiveStore = DefaultSessionStatusLiveStore(
        sessionRepository: sessionRepository,
        eventStream: eventStream,
        invalidationAckTimeoutMs: invalidationAckTimeoutMs
    )
    let sessionLiveStore = DefaultSessionLiveStore(
        sessionRepository: sessionRepository,
        eventStream: eventStream,
        sessionCache: NoOpSessionCache.shared,
        invalidationAckTimeoutMs: invalidationAckTimeoutMs
    )
    let sessionReadyStore = DefaultSessionReadyStore(
        sessionStatusLiveStore: sessionStatusLiveStore,
        eventStream: eventStream
    )
    let permissionLiveStore = DefaultPermissionLiveStore(
        sessionRepository: sessionRepository,
        eventStream: eventStream
    )
    let questionLiveStore = DefaultQuestionLiveStore(
        sessionRepository: sessionRepository,
        eventStream: eventStream
    )
    let sessionTodoLiveStore = DefaultSessionTodoLiveStore(
        sessionRepository: sessionRepository,
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
        permissionLiveStore: permissionLiveStore,
        questionLiveStore: questionLiveStore,
        sessionTodoLiveStore: sessionTodoLiveStore,
        sessionReadyStore: sessionReadyStore,
        foregroundEventStream: eventStream,
        connectionSessionCoordinator: coordinator,
        newSessionModelSelectionResolver: newSessionModelSelectionResolver
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
    private(set) var validatedDirectories: [String?] = []

    init(
        shouldFailProbe: Bool = false,
        bootWorktree: String = "/boot",
        projects: [Project] = [],
        projectsByDirectory: [String: Project] = [:]
    ) {
        self.shouldFailProbe = shouldFailProbe
        self.bootWorktree = bootWorktree
        self.projects = projects
        self.projectsByDirectory = projectsByDirectory
    }

    func probeServer() async throws {
        if shouldFailProbe { throw ServerProbeError.transport("down") }
    }

    func listProjects() async throws -> [Project] { projects }

    func getCurrentProject(directory: String?) async throws -> Project {
        validatedDirectories.append(directory)
        if let directory, let project = projectsByDirectory[directory] {
            return project
        }
        return Project(id: "p1", worktree: directory ?? bootWorktree, displayName: "demo")
    }
}

private struct FakeFilesystemRepository: FilesystemRepository {
    let paths: ServerPaths
    let listings: [String: [DirectoryNode]]

    init(
        paths: ServerPaths = ServerPaths(home: "/home", directory: "/home", worktree: "/"),
        listings: [String: [DirectoryNode]] = [:]
    ) {
        self.paths = paths
        self.listings = listings
    }

    func getPaths() async throws -> ServerPaths { paths }

    func listDirectory(path: String) async throws -> [DirectoryNode] {
        DirectoryListingPolicy.prepare(listings[path] ?? [])
    }
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
