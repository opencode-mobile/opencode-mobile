import Foundation
import Observation

/// One archived session as the list shows it: Home's row, captioned with when it was archived.
struct ArchivedSessionRow: Identifiable, Equatable {
    let row: SessionListSession
    let archivedAtEpochMs: Int64

    var id: String { row.id }
    var title: String { row.title }
    /// Directory the session runs in, for requests and for opening it.
    var directory: String { row.directory }
}

extension ArchivedSessionRow {
    /// Every Home project's archived sessions over its added paths, by project key. The Archived
    /// sessions screen and search's "Include archived" both list them this way. Throws when a
    /// project cannot be listed.
    static func loadByProject(repository: any SessionRepository, projects: [HomeProject]) async throws -> [String: [Session]] {
        var result: [String: [Session]] = [:]
        try await withThrowingTaskGroup(of: (String, [Session]).self) { group in
            for project in projects {
                group.addTask {
                    (project.key, try await ArchivedSessionList.load(repository: repository, directories: project.memberPaths))
                }
            }
            for try await (key, sessions) in group {
                result[key] = sessions
            }
        }
        return result
    }

    /// Home's row builder over each project's archived sessions: no live status, and the caption
    /// and order follow when the session was archived, most recent first.
    static func rows(
        sessionsByProject: [String: [Session]],
        projects: [HomeProject],
        worktreesEnabled: Bool,
        adapter: SessionDomainAdapter
    ) -> [ArchivedSessionRow] {
        let sections = projects.map { project -> ProjectSectionItem in
            ProjectSectionItem(
                id: project.primaryPath,
                worktree: project.primaryPath,
                displayName: project.displayName,
                pathSubtitle: project.primaryPath,
                letter: letterCharacter(for: project.displayName),
                letterAccent: letterAccent(for: project.primaryPath),
                allSessions: (sessionsByProject[project.key] ?? []).map { session in
                    let item = adapter.listItem(from: session, worktree: project.primaryPath)
                    return SessionListItem(
                        id: item.id,
                        title: item.title,
                        worktree: item.worktree,
                        updatedAtEpochMs: session.archivedAtEpochMs,
                        parentID: item.parentID,
                        directory: item.directory
                    )
                },
                loadError: nil
            )
        }
        return sections.crossProjectSessionListSessions(projects: projects, worktreesEnabled: worktreesEnabled)
            .map { ArchivedSessionRow(row: $0, archivedAtEpochMs: $0.updatedAtEpochMs ?? 0) }
    }
}

struct ArchivedSessionSection: Identifiable, Equatable {
    let period: ArchivedSessionPeriod
    let rows: [ArchivedSessionRow]

    var id: ArchivedSessionPeriod { period }
}

enum ArchivedSessionsContent: Equatable {
    case loading
    case failed(String)
    /// `canShowAllProjects`: the scope is narrower than All and other projects have archived sessions.
    case loaded(sections: [ArchivedSessionSection], canShowAllProjects: Bool)
}

/// A snackbar; `undoSessionID` puts an Undo on it that archives that session again.
struct ArchivedSessionsSnackbar: Equatable {
    let message: OpenCodeSnackbarMessage
    var undoSessionID: String? = nil
}

/// Screen Model of the Archived sessions screen: the archived sessions of one Home scope, grouped
/// by when they were archived. Unarchives (with Undo), opens and deletes them. Home's projects
/// name each row; a live archive, unarchive or delete anywhere refreshes the list.
@MainActor
@Observable
final class ArchivedSessionsViewModel {
    /// Live events after which the archived list may have changed.
    static let refreshEventTypes: Set<String> = ["session.archived", "session.unarchived", "session.deleted"]
    static let refreshDebounceMs: UInt64 = 300

    private(set) var scopeTitle = String(localized: "home.all_projects", defaultValue: "All projects")
    private(set) var content: ArchivedSessionsContent = .loading
    /// The row whose actions sheet or delete confirmation is up.
    private(set) var actionTarget: ArchivedSessionRow?
    private(set) var sheetVisible = false
    private(set) var deleteConfirmVisible = false
    /// The session the delete confirmation names; kept apart so closing the sheet cannot clear it.
    private(set) var deleteTarget: ArchivedSessionRow?
    private(set) var snackbar: ArchivedSessionsSnackbar?

    private let sessionRepository: any SessionRepository
    private let adapter: SessionDomainAdapter
    private let eventStream: (any ForegroundEventStream)?
    private let now: () -> Int64

    private var scopeID = HomeScope.all.id
    private var projects: [HomeProject] = []
    private var worktreesEnabled = true
    private var loaded = false
    /// Every Home project's archived sessions, by project key, as last loaded.
    private var archivedByProject: [String: [Session]] = [:]
    /// Sessions with an unarchive or delete in flight; a refresh must not bring their rows back.
    private var hiddenIDs = Set<String>()
    /// Sessions this screen unarchived or deleted, with the archive time they had. A list that
    /// still carries that archive time lags behind the server; a newer one is a fresh archive.
    private var settled: [String: Int64?] = [:]
    /// Sessions shown again by Undo before the server has archived them, with their project.
    private var restoredSessions: [String: (projectKey: String?, session: Session)] = [:]
    /// Sessions the last unarchive snackbars can archive again, with their project.
    private var undoable: [String: (projectKey: String?, session: Session)] = [:]
    private var loadGeneration = 0
    private var refreshTask: Task<Void, Never>?

    init(
        sessionRepository: any SessionRepository,
        adapter: SessionDomainAdapter = SessionDomainAdapter(),
        eventStream: (any ForegroundEventStream)?,
        now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.sessionRepository = sessionRepository
        self.adapter = adapter
        self.eventStream = eventStream
        self.now = now
    }

    /// Shows the archived sessions of Home scope `scopeID` over `projects`, then refreshes on live
    /// events until the calling task is cancelled.
    func appear(scopeID: String, projects: [HomeProject], worktreesEnabled: Bool) async {
        if !loaded {
            self.scopeID = scopeID
        }
        self.projects = projects
        self.worktreesEnabled = worktreesEnabled
        publish()
        // Subscribe before loading, so a change during the load still refreshes the list.
        let events = eventStream?.events()
        await load()
        guard let events else { return }
        for await event in events {
            if Task.isCancelled { break }
            if Self.refreshEventTypes.contains(event.type) { scheduleRefresh() }
        }
    }

    func retry() async {
        content = .loading
        await load()
    }

    /// Empty state's "Show all projects".
    func showAllProjects() {
        scopeID = HomeScope.all.id
        publish()
    }

    func openActions(_ row: ArchivedSessionRow) {
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.archivedSessionActions,
            presentation: ProductAnalytics.Presentation.sheet,
            trigger: ProductAnalytics.Trigger.present
        )
        actionTarget = row
        deleteConfirmVisible = false
        sheetVisible = true
    }

    func dismissActions() {
        sheetVisible = false
        actionTarget = nil
    }

    /// The sheet's Open: the caller navigates to the returned row's session.
    func chooseOpen() -> ArchivedSessionRow? {
        let target = actionTarget
        sheetVisible = false
        actionTarget = nil
        return target
    }

    func chooseUnarchive() async {
        guard let target = actionTarget else { return }
        sheetVisible = false
        actionTarget = nil
        await unarchive(sessionID: target.id)
    }

    func chooseDelete() {
        guard let target = actionTarget else { return }
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.archivedDeleteSessionConfirm,
            presentation: ProductAnalytics.Presentation.dialog,
            trigger: ProductAnalytics.Trigger.present
        )
        sheetVisible = false
        actionTarget = nil
        deleteTarget = target
        deleteConfirmVisible = true
    }

    func dismissDeleteConfirm() {
        deleteConfirmVisible = false
        deleteTarget = nil
    }

    func confirmDelete() async {
        guard let target = deleteTarget else { return }
        deleteConfirmVisible = false
        deleteTarget = nil
        guard let session = session(for: target.id) else { return }
        hide(session.id)
        DiagnosticLog.info(.session, "archived delete start session=\(session.id)")
        let deleted: Bool
        do {
            deleted = try await sessionRepository.deleteSession(id: session.id, directory: target.directory)
        } catch {
            DiagnosticLog.warning(.session, "archived delete failed session=\(session.id)", error: error)
            deleted = false
        }
        if deleted {
            settle(session)
        } else {
            unhide(session.id)
            snackbar = ArchivedSessionsSnackbar(message: OpenCodeSnackbarMessage(text: Self.deleteError, variant: .error))
        }
    }

    /// The row's Unarchive: the row leaves at once and a snackbar offers Undo.
    func unarchive(sessionID: String) async {
        guard let session = session(for: sessionID), !hiddenIDs.contains(sessionID) else { return }
        let projectKey = projectKey(of: sessionID)
        let directory = directory(of: session)
        hide(sessionID)
        DiagnosticLog.info(.session, "archived unarchive start session=\(sessionID)")
        do {
            _ = try await sessionRepository.unarchiveSession(id: sessionID, directory: directory)
        } catch {
            DiagnosticLog.warning(.session, "archived unarchive failed session=\(sessionID)", error: error)
            unhide(sessionID)
            snackbar = ArchivedSessionsSnackbar(message: OpenCodeSnackbarMessage(text: Self.unarchiveError, variant: .error))
            return
        }
        settle(session)
        undoable[sessionID] = (projectKey, session)
        snackbar = ArchivedSessionsSnackbar(
            message: OpenCodeSnackbarMessage(
                text: String(localized: LocalizedStringResource(
                    "archived_sessions.unarchived",
                    defaultValue: "Unarchived \u{201C}\(session.title)\u{201D}"
                )),
                actionLabel: String(localized: "archived_sessions.undo", defaultValue: "Undo")
            ),
            undoSessionID: sessionID
        )
    }

    /// The snackbar's Undo: archives the session again and puts its row back.
    func undoUnarchive(sessionID: String) async {
        guard let (projectKey, original) = undoable.removeValue(forKey: sessionID) else { return }
        var session = original
        session.archivedAtEpochMs = now()
        settled[sessionID] = nil
        restoredSessions[sessionID] = (projectKey, session)
        publish()
        DiagnosticLog.info(.session, "archived undo unarchive session=\(sessionID)")
        do {
            _ = try await sessionRepository.archiveSession(id: sessionID, directory: directory(of: session))
            scheduleRefresh()
        } catch {
            DiagnosticLog.warning(.session, "archived undo unarchive failed session=\(sessionID)", error: error)
            restoredSessions[sessionID] = nil
            publish()
            snackbar = ArchivedSessionsSnackbar(message: OpenCodeSnackbarMessage(text: Self.archiveError, variant: .error))
        }
    }

    func consumeSnackbar() {
        snackbar = nil
    }

    private func load() async {
        loadGeneration += 1
        let generation = loadGeneration
        let repository = sessionRepository
        let homeProjects = projects
        // A project that fails keeps its last list while another project answers.
        var answered: [String: [Session]] = [:]
        await withTaskGroup(of: (String, [Session]?).self) { group in
            for project in homeProjects {
                group.addTask {
                    do {
                        return (project.key, try await ArchivedSessionList.load(repository: repository, directories: project.memberPaths))
                    } catch {
                        DiagnosticLog.warning(.session, "archived load failed project=\(project.key)", error: error)
                        return (project.key, nil)
                    }
                }
            }
            for await (key, sessions) in group {
                if let sessions { answered[key] = sessions }
            }
        }
        guard generation == loadGeneration, !Task.isCancelled else { return }
        if !homeProjects.isEmpty, answered.isEmpty {
            if !loaded { content = .failed(Self.loadError) }
            return
        }
        var result: [String: [Session]] = [:]
        for project in homeProjects {
            result[project.key] = answered[project.key] ?? archivedByProject[project.key] ?? []
        }
        archivedByProject = result
        loaded = true
        let listedIDs = Set(result.values.flatMap { $0.map(\.id) })
        // A session the server no longer lists as archived is settled for good.
        settled = settled.filter { listedIDs.contains($0.key) }
        // The server now has every row Undo put back.
        restoredSessions = restoredSessions.filter { !listedIDs.contains($0.key) }
        publish()
    }

    private func scheduleRefresh() {
        refreshTask?.cancel()
        refreshTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: Self.refreshDebounceMs * 1_000_000)
            guard !Task.isCancelled else { return }
            await self?.load()
        }
    }

    private func hide(_ sessionID: String) {
        hiddenIDs.insert(sessionID)
        publish()
    }

    private func unhide(_ sessionID: String) {
        hiddenIDs.remove(sessionID)
        publish()
    }

    /// The server unarchived or deleted `session`; a lagging list must not bring its row back.
    private func settle(_ session: Session) {
        settled[session.id] = .some(session.archivedAtEpochMs)
        hiddenIDs.remove(session.id)
        restoredSessions[session.id] = nil
        archivedByProject = archivedByProject.mapValues { $0.filter { $0.id != session.id } }
        publish()
    }

    private func isSettled(_ session: Session) -> Bool {
        guard let archivedAt = settled[session.id] else { return false }
        return archivedAt == session.archivedAtEpochMs
    }

    private func session(for sessionID: String) -> Session? {
        restoredSessions[sessionID]?.session ?? archivedByProject.values.lazy.compactMap { $0.first { $0.id == sessionID } }.first
    }

    private func projectKey(of sessionID: String) -> String? {
        restoredSessions[sessionID]?.projectKey ?? archivedByProject.first { $0.value.contains { $0.id == sessionID } }?.key
    }

    private func directory(of session: Session) -> String? {
        if let directory = session.directory, !directory.isEmpty { return directory }
        guard let key = projectKey(of: session.id) else { return nil }
        return projects.first { $0.key == key }?.primaryPath
    }

    private func publish() {
        let resolved = HomeScope.resolve(id: scopeID, projects: projects, worktreesEnabled: worktreesEnabled) ?? .all
        scopeTitle = archivedScopeTitle(resolved, projects: projects)
        guard loaded else { return }
        let nowMs = now()
        let restoredByProject = Dictionary(grouping: restoredSessions.values) { $0.projectKey ?? "" }
            .mapValues { $0.map(\.session) }
        var sessionsByProject: [String: [Session]] = [:]
        for project in projects {
            let listed = (archivedByProject[project.key] ?? []).filter { !isSettled($0) }
            let restored = (restoredByProject[project.key] ?? []).filter { restored in !listed.contains { $0.id == restored.id } }
            sessionsByProject[project.key] = ArchivedSessionList.sorted(listed + restored).filter { !hiddenIDs.contains($0.id) }
        }
        let allRows = ArchivedSessionRow.rows(
            sessionsByProject: sessionsByProject,
            projects: projects,
            worktreesEnabled: worktreesEnabled,
            adapter: adapter
        )
        let visibleIDs = Set(allRows.map(\.row).visibleForFilter(resolved.id).map(\.id))
        let visible = allRows.filter { visibleIDs.contains($0.id) }
        content = .loaded(
            sections: ArchivedSessionPeriod.allCases.compactMap { period in
                let rows = visible.filter { ArchivedSessionList.period(archivedAtEpochMs: $0.archivedAtEpochMs, nowEpochMs: nowMs) == period }
                return rows.isEmpty ? nil : ArchivedSessionSection(period: period, rows: rows)
            },
            canShowAllProjects: resolved != .all && !allRows.isEmpty
        )
    }

    private static var loadError: String {
        String(localized: "archived_sessions.load_failed", defaultValue: "Couldn't load archived sessions.")
    }

    private static var unarchiveError: String {
        String(localized: "archived_sessions.unarchive_failed", defaultValue: "Couldn't unarchive session.")
    }

    private static var archiveError: String {
        String(localized: "home.session_error.archive_failed", defaultValue: "Couldn't archive session.")
    }

    private static var deleteError: String {
        String(localized: "home.session_error.delete_failed", defaultValue: "Couldn't delete session.")
    }
}
