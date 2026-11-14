import Foundation
import Testing
@testable import OpenCode_Mobile

/// #1004: the in-memory New session draft store and where a fresh draft starts.
@MainActor
struct NewSessionDraftTests {
    // MARK: Store

    @Test func storeKeepsOneDraftPerProfile() {
        let store = NewSessionDraftStore()
        store.save(draft(text: "work"), forProfile: "work-server")
        store.save(draft(text: "home", directory: "/home/repo"), forProfile: "home-server")

        #expect(store.draft(forProfile: "work-server")?.composer.text == "work")
        #expect(store.draft(forProfile: "home-server")?.composer.text == "home")
        #expect(store.draft(forProfile: "other-server") == nil)
    }

    @Test func openRestoresADraftWithTextEvenWhenHomeStartsElsewhere() {
        let store = NewSessionDraftStore()
        store.save(draft(text: "keep me", directory: "/src/repo"), forProfile: "p")

        let opened = store.open(profile: "p", start: NewSessionDraft(projectKey: "path:/play", projectDirectory: "/play"))

        #expect(opened.composer.text == "keep me")
        #expect(opened.directory == "/src/repo")
    }

    @Test func openRestoresADraftWithOnlyAttachments() {
        let store = NewSessionDraftStore()
        var kept = draft(text: "")
        kept.attachments = [Attachment(id: "a1", filename: "notes.txt", mime: "text/plain", kind: .text, dataURL: "")]
        store.save(kept, forProfile: "p")

        let opened = store.open(profile: "p", start: NewSessionDraft(projectKey: "path:/play", projectDirectory: "/play"))

        #expect(opened.attachments.map(\.id) == ["a1"])
    }

    @Test func openReplacesADraftWithoutContentSoAPlaceAloneIsNotKept() {
        let store = NewSessionDraftStore()
        var placeOnly = draft(text: "   ")
        placeOnly.place = .newWorktree(baseBranch: "main")
        store.save(placeOnly, forProfile: "p")
        let start = NewSessionDraft(projectKey: "path:/play", projectDirectory: "/play")

        let opened = store.open(profile: "p", start: start)

        #expect(opened == start)
        #expect(store.draft(forProfile: "p") == start)
    }

    @Test func forcePlaceKeepsTheTextButMovesToTheWorktree() {
        let store = NewSessionDraftStore()
        var kept = draft(text: "fix the tests")
        kept.createdWorktree = CreatedWorktree(directory: "/wt/old", name: "old", branch: nil)
        kept.place = .newWorktree(baseBranch: nil)
        store.save(kept, forProfile: "p")
        let start = NewSessionDraft(
            projectKey: "prj",
            projectDirectory: "/src/repo",
            place: .worktree(directory: "/src/repo-wt", branch: "feature/x")
        )

        let opened = store.open(profile: "p", start: start, forcePlace: true)

        #expect(opened.composer.text == "fix the tests")
        #expect(opened.place == .worktree(directory: "/src/repo-wt", branch: "feature/x"))
        #expect(opened.directory == "/src/repo-wt")
        #expect(opened.createdWorktree == nil)
    }

    @Test func clearDropsTheDraft() {
        let store = NewSessionDraftStore()
        store.save(draft(text: "x"), forProfile: "p")

        store.clear(profile: "p", discardingAttachments: false)

        #expect(store.draft(forProfile: "p") == nil)
    }

    @Test func profileKeyFallsBackToAddressAndUser() {
        let withID = ConnectionProfile(baseURL: "https://example.com", username: "opencode", password: "", id: "profile-1")
        let withoutID = ConnectionProfile(baseURL: "https://example.com/", username: "opencode", password: "")

        #expect(NewSessionDraftStore.key(for: withID) == "profile-1")
        #expect(NewSessionDraftStore.key(for: withoutID) == NewSessionDraftStore.key(
            for: ConnectionProfile(baseURL: "https://example.com", username: "opencode", password: "")
        ))
    }

    @Test func directoryFollowsThePlace() {
        var draft = NewSessionDraft(projectKey: "prj", projectDirectory: "/src/repo/")
        #expect(draft.directory == "/src/repo")
        draft.place = .worktree(directory: "/src/repo-wt", branch: "feature/x")
        #expect(draft.directory == "/src/repo-wt")
        // A New worktree binds to the main checkout until the first send creates it.
        draft.place = .newWorktree(baseBranch: "main")
        #expect(draft.directory == "/src/repo")
    }

    @Test func movingToAnotherProjectStartsOnItsMainCheckout() {
        var draft = draft(text: "keep")
        draft.place = .newWorktree(baseBranch: "main")
        draft.createdWorktree = CreatedWorktree(directory: "/wt/a", name: "a", branch: nil)

        let moved = draft.movingTo(projectKey: "path:/play", projectDirectory: "/play")

        #expect(moved.place == .local)
        #expect(moved.directory == "/play")
        #expect(moved.createdWorktree == nil)
        #expect(moved.composer.text == "keep")
    }

    // MARK: Starting place

    @Test func homeStartsInTheFilteredProjectOnItsMainCheckout() {
        let start = NewSessionDraftStart.fromHome(
            projects: [repo, playground],
            selectedFilterId: "prj",
            worktreesEnabled: true,
            recentSessions: []
        )

        #expect(start?.projectKey == "prj")
        #expect(start?.place == .local)
        #expect(start?.directory == "/src/repo")
    }

    @Test func homeStartsInTheFilteredWorktree() {
        let scope = HomeScope.worktree(projectKey: "prj", directory: "/src/repo-wt").id

        let start = NewSessionDraftStart.fromHome(
            projects: [repo, playground],
            selectedFilterId: scope,
            worktreesEnabled: true,
            recentSessions: []
        )

        #expect(start?.place == .worktree(directory: "/src/repo-wt", branch: "feature/x"))
        #expect(start?.directory == "/src/repo-wt")
    }

    @Test func homeInAllStartsWhereTheMostRecentSessionRuns() {
        let start = NewSessionDraftStart.fromHome(
            projects: [repo, playground],
            selectedFilterId: SessionListFilterTab.allId,
            worktreesEnabled: true,
            recentSessions: [
                NewSessionRecentSession(projectKey: "path:/play", directory: "/play", updatedAtEpochMs: 10),
                NewSessionRecentSession(projectKey: "prj", directory: "/src/repo-wt", updatedAtEpochMs: 30),
                NewSessionRecentSession(projectKey: "prj", directory: "/src/repo", updatedAtEpochMs: 20),
            ]
        )

        #expect(start?.projectKey == "prj")
        #expect(start?.place == .worktree(directory: "/src/repo-wt", branch: "feature/x"))
    }

    @Test func homeInAllWithWorktreesOffStartsOnTheRecentProjectsMainCheckout() {
        let start = NewSessionDraftStart.fromHome(
            projects: [repo, playground],
            selectedFilterId: SessionListFilterTab.allId,
            worktreesEnabled: false,
            recentSessions: [NewSessionRecentSession(projectKey: "prj", directory: "/src/repo-wt", updatedAtEpochMs: 30)]
        )

        #expect(start?.place == .local)
        #expect(start?.directory == "/src/repo")
    }

    @Test func homeInAllWithoutSessionsStartsInTheFirstProject() {
        let start = NewSessionDraftStart.fromHome(
            projects: [playground, repo],
            selectedFilterId: SessionListFilterTab.allId,
            worktreesEnabled: true,
            recentSessions: []
        )

        #expect(start?.projectKey == "path:/play")
        #expect(start?.place == .local)
    }

    @Test func homeWithoutProjectsHasNoStart() {
        #expect(NewSessionDraftStart.fromHome(
            projects: [],
            selectedFilterId: SessionListFilterTab.allId,
            worktreesEnabled: true,
            recentSessions: []
        ) == nil)
    }

    @Test func directoryStartsInItsProjectAndWorktree() {
        let main = NewSessionDraftStart.fromDirectory("/src/repo", projects: [repo], worktreesEnabled: true)
        let worktree = NewSessionDraftStart.fromDirectory("/src/repo-wt/", projects: [repo], worktreesEnabled: true)
        let unknown = NewSessionDraftStart.fromDirectory("/elsewhere", projects: [repo], worktreesEnabled: true)

        #expect(main.projectKey == "prj")
        #expect(main.place == .local)
        #expect(worktree.place == .worktree(directory: "/src/repo-wt", branch: "feature/x"))
        #expect(worktree.projectDirectory == "/src/repo")
        #expect(unknown.projectKey == nil)
        #expect(unknown.directory == "/elsewhere")
    }

    // MARK: Fixtures

    private func draft(text: String, directory: String = "/src/repo") -> NewSessionDraft {
        NewSessionDraft(projectKey: "prj", projectDirectory: directory, composer: ComposerDraft(text: text))
    }

    private var repo: HomeProject {
        let resolution = HomeProjectResolution(id: "prj", canonical: "/src/repo", isRepository: true)
        return HomeProjectGrouping.group(
            addedPaths: ["/src/repo", "/src/repo-wt"],
            resolutions: ["/src/repo": resolution, "/src/repo-wt": resolution],
            branches: ["/src/repo": "main", "/src/repo-wt": "feature/x"]
        )[0]
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
}
