import Foundation
import Observation

/// What session search needs from Home, passed in whenever Home's data changes.
struct SessionSearchHome: Equatable {
    /// Home's rows across every project, with live status, as Home renders them.
    var rows: [SessionListSession] = []
    var projects: [HomeProject] = []
    /// One section per added path. Server-only results become rows through Home's own row
    /// builder from these.
    var sections: [ProjectSectionItem] = []
    var worktreesEnabled = true
}

/// A choice in the scope menu: All projects, or one Home project.
struct SessionSearchScopeOption: Identifiable, Equatable {
    let id: String
    let title: String
    /// Nil for All projects.
    let letter: Character?
    let accent: LetterAccent?

    var isAll: Bool { id == HomeScope.allID }
}

/// One search result: Home's row, with where the query occurs in its title.
struct SessionSearchResult: Identifiable, Equatable {
    let row: SessionListSession
    let matchRange: Range<String.Index>?

    var id: String { row.id }
}

enum SessionSearchContent: Equatable {
    /// Nothing typed yet.
    case hint(scopeTitle: String)
    /// The server is still answering and nothing matched yet.
    case searching
    /// Newest first.
    case results([SessionSearchResult])
    /// `offerAllProjects` when a project is searched and a session in another project matches.
    case noMatch(query: String, scopeTitle: String, offerAllProjects: Bool)
}

/// Screen Model for searching session titles from Home (#929). Home's own rows answer on every
/// keystroke; the server is asked 250 ms after the last keystroke (at once on a scope change),
/// and what it adds joins the list. Stale replies are dropped; a failed request keeps the local
/// results.
@MainActor
@Observable
final class SessionSearchModel {
    static let debounce: Duration = .milliseconds(250)

    private(set) var isPresented = false
    private(set) var query = ""
    /// `HomeScope.allID` or a Home project key.
    private(set) var scope = HomeScope.allID
    /// A server search for the current query and scope is pending or running.
    private(set) var busy = false
    /// Bumped when the field should take keyboard focus (opening, ⌘F).
    private(set) var focusRequest = 0

    private var home = SessionSearchHome()
    /// Rows the server returned, by session id, for the latest answered search.
    private var serverRows: [String: SessionListSession] = [:]

    @ObservationIgnored private let repository: any SessionRepository
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private let adapter = SessionDomainAdapter()
    @ObservationIgnored private var searchTask: Task<Void, Never>?
    @ObservationIgnored private var generation = 0

    init(
        repository: any SessionRepository,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.repository = repository
        self.sleep = sleep
    }

    // MARK: State

    var scopeOptions: [SessionSearchScopeOption] {
        [SessionSearchScopeOption(id: HomeScope.allID, title: Self.allProjectsTitle, letter: nil, accent: nil)]
            + home.projects.map { project in
                SessionSearchScopeOption(
                    id: project.key,
                    title: project.displayName,
                    letter: letterCharacter(for: project.displayName),
                    accent: letterAccent(for: project.primaryPath)
                )
            }
    }

    var selectedScope: SessionSearchScopeOption {
        scopeOptions.first { $0.id == scope } ?? scopeOptions[0]
    }

    var scopeTitle: String { selectedScope.title }

    var content: SessionSearchContent {
        let normalized = SessionTitleSearch.normalizedQuery(query)
        guard !normalized.isEmpty else { return .hint(scopeTitle: scopeTitle) }
        let rows = matchingRows(query: normalized).filter { inScope($0, scope) }
        if !rows.isEmpty {
            return .results(rows.map { row in
                SessionSearchResult(row: row, matchRange: SessionTitleSearch.matchRange(in: row.title, query: normalized))
            })
        }
        if busy { return .searching }
        let offerAllProjects = scope != HomeScope.allID && !matchingRows(query: normalized).isEmpty
        return .noMatch(query: normalized, scopeTitle: scopeTitle, offerAllProjects: offerAllProjects)
    }

    // MARK: Actions

    /// Opens search with an empty query on Home's scope; a worktree scope searches its project.
    func open(homeScopeId: String) {
        cancelServerSearch()
        query = ""
        serverRows = [:]
        scope = Self.searchScope(homeScopeId: homeScopeId, home: home)
        isPresented = true
        focusRequest += 1
    }

    /// ⌘F while search is already open: back to the field.
    func focusField() {
        focusRequest += 1
    }

    func close() {
        cancelServerSearch()
        isPresented = false
    }

    func updateQuery(_ text: String) {
        guard text != query else { return }
        let changed = SessionTitleSearch.normalizedQuery(text) != SessionTitleSearch.normalizedQuery(query)
        query = text
        guard changed else { return }
        if SessionTitleSearch.normalizedQuery(text).isEmpty {
            cancelServerSearch()
            serverRows = [:]
        } else {
            startServerSearch(debounced: true)
        }
    }

    func clearQuery() {
        updateQuery("")
    }

    func selectScope(_ id: String) {
        let next = scopeOptions.contains { $0.id == id } ? id : HomeScope.allID
        guard next != scope else { return }
        scope = next
        if !SessionTitleSearch.normalizedQuery(query).isEmpty {
            startServerSearch(debounced: false)
        }
    }

    func searchAllProjects() {
        selectScope(HomeScope.allID)
    }

    /// Esc: a query is cleared first; an empty search closes.
    func escape() {
        if query.isEmpty {
            close()
        } else {
            clearQuery()
        }
    }

    func homeChanged(_ snapshot: SessionSearchHome) {
        guard snapshot != home else { return }
        home = snapshot
        if scope != HomeScope.allID, !snapshot.projects.contains(where: { $0.key == scope }) {
            scope = HomeScope.allID
        }
    }

    // MARK: Matching

    /// Home rows and server rows matching `query`, Home's copy winning, newest first.
    private func matchingRows(query: String) -> [SessionListSession] {
        var byID: [String: SessionListSession] = [:]
        for row in serverRows.values where SessionTitleSearch.matches(row.title, query: query) {
            byID[row.id] = row
        }
        for row in home.rows where SessionTitleSearch.matches(row.title, query: query) {
            byID[row.id] = row
        }
        return byID.values.sorted { lhs, rhs in
            let left = lhs.updatedAtEpochMs ?? 0
            let right = rhs.updatedAtEpochMs ?? 0
            if left != right { return left > right }
            return lhs.id < rhs.id
        }
    }

    private func inScope(_ row: SessionListSession, _ scope: String) -> Bool {
        scope == HomeScope.allID || row.worktree == scope
    }

    /// Added paths searched for `scope`, one request each.
    private func pathsInScope(_ scope: String) -> [String] {
        if scope == HomeScope.allID { return home.sections.map(\.worktree) }
        return home.projects.first { $0.key == scope }?.memberPaths ?? []
    }

    // MARK: Server search

    private func startServerSearch(debounced: Bool) {
        cancelServerSearch()
        let normalized = SessionTitleSearch.normalizedQuery(query)
        let paths = pathsInScope(scope)
        guard !normalized.isEmpty, !paths.isEmpty else { return }
        let request = generation
        let repository = repository
        let sleep = sleep
        busy = true
        searchTask = Task { [weak self] in
            if debounced {
                do { try await sleep(Self.debounce) } catch { return }
            }
            guard !Task.isCancelled else { return }
            let replies = await Self.search(repository: repository, query: normalized, paths: paths)
            guard let self, self.generation == request else { return }
            self.applyServerReplies(replies)
        }
    }

    private func cancelServerSearch() {
        generation += 1
        searchTask?.cancel()
        searchTask = nil
        if busy { busy = false }
    }

    private func applyServerReplies(_ replies: [(path: String, sessions: [Session]?)]) {
        busy = false
        searchTask = nil
        let answered = replies.filter { $0.sessions != nil }
        // Every request failed: keep what is shown.
        guard !answered.isEmpty else { return }
        var rows: [String: SessionListSession] = [:]
        for reply in answered {
            for row in serverRowsFor(path: reply.path, sessions: reply.sessions ?? []) {
                rows[row.id] = row
            }
        }
        serverRows = rows
    }

    /// Home's row builder applied to sessions the server returned for one added path.
    private func serverRowsFor(path: String, sessions: [Session]) -> [SessionListSession] {
        guard let section = home.sections.first(where: { $0.worktree == path }), !sessions.isEmpty else { return [] }
        let items = sessions.map { adapter.listItem(from: $0, worktree: path) }
        let serverSection = ProjectSectionItem(
            id: section.id,
            worktree: section.worktree,
            displayName: section.displayName,
            pathSubtitle: section.pathSubtitle,
            letter: section.letter,
            letterAccent: section.letterAccent,
            allSessions: items,
            loadError: nil,
            projectID: section.projectID,
            worktreeInventory: section.worktreeInventory
        )
        return [serverSection].crossProjectSessionListSessions(
            projects: home.projects,
            worktreesEnabled: home.worktreesEnabled
        )
    }

    /// One request per path, in parallel. A failed path answers nil.
    private nonisolated static func search(
        repository: any SessionRepository,
        query: String,
        paths: [String]
    ) async -> [(path: String, sessions: [Session]?)] {
        await withTaskGroup(of: (Int, String, [Session]?).self) { group in
            for (index, path) in paths.enumerated() {
                group.addTask {
                    do {
                        return (index, path, try await repository.searchSessions(query: query, directory: path))
                    } catch {
                        if !(error is CancellationError) {
                            DiagnosticLog.warning(.session, "session search failed", error: error)
                        }
                        return (index, path, nil)
                    }
                }
            }
            var replies: [(Int, String, [Session]?)] = []
            for await reply in group {
                replies.append(reply)
            }
            return replies.sorted { $0.0 < $1.0 }.map { (path: $0.1, sessions: $0.2) }
        }
    }

    // MARK: Helpers

    static var allProjectsTitle: String {
        String(localized: "home.all_projects", defaultValue: "All projects")
    }

    /// Home's scope as a search scope: All, or the project (a worktree scope maps to its project).
    static func searchScope(homeScopeId: String, home: SessionSearchHome) -> String {
        guard let resolved = HomeScope.resolve(
            id: homeScopeId,
            projects: home.projects,
            worktreesEnabled: home.worktreesEnabled
        ) else { return HomeScope.allID }
        return resolved.projectKey ?? HomeScope.allID
    }
}
