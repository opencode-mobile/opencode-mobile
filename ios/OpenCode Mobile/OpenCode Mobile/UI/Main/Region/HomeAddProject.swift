import Foundation

/// Add Project sheet presentation and its current sheet.
struct HomeAddProjectUiState: Equatable {
    /// Sheet presentation is tracked here so SwiftUI `.sheet` can observe it directly.
    var isPresented = false
    var sheet = AddProjectSheetState.empty
}

/// What adding a chosen path did. The sheet shows a validation failure itself.
enum HomeAddProjectResult {
    case alreadyAdded
    /// `joined` is the Home project the new path joined (a worktree of a repository on Home).
    case added(resolution: HomeProjectResolution, joined: HomeProject?)
    case failed
}

/// The Add project sheet: server home, added paths, server project suggestions and `/Volumes`,
/// the path field with its debounced listing, folder navigation, row summaries, the browser,
/// and validating and saving the chosen path. Home reloads itself after an add.
@MainActor
final class HomeAddProject {
    private(set) var state = HomeAddProjectUiState() {
        didSet {
            if state != oldValue { onChange?() }
        }
    }

    var onChange: (() -> Void)?
    /// The sheet as it was just published; Home writes it into its content.
    var onSheetPublished: ((AddProjectSheetState) -> Void)?

    private let projectRepository: any ProjectRepository
    private let filesystemRepository: any FilesystemRepository
    private let connectionProfileStore: any ConnectionProfileStore
    private let serverBaseURL: @MainActor () -> String
    /// Home projects keyed by server project id (not path keys), so suggestions can mark them.
    var addedProjectIDs: @MainActor () -> Set<String> = { [] }
    private let listingDebounce: Duration

    private var inputs = AddProjectSheetInputs()
    /// Bumped per presentation so a late listing from a closed sheet is dropped.
    private var generation = 0
    private var listingTask: Task<Void, Never>?
    private var listingsInFlight: Set<String> = []
    /// Row listings for folder counts and git tags: waiting (first in, first out), loading,
    /// and failed this presentation (never retried, never shown as a list error).
    private var summaryQueue: [String] = []
    private var summariesInFlight: Set<String> = []
    private var summaryFailures: Set<String> = []

    init(
        projectRepository: any ProjectRepository,
        filesystemRepository: any FilesystemRepository,
        connectionProfileStore: any ConnectionProfileStore,
        serverBaseURL: @escaping @MainActor () -> String,
        listingDebounce: Duration
    ) {
        self.projectRepository = projectRepository
        self.filesystemRepository = filesystemRepository
        self.connectionProfileStore = connectionProfileStore
        self.serverBaseURL = serverBaseURL
        self.listingDebounce = listingDebounce
    }

    /// Presented and not validating a path: navigation and suggestions may act.
    var canNavigate: Bool { state.isPresented && !inputs.isValidating }

    var isBrowsing: Bool { inputs.isBrowsing }

    func open() async {
        generation += 1
        listingTask?.cancel()
        resetLoads()
        inputs = AddProjectSheetInputs()
        state.isPresented = true
        publish()
        await loadSheet(generation: generation)
    }

    /// Closes the sheet and drops this presentation's loads. Home clears the sheet from its content.
    func dismiss() {
        state.isPresented = false
        generation += 1
        listingTask?.cancel()
        listingTask = nil
        resetLoads()
        inputs = AddProjectSheetInputs()
        state.sheet = .empty
    }

    /// Path field edits. Filtering is local; a new folder is listed after a short pause.
    func updateField(_ text: String) {
        guard canNavigate, text != inputs.fieldText else { return }
        inputs.fieldText = text
        inputs.fieldError = nil
        publish()
        scheduleListing()
    }

    /// A browser row came on screen: load its own listing for the folder count and git tag.
    func rowAppeared(path: String) {
        guard state.isPresented,
              inputs.listings[path] == nil,
              !summariesInFlight.contains(path),
              !summaryFailures.contains(path),
              !summaryQueue.contains(path)
        else { return }
        summaryQueue.append(path)
        startSummaries()
    }

    /// A browser row left the screen before its listing started: drop it from the queue.
    func rowDisappeared(path: String) {
        summaryQueue.removeAll { $0 == path }
    }

    /// Replaces the suggestion sheet with the full-screen folder browser.
    func browse() {
        inputs.isBrowsing = true
        publish()
    }

    /// Back from the browser. Returns to the suggestion sheet at the server home, so
    /// "On this server" is not lost because the field was left in a subfolder.
    func closeBrowser() {
        listingTask?.cancel()
        inputs.isBrowsing = false
        if !inputs.home.isEmpty {
            inputs.fieldText = ServerPathPolicy.fieldText(inputs.home)
            inputs.fieldError = nil
        }
        publish()
    }

    func retryListing() async {
        let generation = generation
        if inputs.home.isEmpty {
            await loadSheet(generation: generation)
            return
        }
        guard let directory = state.sheet.directory else { return }
        inputs.listErrors[directory] = nil
        await loadListing(directory, generation: generation)
    }

    /// Validates `path` and saves it to the profile. `remember` receives the path's project as
    /// soon as it resolves, before the save, as Home's own resolutions did.
    func add(
        path: String,
        projects: @MainActor () -> [HomeProject],
        remember: @MainActor (String, HomeProjectResolution) -> Void
    ) async -> HomeAddProjectResult {
        let added = await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL())
        if added.contains(path) {
            return .alreadyAdded
        }
        inputs.isValidating = true
        inputs.fieldError = nil
        publish()
        do {
            // Validate the path is usable, then persist the selected directory (web
            // open-project behavior). Non-git dirs resolve /project/current to "/".
            let project = try await projectRepository.getCurrentProject(directory: path)
            let resolution = HomeProjectResolution(project)
            // A worktree of a repository already on Home joins that project.
            let joined = resolution.isMergeable
                ? projects().first { $0.key == resolution.id.trimmingCharacters(in: .whitespacesAndNewlines) }
                : nil
            remember(path, resolution)
            await connectionProfileStore.addWorktree(serverBaseURL: serverBaseURL(), worktree: path)
            return .added(resolution: resolution, joined: joined)
        } catch {
            inputs.isValidating = false
            inputs.fieldError = error.localizedDescription
            publish()
            return .failed
        }
    }

    /// Points the field at `folder` and lists it now (taps skip the typing pause).
    func showFolder(_ folder: String) async {
        listingTask?.cancel()
        inputs.fieldText = ServerPathPolicy.fieldText(folder)
        inputs.fieldError = nil
        publish()
        if let directory = state.sheet.directory {
            await loadListing(directory, generation: generation)
        }
    }

    /// Loads home, the added projects, the server's projects, and `/Volumes` for one presentation.
    private func loadSheet(generation: Int) async {
        inputs.pathsError = nil
        publish()
        let added = await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL())
        let paths: ServerPaths
        do {
            paths = try await filesystemRepository.getPaths()
        } catch {
            guard generation == self.generation else { return }
            DiagnosticLog.warning(.connection, "add project: server paths failed", error: error)
            inputs.added = added
            inputs.pathsError = AddProjectSheetState.listErrorMessage
            publish()
            return
        }
        guard generation == self.generation else { return }
        let home = ServerPathPolicy.canonical(paths.home)
        inputs.added = added
        inputs.addedProjectIDs = addedProjectIDs()
        inputs.home = home
        inputs.fieldText = ServerPathPolicy.fieldText(home)
        publish()

        let projectRepository = projectRepository
        let filesystemRepository = filesystemRepository
        // Suggestions and volumes are optional: failures hide them.
        async let projects = (try? await projectRepository.listProjects()) ?? []
        async let volumes: [DirectoryNode] = ServerPathPolicy.root(home) == "/" && !ServerPathPolicy.isWindows(home)
            ? (try? await filesystemRepository.listDirectory(path: "/Volumes"))?.folders ?? []
            : []
        if let directory = state.sheet.directory {
            await loadListing(directory, generation: generation)
        }
        let loadedProjects = await projects
        let loadedVolumes = await volumes
        guard generation == self.generation else { return }
        inputs.serverProjects = loadedProjects
        inputs.suggestionsResolved = true
        inputs.volumes = loadedVolumes.filter { !DirectoryListingPolicy.isHidden($0.name) }
        publish()
    }

    /// Lists the field's folder after the typing pause unless it is cached or already loading.
    private func scheduleListing() {
        listingTask?.cancel()
        listingTask = nil
        guard let directory = state.sheet.directory,
              inputs.listings[directory] == nil,
              inputs.listErrors[directory] == nil,
              !inputs.missingDirectories.contains(directory),
              !listingsInFlight.contains(directory)
        else { return }
        let generation = generation
        let debounce = listingDebounce
        listingTask = Task { [weak self] in
            if debounce > .zero {
                try? await Task.sleep(for: debounce)
            }
            guard !Task.isCancelled, let self else { return }
            // The fetch outlives later keystrokes so its listing still lands in the cache.
            Task { await self.loadListing(directory, generation: generation) }
        }
    }

    private func loadListing(_ directory: String, generation: Int) async {
        guard generation == self.generation,
              inputs.listings[directory] == nil,
              !inputs.missingDirectories.contains(directory),
              !listingsInFlight.contains(directory)
        else { return }
        listingsInFlight.insert(directory)
        inputs.listErrors[directory] = nil
        publish()
        let result: Result<DirectoryListing, Error>
        do {
            result = .success(try await filesystemRepository.listDirectory(path: directory))
        } catch {
            result = .failure(error)
        }
        guard generation == self.generation else { return }
        listingsInFlight.remove(directory)
        switch result {
        case let .success(listing):
            storeListing(listing, for: directory)
        case .failure(FilesystemError.directoryNotFound):
            inputs.missingDirectories.insert(directory)
        case let .failure(error):
            DiagnosticLog.warning(.connection, "add project: folder listing failed", error: error)
            inputs.listErrors[directory] = AddProjectSheetState.listErrorMessage
        }
        publish()
    }

    private func storeListing(_ listing: DirectoryListing, for directory: String) {
        inputs.listings[directory] = listing
        if ServerPathPolicy.isWindows(directory) {
            let root = ServerPathPolicy.root(directory)
            if !inputs.listedRoots.contains(root) {
                inputs.listedRoots.append(root)
            }
        }
    }

    private func resetLoads() {
        listingsInFlight = []
        summaryQueue = []
        summariesInFlight = []
        summaryFailures = []
    }

    /// Starts queued row listings up to `folderSummaryConcurrency` at once. A listing that
    /// lands fills the browser's cache, so entering that folder needs no new request.
    private func startSummaries() {
        let generation = generation
        while summariesInFlight.count < AddProjectSheetState.folderSummaryConcurrency,
              !summaryQueue.isEmpty {
            let path = summaryQueue.removeFirst()
            guard inputs.listings[path] == nil else { continue }
            summariesInFlight.insert(path)
            let filesystemRepository = filesystemRepository
            Task { [weak self] in
                let listing = try? await filesystemRepository.listDirectory(path: path)
                guard let self, generation == self.generation else { return }
                self.summariesInFlight.remove(path)
                if let listing {
                    if self.inputs.listings[path] == nil {
                        self.storeListing(listing, for: path)
                        self.publish()
                    }
                } else {
                    self.summaryFailures.insert(path)
                }
                self.startSummaries()
            }
        }
    }

    /// Rebuilds the sheet from its inputs and hands it to Home while the sheet is up.
    private func publish() {
        guard state.isPresented else { return }
        let sheet = AddProjectSheetState.make(inputs)
        if sheet != state.sheet {
            state.sheet = sheet
        }
        onSheetPublished?(sheet)
    }
}
