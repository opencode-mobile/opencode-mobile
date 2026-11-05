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
        case connecting
        case connected(server: PairedServer)
        case failed(PairingFailure)
    }

    private(set) var step: Step = .welcome
    private(set) var scan: ScanPhase = .scanning
    /// Changes whenever the camera should start over, so the scanner reads a new code.
    private(set) var scanAttempt = 0
    private(set) var server: PairedServer?
    private(set) var projectPicker: ProjectPickerModel?
    private(set) var project: ChosenProject?
    private(set) var modelPage: ModelPageModel?
    private(set) var isStarting = false
    private(set) var startError: String?

    @ObservationIgnored private let services: any FirstRunServices
    @ObservationIgnored private let store: any HexSetupStoring
    @ObservationIgnored private let onScanStarted: () -> Void
    @ObservationIgnored private let onFinished: (HexSetup) -> Void
    @ObservationIgnored private let connectedPause: Duration
    @ObservationIgnored private var pairingTask: Task<Void, Never>?

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
        scan = .connecting
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
        let page = ModelPageModel(
            repository: services.models(connection),
            tester: services.tester(connection),
            directory: project.path
        )
        modelPage = page
        await page.load()
    }

    func backFromModels() {
        modelPage?.cancel()
        step = .project
    }

    /// Saves the setup and leaves first run. Only offered after the model passed its test.
    func start() async {
        guard let server, let project, let choice = modelPage?.passedChoice, !isStarting else { return }
        isStarting = true
        startError = nil
        let setup = HexSetup(
            profile: server.profile,
            serverVersion: server.version,
            serverHostname: server.hostname,
            project: project,
            model: choice,
            modelName: modelPage?.selectedModelName
        )
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
