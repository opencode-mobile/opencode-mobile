import Foundation
import Testing
@testable import OpenCode_Mobile

/// #833: the Worktrees screen model, remove flow, and what it asks Home to do.
@MainActor
struct WorktreesViewModelTests {
    @Test func groupsWorktreesUnderTheirProjectWithSessionsRunningAndChanges() async {
        let feed = makeFeed(sessions: [session("s1", "/wt/search", running: true), session("s2", "/wt/search")])
        let model = makeModel(feed, changes: ["/wt/search": 3, "/wt/typo": 0])
        await settle()

        #expect(model.worktreeCount == 2)
        #expect(model.projectCount == 2)
        let acme = model.groups[0]
        #expect(acme.mainCheckout == "/repo")
        #expect(acme.worktrees.map(\.name) == ["search", "typo"])
        #expect(acme.worktrees[0].sessionCount == 2)
        #expect(acme.worktrees[0].isRunning)
        #expect(acme.worktrees[0].changeCount == 3)
        #expect(acme.worktrees[1].changeCount == 0)
        #expect(model.groups[1].worktrees.isEmpty)
    }

    @Test func aRunningSessionBlocksRemoveAndCanBeOpened() async {
        let feed = makeFeed(sessions: [session("s1", "/wt/search", running: true, title: "Search")])
        let model = makeModel(feed)

        model.requestRemove(directory: "/wt/search")
        guard case let .blocked(name, running)? = model.dialog else {
            Issue.record("expected the blocked dialog")
            return
        }
        #expect(name == "search")
        model.showRunningSession()

        #expect(model.dialog == nil)
        #expect(model.sessionToOpen == running)
        #expect(running.id == "s1")
        #expect(running.worktree == "/wt/search")
    }

    @Test func uncommittedChangesForceTheRemoveAndTheRowLeaves() async {
        let feed = makeFeed(sessions: [session("s2", "/wt/search")])
        let repository = RemoveRepository()
        let model = makeModel(feed, repository: repository, changes: ["/wt/search": 2])
        await settle()

        model.requestRemove(directory: "/wt/search")
        #expect(model.dialog == .confirm(directory: "/wt/search", name: "search", changeCount: 2, changesUnknown: false, sessionCount: 1))
        #expect(model.dialog?.losesChanges == true)
        await model.confirmRemove()

        #expect(repository.requests == [RemoveWorktreeRequest(projectID: "acme", projectDirectory: "/repo", directory: "/wt/search", force: true)])
        #expect(model.groups[0].worktrees.map(\.name) == ["typo"])
        guard case let .removed(name, _)? = model.snackbar else {
            Issue.record("expected the removed snackbar")
            return
        }
        #expect(name == "search")
        #expect(feed.removed == ["/wt/search": "acme"])
    }

    @Test func aCleanWorktreeIsRemovedWithoutForce() async {
        let repository = RemoveRepository()
        let model = makeModel(makeFeed(), repository: repository, changes: ["/wt/typo": 0])
        await settle()

        model.requestRemove(directory: "/wt/typo")
        #expect(model.dialog?.losesChanges == false)
        await model.confirmRemove()

        #expect(repository.requests.map(\.force) == [false])
    }

    @Test func forceRequiredAsksAgainWithUnknownChanges() async {
        let repository = RemoveRepository(errors: [.forceRequired(message: "dirty")])
        let model = makeModel(makeFeed(), repository: repository)

        model.requestRemove(directory: "/wt/typo")
        await model.confirmRemove()
        guard case let .confirm(_, _, _, changesUnknown, _, _)? = model.dialog else {
            Issue.record("expected the confirmation again")
            return
        }
        #expect(changesUnknown)
        await model.confirmRemove()

        #expect(repository.requests.map(\.force) == [false, true])
    }

    @Test func anUnreadableChangeCountWarnsInsteadOfActingClean() async {
        // No count for /wt/typo, as on a server that cannot tell.
        let repository = RemoveRepository()
        let model = makeModel(makeFeed(), repository: repository)
        await settle()

        model.requestRemove(directory: "/wt/typo")
        guard case let .confirm(_, _, _, _, _, changesUnchecked)? = model.dialog else {
            Issue.record("expected the confirmation")
            return
        }
        #expect(changesUnchecked)
        #expect(model.dialog?.warnsAboutChanges == true)
        #expect(model.dialog?.losesChanges == false)
        #expect(WorktreesCopy.removeNotes(model.dialog).count == 1)
        await model.confirmRemove()

        // v2 does not force on a guess; a dirty checkout comes back as forceRequired.
        #expect(repository.requests.map(\.force) == [false])
    }

    @Test func aFailureKeepsTheRowAndOffersRetry() async {
        let repository = RemoveRepository(errors: [.failed(message: "Invalid worktree directory")])
        let feed = makeFeed()
        let model = makeModel(feed, repository: repository, changes: ["/wt/typo": 0])
        await settle()

        model.requestRemove(directory: "/wt/typo")
        await model.confirmRemove()

        guard case let .failed(_, directory, message, _)? = model.snackbar else {
            Issue.record("expected the failure snackbar")
            return
        }
        #expect(message == "Invalid worktree directory")
        let row = model.groups[0].worktrees.first { $0.name == "typo" }
        #expect(row?.isRemoving == false)
        #expect(feed.removed.isEmpty)

        model.retry(directory: directory)
        guard case .confirm? = model.dialog else {
            Issue.record("expected Retry to confirm again")
            return
        }
    }

    @Test func showOnHomeAndNewSessionGoBackToHome() {
        let feed = makeFeed()
        var requests: [HomeWorktreeRequest] = []
        feed.onRequest = { requests.append($0) }
        let model = makeModel(feed)

        model.openActions(directory: "/wt/search")
        #expect(model.actions?.projectName == "Acme")
        model.showOnHome(directory: "/wt/search")
        #expect(model.actions == nil)
        model.newSessionHere(directory: "/wt/typo")

        #expect(requests == [.showSessions(projectKey: "acme", directory: "/wt/search"), .newSession(directory: "/wt/typo")])
        #expect(model.closeToken == 2)
    }

    @Test func menuSubtitleCountsWorktreesAcrossProjects() {
        #expect(WorktreesCopy.menuSubtitle(makeFeed().snapshot) == "2 across 1 project")
        #expect(WorktreesCopy.menuSubtitle(HomeWorktreesSnapshot()) == "None")
    }

    @Test func aFolderTheServerDidNotListAsAWorktreeIsNotARow() {
        // The added path /tmp/repo is the main checkout through a symlink: Home groups it as a
        // non-main entry, but the server never listed it as a worktree.
        let feed = HomeWorktreesFeed()
        feed.publish(HomeWorktreesSnapshot(
            projects: [
                HomeProject(
                    key: "acme",
                    projectID: "acme",
                    displayName: "Acme",
                    memberPaths: ["/tmp/repo"],
                    isRepository: true,
                    worktrees: [
                        HomeWorktree(directory: "/private/tmp/repo", branch: "main", isMain: true),
                        HomeWorktree(directory: "/tmp/repo", branch: "main", isMain: false),
                        HomeWorktree(directory: "/wt/a", branch: nil, isMain: false),
                    ]
                ),
            ],
            removableDirectories: ["/wt/a"]
        ))
        let model = makeModel(feed)

        #expect(model.groups.first?.worktrees.map(\.directory) == ["/wt/a"])
        model.requestRemove(directory: "/tmp/repo")
        #expect(model.dialog == nil)
        #expect(feed.snapshot.worktreeCount == 1)
    }

    @Test func oneFolderUnderTwoSpellingsIsOneRow() {
        // v1 lists /tmp/…; Home also knows the folder (and the main checkout) as /private/tmp/….
        let feed = HomeWorktreesFeed()
        feed.publish(HomeWorktreesSnapshot(
            projects: [
                HomeProject(
                    key: "acme",
                    projectID: "acme",
                    displayName: "Acme",
                    memberPaths: ["/tmp/repo"],
                    isRepository: true,
                    worktrees: [
                        HomeWorktree(directory: "/private/tmp/repo", branch: "main", isMain: true),
                        HomeWorktree(directory: "/private/tmp/wt/v1-cleanup", branch: "opencode/v1-cleanup", isMain: false),
                        HomeWorktree(directory: "/tmp/repo", branch: "main", isMain: false),
                        HomeWorktree(directory: "/tmp/wt/v1-cleanup", branch: "opencode/v1-cleanup", isMain: false),
                    ]
                ),
            ],
            sessions: [session("s1", "/private/tmp/wt/v1-cleanup")],
            removableDirectories: ["/tmp/wt/v1-cleanup", "/tmp/repo"]
        ))
        let model = makeModel(feed)

        let rows = model.groups.first?.worktrees ?? []
        #expect(rows.map(\.directory) == ["/tmp/wt/v1-cleanup"])
        #expect(rows.first?.name == "v1-cleanup")
        #expect(rows.first?.sessionCount == 1)
        #expect(feed.snapshot.worktreeCount == 1)
        #expect(HomeRowLocation.isRemoved("/private/tmp/wt/v1-cleanup", in: ["/tmp/wt/v1-cleanup"]))
    }

    @Test func anUnreachableServerGetsAPlainMessage() async {
        let repository = RemoveRepository(errors: [.unreachable(message: "The network connection was lost.")])
        let model = makeModel(makeFeed(), repository: repository, changes: ["/wt/typo": 0])
        await settle()

        model.requestRemove(directory: "/wt/typo")
        await model.confirmRemove()

        guard case let .failed(_, _, message, _)? = model.snackbar else {
            Issue.record("expected the failure snackbar")
            return
        }
        #expect(message == nil)
        #expect(WorktreesCopy.menuSubtitle(HomeWorktreesSnapshot(worktreesLoaded: false)) == nil)
    }

    @Test func theMainCheckoutCannotBeRemoved() {
        let model = makeModel(makeFeed())

        model.requestRemove(directory: "/repo")
        model.openActions(directory: "/repo")

        #expect(model.dialog == nil)
        #expect(model.actions == nil)
    }

    @Test func removedWorktreeSessionsStayOnHomeStruckThrough() {
        let project = HomeProject(
            key: "acme",
            projectID: "acme",
            displayName: "Acme",
            memberPaths: ["/repo"],
            isRepository: true,
            worktrees: [HomeWorktree(directory: "/repo", branch: "main", isMain: true)]
        )

        let removed = HomeRowLocation.make(
            project: project,
            sessionDirectory: "/wt/gone",
            includesProjectName: false,
            removedDirectories: ["/wt/gone"]
        )
        let other = HomeRowLocation.make(project: project, sessionDirectory: "/wt/other", includesProjectName: false)

        #expect(removed?.parts == [.removed("gone")])
        #expect(other?.parts == [.text("other")])
        let segments = removed?.listItemSegments(tone: .tertiary) ?? []
        #expect(segments.map(\.text) == ["gone", " · worktree removed"])
        #expect(segments.first?.tone == .removed)
    }

    @Test func removedWorktreeExtrasStayEvenWithoutASessionDirectory() {
        let fold = HomeWorktreeSessionFold()
        let extra = SessionListItem(id: "gone", title: "Gone", worktree: "/wt/gone", updatedAtEpochMs: 2)
        let main = SessionListItem(id: "main", title: "Main", worktree: "/repo", updatedAtEpochMs: 1)
        _ = fold.apply(addedPath: "/repo", current: [main], nextExtras: [extra])

        fold.retainedDirectories = ["/wt/gone"]
        // v1 no longer lists the removed worktree.
        let merged = fold.apply(addedPath: "/repo", current: [main], nextExtras: [])

        #expect(merged.map(\.id) == ["gone", "main"])
    }

    // MARK: Helpers

    private func makeModel(
        _ feed: HomeWorktreesFeed,
        repository: any WorktreeRepository = RemoveRepository(),
        changes: [String: Int] = [:]
    ) -> WorktreesViewModel {
        let model = WorktreesViewModel(feed: feed, repository: repository, changeCount: { changes[$0] })
        model.sync()
        return model
    }

    private func makeFeed(sessions: [HomeWorktreeSession] = []) -> HomeWorktreesFeed {
        let feed = HomeWorktreesFeed()
        feed.publish(HomeWorktreesSnapshot(
            projects: [
                HomeProject(
                    key: "acme",
                    projectID: "acme",
                    displayName: "Acme",
                    memberPaths: ["/repo"],
                    isRepository: true,
                    worktrees: [
                        HomeWorktree(directory: "/repo", branch: "main", isMain: true),
                        HomeWorktree(directory: "/wt/search", branch: "feature/search", isMain: false),
                        HomeWorktree(directory: "/wt/typo", branch: nil, isMain: false),
                    ]
                ),
                HomeProject(
                    key: "web",
                    projectID: "web",
                    displayName: "Web",
                    memberPaths: ["/web"],
                    isRepository: true,
                    worktrees: [HomeWorktree(directory: "/web", branch: nil, isMain: true)]
                ),
                HomeProject(
                    key: "path:/notes",
                    projectID: nil,
                    displayName: "notes",
                    memberPaths: ["/notes"],
                    isRepository: false,
                    worktrees: []
                ),
            ],
            sessions: sessions,
            removableDirectories: ["/wt/search", "/wt/typo"]
        ))
        return feed
    }

    private func session(_ id: String, _ directory: String, running: Bool = false, title: String? = nil) -> HomeWorktreeSession {
        HomeWorktreeSession(id: id, title: title ?? id, directory: directory, isRunning: running)
    }

    /// Lets the change-count tasks finish.
    private func settle() async {
        for _ in 0 ..< 20 { await Task.yield() }
    }
}

private final class RemoveRepository: WorktreeRepository, @unchecked Sendable {
    private var errors: [WorktreeRemoveError]
    private(set) var requests: [RemoveWorktreeRequest] = []

    init(errors: [WorktreeRemoveError] = []) {
        self.errors = errors
    }

    func remove(_ request: RemoveWorktreeRequest) async throws {
        requests.append(request)
        if !errors.isEmpty { throw errors.removeFirst() }
    }

    func load(addedPath: String, projectID: String?) async -> WorktreeLoad { .unloaded }
    func loadRefreshed(addedPath: String, projectID: String?) async -> WorktreeLoad { .unloaded }
    func refresh(projectID: String) async -> Bool { false }
    func refetch(projectID: String) async -> WorktreeLoad { .unloaded }
    func onDisconnected() {}
    func onConnection(key: String) {}
    func create(_ request: CreateWorktreeRequest) async throws -> CreatedWorktree {
        throw WorktreeCreateError(message: "unused")
    }
    func baseBranches(directory: String) async throws -> [String] { [] }
    var canCreateWorktrees: Bool { true }
}
