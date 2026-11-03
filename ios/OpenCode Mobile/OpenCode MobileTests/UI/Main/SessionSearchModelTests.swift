import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionSearchModelTests {
    private let alpha = "/work/alpha"
    private let beta = "/work/beta"

    // MARK: Local results

    @Test func localResults_updateOnEveryKeystroke() throws {
        let (model, _, _) = makeModel()
        model.open(homeScopeId: HomeScope.allID)

        model.updateQuery("lo")
        #expect(resultIDs(model) == ["a-login", "b-logs"])

        model.updateQuery("log")
        #expect(resultIDs(model) == ["a-login", "b-logs"])

        model.updateQuery("logi")
        #expect(resultIDs(model) == ["a-login"])
        guard case let .results(results, _) = model.content else {
            Issue.record("expected results")
            return
        }
        let result = try #require(results.first)
        let range = try #require(result.matchRange)
        #expect(result.row.title[range] == "Logi")
    }

    @Test func emptyQuery_showsTheHintForTheScope() {
        let (model, _, _) = makeModel()
        model.open(homeScopeId: HomeScope.allID)

        #expect(model.content == .hint(scopeTitle: "All projects"))
        model.updateQuery("   ")
        #expect(model.content == .hint(scopeTitle: "All projects"))
    }

    // MARK: Server search

    @Test func serverSearch_runsOnceAfterTheDebounce() async {
        let (model, repository, sleeper) = makeModel()
        model.open(homeScopeId: HomeScope.allID)

        model.updateQuery("l")
        model.updateQuery("lo")
        #expect(await waitUntil { sleeper.durations.count == 2 })
        await settle()
        #expect(repository.calls.isEmpty)
        #expect(sleeper.durations == [SessionSearchModel.debounce, SessionSearchModel.debounce])

        await fireDebounce(sleeper)
        #expect(await waitUntil { !model.busy })
        #expect(Set(repository.calls) == [
            .init(query: "lo", directory: alpha),
            .init(query: "lo", directory: beta),
        ])
    }

    @Test func serverMerge_homeRowWinsAndServerOnlyRowsJoinNewestFirst() async throws {
        let (model, repository, sleeper) = makeModel()
        repository.results[alpha] = [
            // Server copy of a Home row: Home's row (with live status) wins.
            session("a-login", title: "Login flow (server copy)", directory: alpha, updated: 30),
            // Not on Home: built with Home's row builder.
            session("a-old", title: "Old login spike", directory: alpha, updated: 5),
            session("a-new", title: "New login screen", directory: alpha, updated: 50),
        ]
        model.open(homeScopeId: HomeScope.allID)

        model.updateQuery("login")
        await fireDebounce(sleeper)
        #expect(await waitUntil { !model.busy })

        #expect(resultIDs(model) == ["a-new", "a-login", "a-old"])
        guard case let .results(results, _) = model.content else {
            Issue.record("expected results")
            return
        }
        let homeRow = try #require(results.first { $0.id == "a-login" }?.row)
        #expect(homeRow.title == "Login flow")
        #expect(homeRow.status == .running)
        let serverRow = try #require(results.first { $0.id == "a-new" }?.row)
        #expect(serverRow.worktree == HomeProjectGrouping.pathKey(alpha))
        #expect(serverRow.letter == "A")
        #expect(serverRow.directory == alpha)
    }

    @Test func staleReply_isDropped() async {
        let (model, repository, sleeper) = makeModel()
        repository.holdsReplies = true
        repository.results[alpha] = [session("a-stale", title: "logic stale", directory: alpha, updated: 99)]
        model.open(homeScopeId: HomeScope.project(HomeProjectGrouping.pathKey(alpha)).id)

        model.updateQuery("log")
        await fireDebounce(sleeper)
        #expect(await waitUntil { repository.heldCount == 1 })

        repository.results[alpha] = []
        model.updateQuery("logi")
        await fireDebounce(sleeper)
        #expect(await waitUntil { repository.heldCount == 2 })

        // The newer reply lands first, then the older one.
        repository.release(at: 1)
        #expect(await waitUntil { !model.busy })
        repository.release(at: 0)
        await settle()

        #expect(resultIDs(model) == ["a-login"])
    }

    @Test func scopeChange_searchesAtOnceForThatProjectOnly() async {
        let (model, repository, sleeper) = makeModel()
        model.open(homeScopeId: HomeScope.allID)
        model.updateQuery("log")
        await fireDebounce(sleeper)
        #expect(await waitUntil { !model.busy })
        let sleepsBefore = sleeper.durations.count
        repository.calls = []

        model.selectScope(HomeProjectGrouping.pathKey(beta))

        #expect(model.busy)
        #expect(await waitUntil { !model.busy })
        #expect(sleeper.durations.count == sleepsBefore)
        #expect(repository.calls == [.init(query: "log", directory: beta)])
        #expect(resultIDs(model) == ["b-logs"])
        #expect(model.scopeTitle == "beta")
    }

    @Test func noMatch_offersAllProjectsOnlyWhenAnotherProjectMatches() async {
        let (model, _, sleeper) = makeModel()
        model.open(homeScopeId: HomeProjectGrouping.pathKey(beta))

        model.updateQuery("login")
        await fireDebounce(sleeper)
        #expect(await waitUntil { !model.busy })
        #expect(model.content == .noMatch(query: "login", scopeTitle: "beta", offerAllProjects: true, offerArchived: false))

        model.updateQuery("nothing")
        await fireDebounce(sleeper)
        #expect(await waitUntil { !model.busy })
        #expect(model.content == .noMatch(query: "nothing", scopeTitle: "beta", offerAllProjects: false, offerArchived: false))

        model.updateQuery("login")
        model.searchAllProjects()
        #expect(model.scope == HomeScope.allID)
        #expect(resultIDs(model) == ["a-login"])
    }

    @Test func busy_coversThePendingSearchAndShowsSearchingUntilSomethingMatches() async {
        let (model, repository, sleeper) = makeModel()
        repository.holdsReplies = true
        repository.results[alpha] = [session("a-zeta", title: "Zeta notes", directory: alpha, updated: 7)]
        model.open(homeScopeId: HomeScope.allID)

        model.updateQuery("zeta")
        #expect(model.busy)
        #expect(model.content == .searching)

        await fireDebounce(sleeper)
        #expect(await waitUntil { repository.heldCount == 2 })
        #expect(model.busy)
        repository.releaseAll()
        #expect(await waitUntil { !model.busy })
        #expect(resultIDs(model) == ["a-zeta"])

        model.updateQuery("zet")
        #expect(model.busy)
        model.clearQuery()
        #expect(!model.busy)
        #expect(model.content == .hint(scopeTitle: "All projects"))
    }

    @Test func serverFailure_keepsLocalResultsAndStopsTheSpinner() async {
        let (model, repository, sleeper) = makeModel()
        repository.failingDirectories = [alpha, beta]
        model.open(homeScopeId: HomeScope.allID)

        model.updateQuery("log")
        #expect(resultIDs(model) == ["a-login", "b-logs"])
        await fireDebounce(sleeper)
        #expect(await waitUntil { !model.busy })
        #expect(resultIDs(model) == ["a-login", "b-logs"])
    }

    // MARK: Open, scope, Esc

    @Test func open_resetsTheQueryAndMapsAWorktreeScopeToItsProject() {
        let (model, _, _) = makeModel()
        model.open(homeScopeId: HomeScope.allID)
        model.updateQuery("log")
        model.close()

        let worktreeScope = HomeScope.worktree(projectKey: HomeProjectGrouping.pathKey(alpha), directory: alpha).id
        model.open(homeScopeId: worktreeScope)

        #expect(model.isPresented)
        #expect(model.query.isEmpty)
        #expect(model.scope == HomeProjectGrouping.pathKey(alpha))
        #expect(model.scopeOptions.map(\.id) == [
            HomeScope.allID, HomeProjectGrouping.pathKey(alpha), HomeProjectGrouping.pathKey(beta),
        ])
        #expect(model.content == .hint(scopeTitle: "alpha"))
    }

    @Test func escape_clearsTheQueryFirstThenCloses() {
        let (model, _, _) = makeModel()
        model.open(homeScopeId: HomeScope.allID)
        model.updateQuery("log")

        model.escape()
        #expect(model.query.isEmpty)
        #expect(model.isPresented)

        model.escape()
        #expect(!model.isPresented)
    }

    @Test func homeChange_dropsAScopeThatLeftHome() {
        let (model, _, _) = makeModel()
        model.open(homeScopeId: HomeProjectGrouping.pathKey(beta))

        model.homeChanged(makeHome(paths: [alpha]))

        #expect(model.scope == HomeScope.allID)
    }

    // MARK: Include archived

    private var archivedFixture: [String: [Session]] {
        [
            alpha: [
                archivedSession("a-spike", title: "Login spike", directory: alpha, archived: 100),
                archivedSession("a-spike-2", title: "Login spike two", directory: alpha, archived: 200),
            ],
            beta: [
                archivedSession("b-notes", title: "Release notes", directory: beta, archived: 150),
            ],
        ]
    }

    @Test func includeArchived_unsupported_hasNoChipAndListsNothingArchived() async {
        let (model, repository, sleeper) = makeModel(archivedSupported: false, archived: archivedFixture)
        model.open(homeScopeId: HomeScope.allID)
        await model.settleArchived()

        #expect(model.archive == nil)
        #expect(repository.archivedCalls.isEmpty)

        model.includeArchived()
        model.toggleIncludeArchived()
        await search(model, "spike", sleeper)
        #expect(model.archive == nil)
        #expect(model.content == .noMatch(query: "spike", scopeTitle: "All projects", offerAllProjects: false, offerArchived: false))
        #expect(repository.archivedCalls.isEmpty)
    }

    @Test func includeArchived_countsArchivedSessionsInScopeAfterOneListing() async {
        let (model, repository, sleeper) = makeModel(archivedSupported: true, archived: archivedFixture)
        model.open(homeScopeId: HomeScope.allID)

        // Counting: the chip reads plain "Include archived".
        #expect(model.archive == SessionSearchArchive(included: false, count: nil))
        await model.settleArchived()
        #expect(model.archive == SessionSearchArchive(included: false, count: 3))
        #expect(Set(repository.archivedCalls) == [alpha, beta])

        model.selectScope(HomeProjectGrouping.pathKey(beta))
        #expect(model.archive?.count == 1)
        model.selectScope(HomeProjectGrouping.pathKey(alpha))
        #expect(model.archive?.count == 2)

        // Typing matches the listing locally: no further archived requests.
        await search(model, "spike", sleeper)
        model.updateQuery("spik")
        #expect(repository.archivedCalls.count == 2)
    }

    @Test func includeArchived_showsArchivedSectionAfterActiveAndStartsOffOnEveryOpen() async throws {
        let (model, _, sleeper) = makeModel(archivedSupported: true, archived: archivedFixture)
        model.open(homeScopeId: HomeScope.allID)
        await model.settleArchived()

        await search(model, "login", sleeper)
        #expect(resultIDs(model) == ["a-login"])
        #expect(archivedResultIDs(model).isEmpty)

        model.toggleIncludeArchived()
        #expect(model.archive?.included == true)
        #expect(resultIDs(model) == ["a-login"])
        // Most recently archived first.
        #expect(archivedResultIDs(model) == ["a-spike-2", "a-spike"])
        guard case let .results(_, archived) = model.content else {
            Issue.record("expected results")
            return
        }
        let row = try #require(archived.first)
        #expect(row.row.status == .idle)
        #expect(row.row.worktree == HomeProjectGrouping.pathKey(alpha))
        #expect(row.row.directory == alpha)
        let range = try #require(row.matchRange)
        #expect(row.row.title[range] == "Login")

        model.toggleIncludeArchived()
        #expect(archivedResultIDs(model).isEmpty)

        model.toggleIncludeArchived()
        model.close()
        model.open(homeScopeId: HomeScope.allID)
        #expect(model.archive?.included == false)
    }

    @Test func includeArchived_sessionInBothListsShowsOnceAsActive() async {
        var archived = archivedFixture
        archived[alpha, default: []].append(archivedSession("a-login", title: "Login flow", directory: alpha, archived: 300))
        let (model, _, sleeper) = makeModel(archivedSupported: true, archived: archived)
        model.open(homeScopeId: HomeScope.allID)
        await model.settleArchived()

        model.toggleIncludeArchived()
        await search(model, "login", sleeper)

        #expect(resultIDs(model) == ["a-login"])
        #expect(archivedResultIDs(model) == ["a-spike-2", "a-spike"])
    }

    @Test func includeArchived_noMatchOffersOnlyWhatWouldFindSomething() async {
        var archived = archivedFixture
        archived[beta, default: []].append(archivedSession("b-login", title: "Login archive", directory: beta, archived: 160))
        let (model, _, sleeper) = makeModel(archivedSupported: true, archived: archived)
        model.open(homeScopeId: HomeProjectGrouping.pathKey(beta))
        await model.settleArchived()

        // Only another project has an archived match: with the chip off, nothing to offer.
        await search(model, "spike", sleeper)
        #expect(model.content == .noMatch(query: "spike", scopeTitle: "beta", offerAllProjects: false, offerArchived: false))

        // With the chip on, archived matches elsewhere count for "Search all projects".
        model.toggleIncludeArchived()
        #expect(model.content == .noMatch(query: "spike", scopeTitle: "beta", offerAllProjects: true, offerArchived: false))

        // An archived match in scope, chip off: "Include archived".
        model.close()
        model.open(homeScopeId: HomeProjectGrouping.pathKey(alpha))
        await model.settleArchived()
        await search(model, "spike", sleeper)
        #expect(model.content == .noMatch(query: "spike", scopeTitle: "alpha", offerAllProjects: false, offerArchived: true))

        // Only an active match in another project: "Search all projects".
        await search(model, "logs", sleeper)
        #expect(model.content == .noMatch(query: "logs", scopeTitle: "alpha", offerAllProjects: true, offerArchived: false))

        // Both: an archived match here and an active match in another project.
        model.close()
        model.open(homeScopeId: HomeProjectGrouping.pathKey(beta))
        await model.settleArchived()
        await search(model, "login", sleeper)
        #expect(model.content == .noMatch(query: "login", scopeTitle: "beta", offerAllProjects: true, offerArchived: true))
    }

    @Test func includeArchived_fromTheEmptyStateTurnsTheChipOn() async {
        let (model, _, sleeper) = makeModel(archivedSupported: true, archived: archivedFixture)
        model.open(homeScopeId: HomeProjectGrouping.pathKey(alpha))
        await model.settleArchived()
        await search(model, "spike", sleeper)
        #expect(model.content == .noMatch(query: "spike", scopeTitle: "alpha", offerAllProjects: false, offerArchived: true))

        model.includeArchived()

        #expect(model.archive == SessionSearchArchive(included: true, count: 2))
        #expect(resultIDs(model).isEmpty)
        #expect(archivedResultIDs(model) == ["a-spike-2", "a-spike"])
    }

    @Test func includeArchived_listingFailureShowsNoCountAndNoArchivedRows() async {
        let (model, repository, sleeper) = makeModel(archivedSupported: true, archived: archivedFixture)
        repository.archivedFails = true
        model.open(homeScopeId: HomeProjectGrouping.pathKey(alpha))
        await model.settleArchived()

        #expect(model.archive == SessionSearchArchive(included: false, count: nil))
        await search(model, "spike", sleeper)
        #expect(model.content == .noMatch(query: "spike", scopeTitle: "alpha", offerAllProjects: false, offerArchived: false))

        model.toggleIncludeArchived()
        #expect(model.archive == SessionSearchArchive(included: true, count: nil))
        #expect(model.content == .noMatch(query: "spike", scopeTitle: "alpha", offerAllProjects: false, offerArchived: false))
    }

    // MARK: Helpers

    private func makeModel(
        archivedSupported: Bool = false,
        archived: [String: [Session]] = [:]
    ) -> (SessionSearchModel, ScriptedSearchRepository, ManualSleeper) {
        let repository = ScriptedSearchRepository()
        repository.archived = archived
        let sleeper = ManualSleeper()
        let model = SessionSearchModel(
            repository: repository,
            archivedSessionsSupported: { archivedSupported },
            sleep: { duration in try await sleeper.sleep(duration) }
        )
        model.homeChanged(makeHome(paths: [alpha, beta]))
        return (model, repository, sleeper)
    }

    private func makeHome(paths: [String]) -> SessionSearchHome {
        let adapter = SessionDomainAdapter()
        let sessions: [String: [SessionListItem]] = [
            alpha: [
                adapter.listItem(
                    from: session("a-login", title: "Login flow", directory: alpha, updated: 30),
                    worktree: alpha,
                    statusLabel: "Working"
                ),
                adapter.listItem(from: session("a-misc", title: "Misc", directory: alpha, updated: 20), worktree: alpha),
            ],
            beta: [
                adapter.listItem(from: session("b-logs", title: "Read logs", directory: beta, updated: 10), worktree: beta),
            ],
        ]
        let sections = paths.map { path in
            let name = ProjectDomainAdapter.displayName(from: path)
            return ProjectSectionItem(
                id: path,
                worktree: path,
                displayName: name,
                pathSubtitle: path,
                letter: letterCharacter(for: name),
                letterAccent: letterAccent(for: path),
                allSessions: sessions[path] ?? [],
                loadError: nil
            )
        }
        let projects = HomeProjectGrouping.group(addedPaths: paths, resolutions: [:])
        return SessionSearchHome(
            rows: sections.crossProjectSessionListSessions(projects: projects),
            projects: projects,
            sections: sections,
            worktreesEnabled: true
        )
    }

    private func session(_ id: String, title: String, directory: String, updated: Int64) -> Session {
        Session(
            id: id,
            title: title,
            slug: nil,
            projectID: nil,
            directory: directory,
            parentID: nil,
            updatedAtEpochMs: updated
        )
    }

    private func resultIDs(_ model: SessionSearchModel) -> [String] {
        guard case let .results(results, _) = model.content else { return [] }
        return results.map(\.id)
    }

    private func archivedResultIDs(_ model: SessionSearchModel) -> [String] {
        guard case let .results(_, archived) = model.content else { return [] }
        return archived.map(\.id)
    }

    private func archivedSession(_ id: String, title: String, directory: String, archived: Int64) -> Session {
        Session(
            id: id,
            title: title,
            slug: nil,
            projectID: nil,
            directory: directory,
            parentID: nil,
            updatedAtEpochMs: 1,
            archivedAtEpochMs: archived
        )
    }

    /// Searches for `query` and waits until the server search has answered.
    private func search(_ model: SessionSearchModel, _ query: String, _ sleeper: ManualSleeper) async {
        model.updateQuery(query)
        await fireDebounce(sleeper)
        _ = await waitUntil { !model.busy }
    }

    /// Fires the debounce once the search task is waiting on it.
    private func fireDebounce(_ sleeper: ManualSleeper) async {
        _ = await waitUntil { sleeper.waitingCount > 0 }
        sleeper.fireAll()
    }

    private func settle() async {
        for _ in 0 ..< 20 { await Task.yield() }
    }

    private func waitUntil(timeoutMs: Int = 2_000, _ condition: () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .milliseconds(timeoutMs)
        while ContinuousClock.now < deadline {
            if condition() { return true }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return condition()
    }
}

/// Answers each search from `results` by directory, filtered like a server; can hold replies.
private final class ScriptedSearchRepository: SessionRepository, @unchecked Sendable {
    struct Call: Hashable {
        let query: String
        let directory: String?
    }

    var calls: [Call] = []
    var results: [String: [Session]] = [:]
    /// Archived sessions by directory, for `listArchivedSessions`.
    var archived: [String: [Session]] = [:]
    var archivedCalls: [String?] = []
    var archivedFails = false
    var failingDirectories: Set<String> = []
    var holdsReplies = false
    private var held: [CheckedContinuation<Void, Never>?] = []

    var heldCount: Int { held.count }

    func release(at index: Int) {
        held[index]?.resume()
        held[index] = nil
    }

    func releaseAll() {
        for index in held.indices { release(at: index) }
    }

    func searchSessions(query: String, directory: String?) async throws -> [Session] {
        calls.append(Call(query: query, directory: directory))
        let reply = results[directory ?? ""] ?? []
        let fails = failingDirectories.contains(directory ?? "")
        if holdsReplies {
            await withCheckedContinuation { continuation in held.append(continuation) }
        }
        if fails { throw SessionError.transport("offline") }
        return SessionTitleSearch.searchResults(reply, query: query)
    }

    func listArchivedSessions(directory: String?) async throws -> [Session] {
        archivedCalls.append(directory)
        if archivedFails { throw SessionError.transport("offline") }
        return archived[directory ?? ""] ?? []
    }

    func listSessions(directory: String?) async throws -> [Session] { [] }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?, directory: String?) async throws -> Session { fatalError() }
    func archiveSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { [] }
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] { [] }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { false }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String, directory: String?) async throws -> Bool { false }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] { [] }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { false }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { false }
    func unrevertSession(id: String) async throws -> Bool { false }
    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory: String?
    ) async throws -> Bool { false }
}

/// Debounce sleeps that wait until the test fires them.
private final class ManualSleeper: @unchecked Sendable {
    private(set) var durations: [Duration] = []
    private var waiters: [CheckedContinuation<Void, Never>] = []

    var waitingCount: Int { waiters.count }

    func sleep(_ duration: Duration) async throws {
        durations.append(duration)
        await withCheckedContinuation { continuation in waiters.append(continuation) }
    }

    func fireAll() {
        let pending = waiters
        waiters = []
        pending.forEach { $0.resume() }
    }
}
