import Foundation
import Observation
import OpenCodeKit

/// The server-facing pieces first run needs once a server is paired. Tests swap them for fakes.
protocol FirstRunServices: Sendable {
    var pairing: any ServerPairing { get }
    func projectListing(_ connection: ServerConnection) -> any ProjectListing
    func filesystem(_ connection: ServerConnection) -> any FilesystemRepository
    func models(_ connection: ServerConnection) -> any ModelRepository
    func tester(_ connection: ServerConnection) -> any ModelTesting
}

struct LiveFirstRunServices: FirstRunServices {
    var pairing: any ServerPairing = OpenCodeServerPairing()

    func projectListing(_ connection: ServerConnection) -> any ProjectListing {
        ServerProjectListing(client: ProjectsAPIClient(fixedConnection: connection))
    }

    func filesystem(_ connection: ServerConnection) -> any FilesystemRepository {
        DefaultFilesystemRepository(
            api: FilesystemAPIClient(fixedConnection: connection),
            activeConnectionManager: FixedServerProtocol(.v2)
        )
    }

    func models(_ connection: ServerConnection) -> any ModelRepository {
        DefaultModelRepository(
            api: ModelsAPIClient(connectionProvider: { connection }),
            adapter: ModelDomainAdapter(),
            activeConnectionManager: FixedServerProtocol(.v2)
        )
    }

    func tester(_ connection: ServerConnection) -> any ModelTesting {
        ModelTester(connection: connection)
    }
}

/// Hex Code only talks to OpenCode 2, which pairing has already checked.
final class FixedServerProtocol: ServerProtocolResolving {
    private let value: ServerProtocol

    init(_ value: ServerProtocol) { self.value = value }

    func currentProtocolOrProbe() async -> ServerProtocol { value }
}

/// First run, one Screen Model for the whole flow: Welcome → Scan → Pick a project (with
/// Browse folders) → Pick a model, which must pass its test before Start.
@MainActor
@Observable
final class FirstRunModel {
    enum Step: Equatable {
        case welcome
        case scan
        case project
        case model
    }

    enum ScanPhase: Equatable {
        case scanning
        case connecting(host: String)
        case connected(server: PairedServer)
        case failed(PairingFailure)
    }

    enum ModelsPhase: Equatable {
        case loading
        case loaded(ModelPickerState)
        case failed
    }

    private(set) var step: Step = .welcome
    private(set) var scan: ScanPhase = .scanning
    /// Changes whenever the camera should start over, so the scanner reads a new code.
    private(set) var scanAttempt = 0
    private(set) var server: PairedServer?
    private(set) var projectPicker: ProjectPickerModel?
    private(set) var project: ChosenProject?
    private(set) var models: ModelsPhase = .loading
    private(set) var isStarting = false
    private(set) var startError: String?

    @ObservationIgnored private let services: any FirstRunServices
    @ObservationIgnored private let store: any HexSetupStoring
    @ObservationIgnored private let onScanStarted: () -> Void
    @ObservationIgnored private let onFinished: (HexSetup) -> Void
    @ObservationIgnored private let connectedPause: Duration
    @ObservationIgnored private var pairingTask: Task<Void, Never>?
    @ObservationIgnored private var testTask: Task<Void, Never>?

    init(
        services: any FirstRunServices = LiveFirstRunServices(),
        store: any HexSetupStoring = HexSetupStore(),
        connectedPause: Duration = .milliseconds(900),
        onScanStarted: @escaping () -> Void = {},
        onFinished: @escaping (HexSetup) -> Void = { _ in }
    ) {
        self.services = services
        self.store = store
        self.connectedPause = connectedPause
        self.onScanStarted = onScanStarted
        self.onFinished = onFinished
    }

    private var connection: ServerConnection? {
        server.flatMap { ServerConnection(profile: $0.profile, serverProtocol: .v2) }
    }

    // MARK: - Welcome and scan

    func startScan() {
        step = .scan
        restartScanner()
        // The speech model downloads once, in the background, from here on.
        onScanStarted()
    }

    func scanAgain() {
        restartScanner()
    }

    func closeScan() {
        pairingTask?.cancel()
        pairingTask = nil
        step = .welcome
    }

    /// A QR was read. Only the first read of an attempt counts.
    func scanned(_ payload: String) {
        guard step == .scan, scan == .scanning else { return }
        let pairing = services.pairing
        scan = .connecting(host: pairing.host(of: payload) ?? "")
        let attempt = scanAttempt
        pairingTask = Task { [weak self] in
            let result = await pairing.pair(payload: payload)
            guard let self, !Task.isCancelled, attempt == self.scanAttempt, self.step == .scan else { return }
            switch result {
            case let .success(paired):
                self.server = paired
                self.scan = .connected(server: paired)
                try? await Task.sleep(for: self.connectedPause)
                guard !Task.isCancelled, attempt == self.scanAttempt, self.step == .scan else { return }
                await self.showProjects()
            case let .failure(failure):
                self.scan = .failed(failure)
            }
        }
    }

    private func restartScanner() {
        pairingTask?.cancel()
        pairingTask = nil
        scan = .scanning
        scanAttempt += 1
    }

    // MARK: - Project

    private func showProjects() async {
        guard let connection else { return }
        let picker = ProjectPickerModel(
            projects: services.projectListing(connection),
            filesystem: services.filesystem(connection)
        )
        projectPicker = picker
        step = .project
        await picker.load()
    }

    /// Back from the project list: scan again, since the code was used.
    func backFromProjects() {
        projectPicker = nil
        server = nil
        step = .scan
        restartScanner()
    }

    func pickProject(_ chosen: ChosenProject) async {
        project = chosen
        step = .model
        await loadModels()
    }

    // MARK: - Model

    func loadModels() async {
        guard let connection, let project else { return }
        models = .loading
        do {
            let catalog = try await services.models(connection).listModelOptions(directory: project.path)
            guard step == .model else { return }
            models = catalog.isEmpty ? .failed : .loaded(ModelPickerState(catalog: catalog))
        } catch {
            guard step == .model else { return }
            models = .failed
        }
    }

    func backFromModels() {
        testTask?.cancel()
        step = .project
    }

    func updateModels(_ change: (inout ModelPickerState) -> Void) {
        guard case var .loaded(picker) = models else { return }
        change(&picker)
        models = .loaded(picker)
    }

    func setQuery(_ query: String) { updateModels { $0.query = query } }

    func select(_ option: ModelSelectionOption) { updateModels { $0.select(option) } }

    func selectLevel(_ level: String) { updateModels { $0.selectLevel(level) } }

    /// Sends the one-line test prompt with the current model and level.
    func testModel() {
        guard let connection, case var .loaded(picker) = models, let choice = picker.beginTest() else { return }
        models = .loaded(picker)
        let tester = services.tester(connection)
        testTask = Task { [weak self] in
            let result = await tester.test(choice)
            guard let self else { return }
            self.updateModels { $0.finishTest(choice, result: result) }
        }
    }

    /// Saves the setup and leaves first run. Only offered after the model passed its test.
    func start() async {
        guard let server, let project, case let .loaded(picker) = models, picker.canStart,
              let choice = picker.choice, !isStarting
        else { return }
        isStarting = true
        startError = nil
        let setup = HexSetup(profile: server.profile, serverVersion: server.version, project: project, model: choice)
        do {
            try await store.save(setup)
            isStarting = false
            onFinished(setup)
        } catch {
            isStarting = false
            startError = String(localized: "first_run.start.save_failed", defaultValue: "Couldn’t save the setup on this phone. Try again.")
        }
    }
}
