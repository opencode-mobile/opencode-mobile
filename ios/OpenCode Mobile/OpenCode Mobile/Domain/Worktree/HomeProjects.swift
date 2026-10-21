import Foundation

/// What the server said about one added path: `/project/current` on v1, `/api/location` on v2,
/// plus the name and VCS from the server's project list when known.
nonisolated struct HomeProjectResolution: Equatable, Sendable {
    let id: String
    /// Main checkout of the project (v1 `worktree`, v2 `canonical`). `/` for v1 non-git folders.
    let canonical: String
    var name: String? = nil
    var isRepository = false

    /// Worktrees of one repository share an id. `global` (v1 non-git folders, and repos with no
    /// commit or remote) never merges.
    var isMergeable: Bool {
        let trimmed = id.trimmingCharacters(in: .whitespacesAndNewlines)
        return !trimmed.isEmpty && trimmed != HomeProjectGrouping.globalProjectID
    }

    /// The main checkout as a real folder; nil for an empty path or a filesystem root such as `/`.
    var mainCheckout: String? {
        let trimmed = canonical.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let path = ServerPathPolicy.canonical(trimmed)
        guard path != ServerPathPolicy.root(path) else { return nil }
        return path
    }

    init(id: String, canonical: String, name: String? = nil, isRepository: Bool = false) {
        self.id = id
        self.canonical = canonical
        self.name = name
        self.isRepository = isRepository
    }

    @MainActor init(_ project: Project) {
        self.init(
            id: project.id,
            canonical: project.canonicalWorktree,
            name: project.name,
            isRepository: project.isRepository
        )
    }
}

/// One checkout of a Home project.
nonisolated struct HomeWorktree: Hashable, Sendable {
    /// Canonical absolute path.
    let directory: String
    /// Current branch; nil while unknown, after a failed lookup, or on a detached HEAD.
    let branch: String?
    let isMain: Bool

    var folderName: String { ServerPathPolicy.name(directory) }
    /// Scope picker and Run in row title.
    var title: String { branch ?? folderName }
    /// Scope picker and Run in row subtitle.
    var subtitle: String { isMain ? HomeProjectCopy.mainCheckout : folderName }
}

/// A project on Home: every added path whose server project matched, with all its worktrees.
nonisolated struct HomeProject: Identifiable, Equatable, Sendable {
    /// Project id, or `path:` + the added path when the project cannot merge.
    let key: String
    let projectID: String?
    let displayName: String
    /// Added paths in added-list order. Never empty.
    let memberPaths: [String]
    let isRepository: Bool
    /// Main checkout first, then by folder name.
    let worktrees: [HomeWorktree]

    var id: String { key }
    /// Location for project-level requests.
    var primaryPath: String { memberPaths[0] }
    var hasManyWorktrees: Bool { worktrees.count > 1 }
    /// Where a tapped New session starts: the main checkout, else the first added path.
    var mainCheckoutDirectory: String {
        worktrees.first(where: \.isMain)?.directory ?? primaryPath
    }

    func worktree(at directory: String) -> HomeWorktree? {
        let canonical = ServerPathPolicy.canonical(directory)
        return worktrees.first { $0.directory == canonical }
    }

    func hasMember(_ path: String) -> Bool {
        let canonical = ServerPathPolicy.canonical(path)
        return memberPaths.contains { $0 == path || ServerPathPolicy.canonical($0) == canonical }
    }
}

nonisolated enum HomeProjectCopy {
    static let mainCheckout = "main checkout"
    static let runInTitle = "Run in"

    static func worktreeCount(_ count: Int) -> String {
        "\(count) worktrees"
    }

    static func allWorktrees(_ count: Int) -> String {
        "All \(count) worktrees"
    }

    static func running(_ count: Int) -> String {
        "\(count) running"
    }

    static let otherProjectsTitle = "Other projects"
    static let showFewer = "Show fewer"
    static let reorderNote = "Drag to reorder. Worktrees stay with their project."

    static func showAll(_ count: Int) -> String {
        "Show all \(count)"
    }

    static func showWorktrees(of projectName: String) -> String {
        "Show worktrees of \(projectName)"
    }

    static func hideWorktrees(of projectName: String) -> String {
        "Hide worktrees of \(projectName)"
    }

    static func joinedExisting(_ projectName: String) -> String {
        "Same repo as \(projectName). Showing it as a worktree there."
    }
}

nonisolated enum HomeProjectGrouping {
    static let globalProjectID = "global"
    static let pathKeyPrefix = "path:"

    static func pathKey(_ path: String) -> String {
        pathKeyPrefix + path
    }

    /// Groups added paths into Home projects.
    ///
    /// - Parameters:
    ///   - addedPaths: added paths in added-list order.
    ///   - resolutions: current project per added path; a missing entry means it did not load.
    ///   - inventories: worktree directories the server listed, per added path.
    ///   - branches: current branch by canonical worktree directory.
    static func group(
        addedPaths: [String],
        resolutions: [String: HomeProjectResolution],
        inventories: [String: [String]] = [:],
        branches: [String: String] = [:]
    ) -> [HomeProject] {
        var order: [String] = []
        var members: [String: [String]] = [:]
        for path in addedPaths {
            let key: String
            if let resolution = resolutions[path], resolution.isMergeable {
                key = resolution.id.trimmingCharacters(in: .whitespacesAndNewlines)
            } else {
                key = pathKey(path)
            }
            if members[key] == nil {
                order.append(key)
                members[key] = []
            }
            if members[key]?.contains(path) == false {
                members[key]?.append(path)
            }
        }
        return order.compactMap { key in
            guard let paths = members[key], let primary = paths.first else { return nil }
            return project(
                key: key,
                paths: paths,
                primary: primary,
                resolutions: paths.compactMap { resolutions[$0] },
                inventories: inventories,
                branches: branches
            )
        }
    }

    private static func project(
        key: String,
        paths: [String],
        primary: String,
        resolutions: [HomeProjectResolution],
        inventories: [String: [String]],
        branches: [String: String]
    ) -> HomeProject {
        let mergeable = resolutions.first?.isMergeable == true && !key.hasPrefix(pathKeyPrefix)
        let main = mergeable ? resolutions.compactMap(\.mainCheckout).first : nil
        let name = resolutions.compactMap { $0.name?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty }
        let displayName = name
            ?? main.map(folderName)
            ?? folderName(primary)

        var directories: [String] = []
        func insert(_ raw: String) {
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return }
            let directory = ServerPathPolicy.canonical(trimmed)
            if !directories.contains(directory) { directories.append(directory) }
        }
        if let main { insert(main) }
        paths.forEach(insert)
        if mergeable {
            for path in paths {
                inventories[path]?.forEach(insert)
            }
        }
        let worktrees = directories
            .map { directory in
                HomeWorktree(
                    directory: directory,
                    branch: usableBranch(branches[directory]),
                    // A project that cannot merge is its one folder.
                    isMain: mergeable ? directory == main : true
                )
            }
            .sorted { lhs, rhs in
                if lhs.isMain != rhs.isMain { return lhs.isMain }
                let order = lhs.folderName.localizedStandardCompare(rhs.folderName)
                if order != .orderedSame { return order == .orderedAscending }
                return lhs.directory < rhs.directory
            }
        let isRepository = resolutions.contains(where: \.isRepository)
            || worktrees.contains { $0.branch != nil }
        return HomeProject(
            key: key,
            projectID: mergeable ? key : resolutions.first?.id,
            displayName: displayName,
            memberPaths: paths,
            isRepository: isRepository,
            worktrees: worktrees
        )
    }

    static func folderName(_ path: String) -> String {
        let name = ServerPathPolicy.name(path)
        return name.isEmpty ? path : name
    }

    /// Detached HEAD and empty answers read as unknown.
    static func usableBranch(_ branch: String?) -> String? {
        guard let trimmed = branch?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty, trimmed != "HEAD"
        else { return nil }
        return trimmed
    }
}

/// Which sessions Home lists.
nonisolated enum HomeScope: Hashable, Sendable {
    case all
    case project(String)
    case worktree(projectKey: String, directory: String)

    static let allID = "all"
    private static let worktreePrefix = "worktree:"
    private static let separator = "\u{1F}"

    var id: String {
        switch self {
        case .all: Self.allID
        case let .project(key): key
        case let .worktree(projectKey, directory): Self.worktreeID(projectKey: projectKey, directory: directory)
        }
    }

    /// Project of a project or worktree scope; nil for All.
    var projectKey: String? {
        switch self {
        case .all: nil
        case let .project(key): key
        case let .worktree(key, _): key
        }
    }

    static func worktreeID(projectKey: String, directory: String) -> String {
        worktreePrefix + projectKey + separator + directory
    }

    /// Splits a worktree scope id; nil for any other id.
    static func worktreeParts(_ id: String) -> (projectKey: String, directory: String)? {
        guard id.hasPrefix(worktreePrefix) else { return nil }
        let body = id.dropFirst(worktreePrefix.count)
        guard let range = body.range(of: separator) else { return nil }
        return (String(body[..<range.lowerBound]), String(body[range.upperBound...]))
    }

    /// Maps a stored scope id onto the current projects. An added path (or its old `path:` key)
    /// that is now a member of a merged project maps to that project; a worktree scope follows its
    /// directory into whichever project lists it now. With worktrees off a worktree scope reads
    /// as its project. Nil when nothing matches.
    static func resolve(id: String, projects: [HomeProject], worktreesEnabled: Bool = true) -> HomeScope? {
        if id == allID { return .all }
        if projects.contains(where: { $0.key == id }) { return .project(id) }
        if let parts = worktreeParts(id) {
            let owner = projects.first { $0.key == parts.projectKey && $0.worktree(at: parts.directory) != nil }
                ?? projects.first { $0.worktree(at: parts.directory) != nil }
            guard let owner else { return nil }
            return worktreesEnabled && owner.hasManyWorktrees
                ? .worktree(projectKey: owner.key, directory: ServerPathPolicy.canonical(parts.directory))
                : .project(owner.key)
        }
        let path = id.hasPrefix(HomeProjectGrouping.pathKeyPrefix)
            ? String(id.dropFirst(HomeProjectGrouping.pathKeyPrefix.count))
            : id
        if let owner = projects.first(where: { $0.hasMember(path) }) {
            return .project(owner.key)
        }
        return nil
    }

    /// Worktree scope keeps sessions whose directory is that worktree.
    func includes(projectKey: String, directory: String) -> Bool {
        switch self {
        case .all:
            return true
        case let .project(key):
            return key == projectKey
        case let .worktree(key, worktree):
            return key == projectKey && ServerPathPolicy.canonical(directory) == worktree
        }
    }
}

/// Session row subtitle on Home for a git project: project name (All scope only), branch, and the
/// worktree folder when it is not the main checkout or the branch is unknown.
nonisolated struct HomeRowLocation: Hashable, Sendable {
    enum Part: Hashable, Sendable {
        case text(String)
        case branch(String)

        var text: String {
            switch self {
            case let .text(value), let .branch(value): value
            }
        }
    }

    static let separator = " \u{00B7} "

    let parts: [Part]

    var text: String { parts.map(\.text).joined(separator: Self.separator) }

    /// Nil for a non-repository project, which keeps the path subtitle, and for every project
    /// while worktrees are off.
    static func make(
        project: HomeProject,
        sessionDirectory: String?,
        includesProjectName: Bool,
        worktreesEnabled: Bool = true
    ) -> HomeRowLocation? {
        guard worktreesEnabled, project.isRepository else { return nil }
        let raw = sessionDirectory?.trimmingCharacters(in: .whitespacesAndNewlines)
        let directory = ServerPathPolicy.canonical(raw?.isEmpty == false ? raw! : project.primaryPath)
        let worktree = project.worktree(at: directory)
        let branch = worktree?.branch
        let isMain = worktree?.isMain ?? false
        var parts: [Part] = []
        if includesProjectName { parts.append(.text(project.displayName)) }
        if let branch { parts.append(.branch(branch)) }
        if !isMain || branch == nil {
            parts.append(.text(worktree?.folderName ?? ServerPathPolicy.name(directory)))
        }
        return HomeRowLocation(parts: parts)
    }
}
