import Foundation

/// Projects come from the T3 shell snapshot: one per orchestration project,
/// keyed by its workspace root so home sections line up with thread worktrees.
struct T3ProjectRepository: ProjectRepository, Sendable {
    private let client: any T3EngineClient
    private let activeConnectionManager: ActiveConnectionManager
    private let connectionProfileStore: any ConnectionProfileStore

    init(
        client: any T3EngineClient,
        activeConnectionManager: ActiveConnectionManager,
        connectionProfileStore: any ConnectionProfileStore
    ) {
        self.client = client
        self.activeConnectionManager = activeConnectionManager
        self.connectionProfileStore = connectionProfileStore
    }

    /// Reachability check for T3 is the shell fetch itself. Seeds the profile's
    /// worktrees with the project roots, keeping any the user added by hand.
    func probeServer() async throws {
        let snapshot: T3ShellSnapshot
        do {
            snapshot = try await client.fetchShell(force: true)
        } catch {
            throw Self.mapProbeError(error)
        }
        guard let serverBaseURL = Self.serverBaseURL(activeConnectionManager) else { return }
        let roots = snapshot.projects.compactMap { project -> String? in
            guard let root = project.workspaceRoot, !root.isEmpty else { return nil }
            return root
        }
        guard !roots.isEmpty else { return }
        let existing = await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL)
        let merged = existing + roots.filter { !existing.contains($0) }
        if merged != existing {
            await connectionProfileStore.setAddedWorktrees(serverBaseURL: serverBaseURL, worktrees: merged)
        }
    }

    func listProjects() async throws -> [Project] {
        let snapshot = try await client.fetchShell(force: false)
        return snapshot.projects.map(Self.project(from:))
    }

    func getCurrentProject(directory: String?) async throws -> Project {
        let projects = try await listProjects()
        guard let directory, !directory.isEmpty else {
            return projects.first ?? Project(id: "t3", worktree: "/", displayName: "T3")
        }
        if let match = projects.first(where: { $0.worktree == directory }) {
            return match
        }
        return Project(
            id: directory,
            worktree: directory,
            displayName: Self.displayName(title: nil, path: directory)
        )
    }

    static func project(from t3Project: T3Project) -> Project {
        let root = t3Project.workspaceRoot ?? ""
        return Project(
            id: t3Project.id,
            worktree: root,
            displayName: displayName(title: t3Project.title, path: root)
        )
    }

    static func displayName(title: String?, path: String) -> String {
        let trimmedTitle = title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !trimmedTitle.isEmpty { return trimmedTitle }
        let last = path.split(separator: "/").last.map(String.init) ?? ""
        return last.isEmpty ? path : last
    }

    private static func serverBaseURL(_ manager: ActiveConnectionManager) -> String? {
        if let url = manager.connection?.baseURL.absoluteString, !url.isEmpty {
            return url
        }
        guard let profile = manager.currentProfile() else { return nil }
        return profile.normalizedBaseURL
    }

    private static func mapProbeError(_ error: Error) -> Error {
        switch error {
        case let T3ClientError.http(statusCode, message):
            return ServerProbeError.http(statusCode: statusCode, message: message)
        case let T3ClientError.transport(message):
            return ServerProbeError.transport(message)
        case let clientError as T3ClientError:
            return ServerProbeError.transport(clientError.errorDescription ?? "T3 environment is unreachable")
        default:
            return error
        }
    }
}
