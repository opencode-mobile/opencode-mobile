import Foundation
import Observation
import OpenCodeKit

/// The project today's session runs in.
struct ChosenProject: Codable, Equatable, Sendable {
    let name: String
    let path: String

    init(name: String, path: String) {
        self.name = name
        self.path = path
    }

    init(path: String) {
        self.init(name: ServerPathPolicy.name(path), path: path)
    }
}

/// Pick a project and Browse folders, on OpenCode Mobile's Add project state: the server's
/// recent repositories, then a path field over the server's folders with a pinned
/// "Use this folder" bar. Nothing is added to the server; the pick only names a folder.
@MainActor
@Observable
final class ProjectPickerModel {
    private(set) var sheet = AddProjectSheetState.empty

    @ObservationIgnored private var inputs = AddProjectSheetInputs()
    @ObservationIgnored private let projects: any ProjectListing
    @ObservationIgnored private let filesystem: any FilesystemRepository
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var listingTask: Task<Void, Never>?
    @ObservationIgnored private var listingsInFlight: Set<String> = []
    @ObservationIgnored private var summaryQueue: [String] = []
    @ObservationIgnored private var summariesInFlight: Set<String> = []
    @ObservationIgnored private var summaryFailures: Set<String> = []
    @ObservationIgnored private let listingDebounce: Duration

    init(projects: any ProjectListing, filesystem: any FilesystemRepository, listingDebounce: Duration = .milliseconds(250)) {
        self.projects = projects
        self.filesystem = filesystem
        self.listingDebounce = listingDebounce
    }

    var isBrowsing: Bool { sheet.isBrowsing }

    /// Loads the server's home and recent repositories. Failures show in the list with Retry.
    func load() async {
        generation += 1
        let generation = generation
        listingTask?.cancel()
        listingsInFlight = []
        summaryQueue = []
        summariesInFlight = []
        summaryFailures = []
        inputs = AddProjectSheetInputs()
        publish()
        let paths: ServerPaths
        do {
            paths = try await filesystem.getPaths()
        } catch {
            guard generation == self.generation else { return }
            inputs.pathsError = AddProjectSheetState.listErrorMessage
            inputs.suggestionsResolved = true
            publish()
            return
        }
        guard generation == self.generation else { return }
        let home = ServerPathPolicy.canonical(paths.home)
        inputs.home = home
        inputs.fieldText = ServerPathPolicy.fieldText(home)
        publish()
        let projects = projects
        let filesystem = filesystem
        async let serverProjects = (try? await projects.listProjects()) ?? []
        async let volumes: [DirectoryNode] = ServerPathPolicy.root(home) == "/" && !ServerPathPolicy.isWindows(home)
            ? (try? await filesystem.listDirectory(path: "/Volumes"))?.folders ?? []
            : []
        let loadedProjects = await serverProjects
        let loadedVolumes = await volumes
        guard generation == self.generation else { return }
        inputs.serverProjects = loadedProjects
        inputs.suggestionsResolved = true
        inputs.volumes = loadedVolumes.filter { !DirectoryListingPolicy.isHidden($0.name) }
        publish()
    }

    func retry() async {
        if inputs.home.isEmpty {
            await load()
        } else if let directory = sheet.directory {
            inputs.listErrors[directory] = nil
            await loadListing(directory, generation: generation)
        }
    }

    /// Opens the browser at the server home and lists it.
    func browse() async {
        inputs.isBrowsing = true
        if !inputs.home.isEmpty {
            inputs.fieldText = ServerPathPolicy.fieldText(inputs.home)
        }
        publish()
        if let directory = sheet.directory {
            await loadListing(directory, generation: generation)
        }
    }

    /// Back from the browser to the project list.
    func closeBrowser() {
        listingTask?.cancel()
        inputs.isBrowsing = false
        if !inputs.home.isEmpty {
            inputs.fieldText = ServerPathPolicy.fieldText(inputs.home)
        }
        inputs.fieldError = nil
        publish()
    }

    /// Path field edits. Filtering is local; a new folder is listed after a short pause.
    func updateField(_ text: String) {
        guard text != inputs.fieldText else { return }
        inputs.fieldText = text
        inputs.fieldError = nil
        publish()
        scheduleListing()
    }

    /// Return in the path field opens the typed folder, or the only match. It never picks.
    func submitField() async {
        guard let target = sheet.goTarget else { return }
        await show(target)
    }

    func up() async {
        guard let target = sheet.upTarget else { return }
        await show(target)
    }

    func selectRoot(_ path: String) async { await show(path) }

    func openFolder(_ path: String) async { await show(path) }

    /// A browser row came on screen: load its own listing for the folder count and git tag.
    func rowAppeared(_ path: String) {
        guard inputs.listings[path] == nil, !summariesInFlight.contains(path),
              !summaryFailures.contains(path), !summaryQueue.contains(path)
        else { return }
        summaryQueue.append(path)
        startSummaries()
    }

    func rowDisappeared(_ path: String) {
        summaryQueue.removeAll { $0 == path }
    }

    /// The folder "Use this folder" names, while the bar allows it.
    var folderToUse: ChosenProject? {
        guard sheet.addEnabled, let target = sheet.addTarget else { return nil }
        return ChosenProject(path: target)
    }

    /// A row in the project list picks that repository.
    func suggestion(at path: String) -> ChosenProject? {
        sheet.suggestions.first { $0.path == path }.map { ChosenProject(name: $0.name, path: $0.path) }
    }

    private func show(_ folder: String) async {
        listingTask?.cancel()
        inputs.fieldText = ServerPathPolicy.fieldText(folder)
        inputs.fieldError = nil
        publish()
        if let directory = sheet.directory {
            await loadListing(directory, generation: generation)
        }
    }

    private func scheduleListing() {
        listingTask?.cancel()
        listingTask = nil
        guard let directory = sheet.directory, inputs.listings[directory] == nil,
              inputs.listErrors[directory] == nil, !inputs.missingDirectories.contains(directory),
              !listingsInFlight.contains(directory)
        else { return }
        let generation = generation
        let debounce = listingDebounce
        listingTask = Task { [weak self] in
            if debounce > .zero { try? await Task.sleep(for: debounce) }
            guard !Task.isCancelled, let self else { return }
            // The fetch outlives later keystrokes so its listing still lands in the cache.
            Task { await self.loadListing(directory, generation: generation) }
        }
    }

    private func loadListing(_ directory: String, generation: Int) async {
        guard generation == self.generation, inputs.listings[directory] == nil,
              !inputs.missingDirectories.contains(directory), !listingsInFlight.contains(directory)
        else { return }
        listingsInFlight.insert(directory)
        inputs.listErrors[directory] = nil
        publish()
        let result: Result<DirectoryListing, Error>
        do {
            result = .success(try await filesystem.listDirectory(path: directory))
        } catch {
            result = .failure(error)
        }
        guard generation == self.generation else { return }
        listingsInFlight.remove(directory)
        switch result {
        case let .success(listing):
            store(listing, for: directory)
        case .failure(FilesystemError.directoryNotFound):
            inputs.missingDirectories.insert(directory)
        case .failure:
            inputs.listErrors[directory] = AddProjectSheetState.listErrorMessage
        }
        publish()
    }

    private func store(_ listing: DirectoryListing, for directory: String) {
        inputs.listings[directory] = listing
        if ServerPathPolicy.isWindows(directory) {
            let root = ServerPathPolicy.root(directory)
            if !inputs.listedRoots.contains(root) { inputs.listedRoots.append(root) }
        }
    }

    private func startSummaries() {
        let generation = generation
        while summariesInFlight.count < AddProjectSheetState.folderSummaryConcurrency, !summaryQueue.isEmpty {
            let path = summaryQueue.removeFirst()
            guard inputs.listings[path] == nil else { continue }
            summariesInFlight.insert(path)
            let filesystem = filesystem
            Task { [weak self] in
                let listing = try? await filesystem.listDirectory(path: path)
                guard let self, generation == self.generation else { return }
                self.summariesInFlight.remove(path)
                if let listing {
                    if self.inputs.listings[path] == nil {
                        self.store(listing, for: path)
                        self.publish()
                    }
                } else {
                    self.summaryFailures.insert(path)
                }
                self.startSummaries()
            }
        }
    }

    private func publish() {
        let next = AddProjectSheetState.make(inputs)
        if next != sheet { sheet = next }
    }
}

/// The server's project list, as product `Project`s.
protocol ProjectListing: Sendable {
    func listProjects() async throws -> [Project]
}

struct ServerProjectListing: ProjectListing {
    let client: ProjectsAPIClient
    var adapter = ProjectDomainAdapter()

    func listProjects() async throws -> [Project] {
        try await client.listProjectsV2().map(adapter.project(from:))
    }
}
