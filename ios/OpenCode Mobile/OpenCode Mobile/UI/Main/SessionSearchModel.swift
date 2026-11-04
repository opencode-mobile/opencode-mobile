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
    /// v1: non-added worktree directories whose sessions an added path's section folds in, by
    /// added path. Search asks them too, so a server match there is found like Home shows it.
    var extraDirectories: [String: [String]] = [:]
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

/// The "Include archived" chip, on servers that list archived sessions.
struct SessionSearchArchive: Equatable {
    /// The chip is on: archived matches follow the active ones.
    var included: Bool
    /// Archived sessions in the current scope. Nil while they load, or when listing them failed.
    var count: Int?
}

enum SessionSearchContent: Equatable {
    /// Nothing typed yet.
    case hint(scopeTitle: String)
    /// The server is still answering and nothing matched yet.
    case searching
    /// Active matches, newest first, then archived matches (only while "Include archived" is on),
    /// most recently archived first. A session in both shows once, as active.
    case results([SessionSearchResult], archived: [SessionSearchResult])
    /// `offerAllProjects` when a project is searched and a session in another project matches;
    /// `offerArchived` when "Include archived" is off and an archived session in scope matches.
    case noMatch(query: String, scopeTitle: String, offerAllProjects: Bool, offerArchived: Bool)
}

/// Screen Model for searching session titles from Home (#929). Home's own rows answer on every
/// keystroke; the server is asked 250 ms after the last keystroke (at once on a scope change),
/// and what it adds joins the list. Stale replies are dropped; a failed request keeps the local
/// results. On servers that list archived sessions, every Home project's archived sessions are
/// listed once on open and matched locally while "Include archived" is on.
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
    /// The server lists archived sessions, as of the last open.
    private var archiveSupported = false
    private var archiveIncluded = false
    /// Each Home project's archived sessions, by project key. Nil while loading or after a failure.
    private var archivedSessions: [String: [Session]]?
    /// `archivedSessions` through Home's row builder, most recently archived first.
    private var archivedRows: [SessionListSession] = []

    @ObservationIgnored private let repository: any SessionRepository
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private let archivedSessionsSupported: @MainActor () -> Bool
    @ObservationIgnored private let adapter = SessionDomainAdapter()
    @ObservationIgnored private var searchTask: Task<Void, Never>?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var archiveTask: Task<Void, Never>?
    @ObservationIgnored private var archiveGeneration = 0

    /// `archivedSessionsSupported` is the server's `supportsArchivedSessions`; without it search
    /// shows no "Include archived" and never lists archived sessions.
    init(
        repository: any SessionRepository,
        archivedSessionsSupported: @escaping @MainActor () -> Bool = { false },
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.repository = repository
        self.archivedSessionsSupported = archivedSessionsSupported
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

    /// Nil when the server cannot list archived sessions: no chip, no archived results.
    var archive: SessionSearchArchive? {
        guard archiveSupported else { return nil }
        return SessionSearchArchive(
            included: archiveIncluded,
            count: archivedSessions == nil ? nil : archivedRows.filter { inScope($0, scope) }.count
        )
    }

    var content: SessionSearchContent {
        let normalized = SessionTitleSearch.normalizedQuery(query)
        guard !normalized.isEmpty else { return .hint(scopeTitle: scopeTitle) }
        let active = matchingRows(query: normalized)
        let archived = matchingArchivedRows(query: normalized, excluding: Set(active.map(\.id)))
        let rows = active.filter { inScope($0, scope) }
        let archivedInScope = archived.filter { inScope($0, scope) }
        let shownArchived = archiveIncluded ? archivedInScope : []
        if !rows.isEmpty || !shownArchived.isEmpty {
            return .results(
                rows.map { result($0, query: normalized) },
                archived: shownArchived.map { result($0, query: normalized) }
            )
        }
        if busy { return .searching }
        let matchesAnywhere = !active.isEmpty || (archiveIncluded && !archived.isEmpty)
        return .noMatch(
            query: normalized,
            scopeTitle: scopeTitle,
            offerAllProjects: scope != HomeScope.allID && matchesAnywhere,
            offerArchived: !archiveIncluded && !archivedInScope.isEmpty
        )
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
        loadArchivedSessions()
    }

    /// ⌘F while search is already open: back to the field.
    func focusField() {
        focusRequest += 1
    }

    func close() {
        cancelServerSearch()
        cancelArchivedLoad()
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

    /// The "Include archived" chip.
    func toggleIncludeArchived() {
        guard archiveSupported else { return }
        archiveIncluded.toggle()
    }

    /// The no-match "Include archived" button: turns the chip on.
    func includeArchived() {
        guard archiveSupported else { return }
        archiveIncluded = true
    }

    /// Waits for the archived listing in flight (tests).
    func settleArchived() async {
        await archiveTask?.value
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
        let previousPaths = pathsInScope(scope)
        home = snapshot
        // Server rows of projects that left Home go with them.
        let projectKeys = Set(snapshot.projects.map(\.key))
        serverRows = serverRows.filter { projectKeys.contains($0.value.worktree) }
        var searchAgain = pathsInScope(scope) != previousPaths
        // A removed project falls back to All projects.
        if scope != HomeScope.allID, !projectKeys.contains(scope) {
            scope = HomeScope.allID
            searchAgain = true
        }
        rebuildArchivedRows()
        // Another scope, or other lists in it: the running search (if any) asked the wrong lists.
        if searchAgain, isPresented, !SessionTitleSearch.normalizedQuery(query).isEmpty {
            startServerSearch(debounced: false)
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

    /// Archived rows matching `query`, leaving out sessions also listed as active.
    private func matchingArchivedRows(query: String, excluding activeIDs: Set<String>) -> [SessionListSession] {
        guard archiveSupported else { return [] }
        return archivedRows.filter { !activeIDs.contains($0.id) && SessionTitleSearch.matches($0.title, query: query) }
    }

    private func result(_ row: SessionListSession, query: String) -> SessionSearchResult {
        SessionSearchResult(row: row, matchRange: SessionTitleSearch.matchRange(in: row.title, query: query))
    }

    private func inScope(_ row: SessionListSession, _ scope: String) -> Bool {
        scope == HomeScope.allID || row.worktree == scope
    }

    /// Every list Home loads for `scope`, one request each: the added paths, then (v1) the
    /// worktrees each added path folds in.
    private func pathsInScope(_ scope: String) -> [String] {
        let added = scope == HomeScope.allID
            ? home.sections.map(\.worktree)
            : home.projects.first { $0.key == scope }?.memberPaths ?? []
        var paths = added
        for path in added {
            for directory in home.extraDirectories[path] ?? [] where !paths.contains(directory) {
                paths.append(directory)
            }
        }
        return paths
    }

    /// The added path whose Home section shows `directory`'s sessions: itself, or the added path
    /// that folds that worktree in.
    private func sectionPath(for directory: String) -> String? {
        if home.sections.contains(where: { $0.worktree == directory }) { return directory }
        return home.extraDirectories.first { $0.value.contains(directory) }?.key
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

    /// Home's row builder applied to sessions the server returned for one list, filed in the
    /// section that shows that list on Home. A list no section shows any more (Home changed while
    /// the request ran) adds nothing; Home's change has already started a new search.
    private func serverRowsFor(path: String, sessions: [Session]) -> [SessionListSession] {
        guard !sessions.isEmpty,
              let owner = sectionPath(for: path),
              let section = home.sections.first(where: { $0.worktree == owner })
        else { return [] }
        let items = sessions.map { session in
            let item = adapter.listItem(from: session, worktree: path)
            if item.directory?.isEmpty == false { return item }
            return SessionListItem(
                id: item.id,
                title: item.title,
                worktree: item.worktree,
                updatedAtEpochMs: item.updatedAtEpochMs,
                parentID: item.parentID,
                directory: path
            )
        }
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

    // MARK: Archived sessions

    /// Lists every Home project's archived sessions once per open, when the server can. "Include
    /// archived" starts off each time.
    private func loadArchivedSessions() {
        cancelArchivedLoad()
        archiveIncluded = false
        archivedSessions = nil
        archivedRows = []
        archiveSupported = archivedSessionsSupported()
        guard archiveSupported, !home.projects.isEmpty else {
            if archiveSupported { archivedSessions = [:] }
            return
        }
        let request = archiveGeneration
        let repository = repository
        let projects = home.projects
        archiveTask = Task { [weak self] in
            let sessions: [String: [Session]]?
            do {
                sessions = try await ArchivedSessionRow.loadByProject(repository: repository, projects: projects)
            } catch {
                if !(error is CancellationError) {
                    DiagnosticLog.warning(.session, "session search archived load failed", error: error)
                }
                sessions = nil
            }
            guard !Task.isCancelled, let self, self.archiveGeneration == request else { return }
            self.archiveTask = nil
            // A failure leaves no count and no archived rows.
            self.archivedSessions = sessions
            self.rebuildArchivedRows()
        }
    }

    private func cancelArchivedLoad() {
        archiveGeneration += 1
        archiveTask?.cancel()
        archiveTask = nil
    }

    private func rebuildArchivedRows() {
        guard let archivedSessions else {
            if !archivedRows.isEmpty { archivedRows = [] }
            return
        }
        archivedRows = ArchivedSessionRow.rows(
            sessionsByProject: archivedSessions,
            projects: home.projects,
            worktreesEnabled: home.worktreesEnabled,
            adapter: adapter
        ).map(\.row)
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
