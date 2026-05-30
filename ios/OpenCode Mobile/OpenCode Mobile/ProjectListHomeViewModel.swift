import Foundation
import Observation

struct ProjectSectionItem: Identifiable, Equatable {
    let id: String
    let worktree: String
    let displayName: String
    let pathSubtitle: String
    let letter: Character
    let letterAccent: LetterAccent
    let previewSessions: [SessionListItem]
    let allSessions: [SessionListItem]
    let totalSessionCount: Int
    let loadError: String?
}

struct AddProjectCandidateItem: Identifiable, Equatable {
    let id: String
    let worktree: String
    let displayName: String
    let pathSubtitle: String
    let letter: Character
    let letterAccent: LetterAccent
}

struct SeeAllSessionsSheetState: Equatable {
    let worktree: String
    let projectName: String
    let sessions: [SessionListItem]
}

enum ProjectListHomeState: Equatable {
    case probing
    case probeFailed(String)
    case loading
    case empty(showAddProjectSheet: Bool, candidates: [AddProjectCandidateItem])
    case loaded(
        sections: [ProjectSectionItem],
        showAddProjectSheet: Bool,
        candidates: [AddProjectCandidateItem],
        seeAllSessions: SeeAllSessionsSheetState?
    )
}

@MainActor
@Observable
final class ProjectListHomeViewModel {
    private(set) var state: ProjectListHomeState = .probing
    private let projectRepository: any ProjectRepository
    private let sessionRepository: any SessionRepository
    private let connectionProfileStore: any ConnectionProfileStore
    private let activeConnectionManager: ActiveConnectionManager
    private let connectionSessionCoordinator: ConnectionSessionCoordinator
    private let sessionLiveStore: any SessionLiveStore
    private let sessionStatusLiveStore: any SessionStatusLiveStore
    private let foregroundEventStream: any ForegroundEventStream
    private let eventAdapter = EventDomainAdapter()
    private let sessionAdapter = SessionDomainAdapter()
    private var connectionStateObservationTask: Task<Void, Never>?
    private(set) var liveUpdatesConnectionState: EventStreamConnectionState = .idle
    private var serverBaseURL: String {
        activeConnectionManager.connection?.baseURL.absoluteString ?? ""
    }
    private var liveObservationTasks: [Task<Void, Never>] = []
    private var liveSessionsByWorktree: [String: [Session]] = [:]
    private var liveStatusesByWorktree: [String: [String: SessionStatus]] = [:]

    init(
        projectRepository: any ProjectRepository,
        sessionRepository: any SessionRepository,
        connectionProfileStore: any ConnectionProfileStore,
        activeConnectionManager: ActiveConnectionManager,
        sessionLiveStore: any SessionLiveStore,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        foregroundEventStream: any ForegroundEventStream,
        connectionSessionCoordinator: ConnectionSessionCoordinator
    ) {
        self.projectRepository = projectRepository
        self.sessionRepository = sessionRepository
        self.connectionProfileStore = connectionProfileStore
        self.activeConnectionManager = activeConnectionManager
        self.sessionLiveStore = sessionLiveStore
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.foregroundEventStream = foregroundEventStream
        self.connectionSessionCoordinator = connectionSessionCoordinator
        connectionSessionCoordinator.onProfileSaved { [weak self] in
            Task { @MainActor in
                await self?.refresh()
            }
        }
        startObservingConnectionState()
    }

    private func startObservingConnectionState() {
        connectionStateObservationTask?.cancel()
        liveUpdatesConnectionState = foregroundEventStream.connectionState
        connectionStateObservationTask = Task {
            for await state in foregroundEventStream.connectionStateUpdates() {
                liveUpdatesConnectionState = state
            }
        }
    }

    func refresh() async {
        await runHomeLoad()
    }

    func retryProbe() async {
        await refresh()
    }

    func showAddProjectSheet() async {
        await loadAddProjectCandidates()
    }

    func dismissAddProjectSheet() {
        switch state {
        case let .empty(_, candidates):
            state = .empty(showAddProjectSheet: false, candidates: candidates)
        case let .loaded(sections, _, candidates, seeAll):
            state = .loaded(sections: sections, showAddProjectSheet: false, candidates: candidates, seeAllSessions: seeAll)
        default:
            break
        }
    }

    func addProject(worktree: String) async {
        await connectionProfileStore.addWorktree(serverBaseURL: serverBaseURL, worktree: worktree)
        dismissAddProjectSheet()
        await runHomeLoad(skipProbe: true)
    }

    func showSeeAllSessions(worktree: String) {
        guard case let .loaded(sections, showAdd, candidates, _) = state else { return }
        guard let section = sections.first(where: { $0.worktree == worktree }) else { return }
        state = .loaded(
            sections: sections,
            showAddProjectSheet: showAdd,
            candidates: candidates,
            seeAllSessions: SeeAllSessionsSheetState(
                worktree: worktree,
                projectName: section.displayName,
                sessions: section.allSessions
            )
        )
    }

    func dismissSeeAllSessions() {
        guard case let .loaded(sections, showAdd, candidates, _) = state else { return }
        state = .loaded(sections: sections, showAddProjectSheet: showAdd, candidates: candidates, seeAllSessions: nil)
    }

    func createSession(in worktree: String) async {
        do {
            let session = try await sessionRepository.createSession(parentID: nil, title: nil, directory: worktree)
            pendingSessionOpen =
                SessionListItem(id: session.id, title: session.title, worktree: worktree)
        } catch {
            return
        }
    }

    private(set) var pendingSessionOpen: SessionListItem?

    func consumePendingSessionOpen() {
        pendingSessionOpen = nil
    }

    private func runHomeLoad(skipProbe: Bool = false) async {
        if !skipProbe {
            state = .probing
            do {
                try await projectRepository.probeServer()
            } catch let error as ServerProbeError {
                stopLiveObservations()
                state = .probeFailed(error.localizedDescription)
                return
            } catch {
                stopLiveObservations()
                state = .probeFailed(error.localizedDescription)
                return
            }
        }

        state = .loading
        do {
            var worktrees = await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL)
            if worktrees.isEmpty {
                let boot = try await projectRepository.getCurrentProject(directory: nil)
                await connectionProfileStore.addWorktree(serverBaseURL: serverBaseURL, worktree: boot.worktree)
                worktrees = [boot.worktree]
            }

            if worktrees.isEmpty {
                stopLiveObservations()
                state = .empty(showAddProjectSheet: false, candidates: [])
                return
            }

            let sections = await loadSections(worktrees: worktrees)
            state = sections.isEmpty
                ? .empty(showAddProjectSheet: false, candidates: [])
                : .loaded(sections: sections, showAddProjectSheet: false, candidates: [], seeAllSessions: nil)
            if case .loaded = state {
                startLiveObservations(worktrees: worktrees)
            } else {
                stopLiveObservations()
            }
        } catch {
            stopLiveObservations()
            state = .probeFailed(error.localizedDescription)
        }
    }

    private func loadSections(worktrees: [String]) async -> [ProjectSectionItem] {
        await withTaskGroup(of: (String, ProjectSectionItem).self) { group in
            for worktree in worktrees {
                group.addTask {
                    (worktree, await self.loadSection(worktree: worktree))
                }
            }
            var byWorktree: [String: ProjectSectionItem] = [:]
            for await (worktree, section) in group {
                byWorktree[worktree] = section
            }
            return worktrees.compactMap { byWorktree[$0] }
        }
    }

    private func loadSection(worktree: String) async -> ProjectSectionItem {
        do {
            let project = try await projectRepository.getCurrentProject(directory: worktree)
            let sessions =
                try await sessionRepository.listSessions(directory: worktree)
                    .sorted { ($0.updatedAtEpochMs ?? 0) > ($1.updatedAtEpochMs ?? 0) }
            let statusBySessionID =
                Dictionary(
                    uniqueKeysWithValues: (try await sessionRepository.getAllSessionStatuses(directory: worktree))
                        .map { ($0.sessionID, $0) }
                )
            let items =
                sessions.map { session in
                    sessionAdapter.listItem(
                        from: session,
                        worktree: worktree,
                        statusLabel: statusBySessionID[session.id].flatMap(eventAdapter.statusLabel)
                    )
                }
            return ProjectSectionItem(
                id: worktree,
                worktree: worktree,
                displayName: project.displayName,
                pathSubtitle: project.worktree,
                letter: letterCharacter(for: project.displayName),
                letterAccent: letterAccent(for: worktree),
                previewSessions: Array(items.prefix(Self.previewSessionLimit)),
                allSessions: items,
                totalSessionCount: items.count,
                loadError: nil
            )
        } catch {
            let name = ProjectDomainAdapter.displayName(from: worktree)
            return ProjectSectionItem(
                id: worktree,
                worktree: worktree,
                displayName: name,
                pathSubtitle: worktree,
                letter: letterCharacter(for: name),
                letterAccent: letterAccent(for: worktree),
                previewSessions: [],
                allSessions: [],
                totalSessionCount: 0,
                loadError: error.localizedDescription
            )
        }
    }

    private func loadAddProjectCandidates() async {
        do {
            let added = Set(await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL))
            let candidates =
                try await projectRepository.listProjects()
                    .filter { !added.contains($0.worktree) }
                    .map { project in
                        AddProjectCandidateItem(
                            id: project.worktree,
                            worktree: project.worktree,
                            displayName: project.displayName,
                            pathSubtitle: project.worktree,
                            letter: letterCharacter(for: project.displayName),
                            letterAccent: letterAccent(for: project.worktree)
                        )
                    }
            switch state {
            case .empty:
                state = .empty(showAddProjectSheet: true, candidates: candidates)
            case let .loaded(sections, _, _, seeAll):
                state = .loaded(sections: sections, showAddProjectSheet: true, candidates: candidates, seeAllSessions: seeAll)
            default:
                break
            }
        } catch {
            return
        }
    }

    private static let previewSessionLimit = 3

    private func startLiveObservations(worktrees: [String]) {
        stopLiveObservations()
        liveObservationTasks =
            worktrees.map { worktree in
                Task {
                    await observeWorktree(worktree)
                }
            }
    }

    private func stopLiveObservations() {
        liveObservationTasks.forEach { $0.cancel() }
        liveObservationTasks = []
        liveSessionsByWorktree = [:]
        liveStatusesByWorktree = [:]
    }

    private func observeWorktree(_ worktree: String) async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                for await sessions in self.sessionLiveStore.observeSessions(directory: worktree) {
                    self.liveSessionsByWorktree[worktree] = sessions
                    self.applyLiveUpdate(worktree: worktree)
                }
            }
            group.addTask { @MainActor in
                for await statuses in self.sessionStatusLiveStore.observeStatuses(directory: worktree) {
                    self.liveStatusesByWorktree[worktree] = statuses
                    self.applyLiveUpdate(worktree: worktree)
                }
            }
        }
    }

    private func applyLiveUpdate(worktree: String) {
        guard let sessions = liveSessionsByWorktree[worktree] else { return }
        let statuses = liveStatusesByWorktree[worktree] ?? [:]
        updateSectionFromLiveData(worktree: worktree, sessions: sessions, statuses: statuses)
    }

    private func updateSectionFromLiveData(
        worktree: String,
        sessions: [Session],
        statuses: [String: SessionStatus]
    ) {
        guard case let .loaded(sections, showAdd, candidates, seeAll) = state else { return }
        guard let section = sections.first(where: { $0.worktree == worktree }) else { return }
        let items =
            sessions
                .sorted { ($0.updatedAtEpochMs ?? 0) > ($1.updatedAtEpochMs ?? 0) }
                .map { session in
                    sessionAdapter.listItem(
                        from: session,
                        worktree: worktree,
                        statusLabel: statuses[session.id].flatMap(eventAdapter.statusLabel)
                    )
                }
        let updatedSection = ProjectSectionItem(
            id: section.id,
            worktree: section.worktree,
            displayName: section.displayName,
            pathSubtitle: section.pathSubtitle,
            letter: section.letter,
            letterAccent: section.letterAccent,
            previewSessions: Array(items.prefix(Self.previewSessionLimit)),
            allSessions: items,
            totalSessionCount: items.count,
            loadError: section.loadError
        )
        let updatedSections =
            sections.map { current in
                current.worktree == worktree ? updatedSection : current
            }
        let updatedSeeAll =
            if let seeAll, seeAll.worktree == worktree {
                SeeAllSessionsSheetState(
                    worktree: seeAll.worktree,
                    projectName: seeAll.projectName,
                    sessions: items
                )
            } else {
                seeAll
            }
        state = .loaded(
            sections: updatedSections,
            showAddProjectSheet: showAdd,
            candidates: candidates,
            seeAllSessions: updatedSeeAll
        )
    }

}
