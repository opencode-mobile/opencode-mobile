import Foundation
import Observation
import OpenCodeKit
import SwiftUI
import UIKit

/// Light, dark, or the system's.
enum HexAppearance: String, CaseIterable, Sendable {
    case auto
    case light
    case dark

    static let key = "hexcode.appearance"

    var colorScheme: ColorScheme? {
        switch self {
        case .auto: nil
        case .light: .light
        case .dark: .dark
        }
    }

    var next: HexAppearance {
        switch self {
        case .auto: .light
        case .light: .dark
        case .dark: .auto
        }
    }

    var label: String {
        switch self {
        case .auto: String(localized: "settings.appearance.auto", defaultValue: "Auto")
        case .light: String(localized: "settings.appearance.light", defaultValue: "Light")
        case .dark: String(localized: "settings.appearance.dark", defaultValue: "Dark")
        }
    }
}

/// After first run: home, Settings, and the project and model pages Settings opens.
@MainActor
@Observable
final class HexMainModel {
    /// Pages pushed over home, in Settings' navigation stack.
    enum Route: Hashable {
        case settings
        case project
        case browse
        case model
        /// Developer settings' component gallery.
        case gallery
    }

    /// The navigation stack over home. Back and the edge swipe pop it directly.
    var path: [Route] = []
    private(set) var setup: HexSetup {
        didSet {
            current.setup = setup
            if setup.profile != oldValue.profile { followConnection() }
        }
    }
    private(set) var projectPicker: ProjectPickerModel?
    private(set) var modelPage: ModelPageModel?
    private(set) var isSaving = false
    private(set) var saveError: String?
    let home: HomeModel
    /// Today's session on the paired server.
    let realSession: TodaySession
    /// Developer settings' Sample session: a fake session shown on home instead of the real one.
    /// Not saved, so the app always starts on the real session.
    private(set) var samplePreset: TodaySessionDemo?
    private(set) var sampleSession: TodaySession?
    let build: HexBuildInfo
    /// Artifacts on this computer and project: Settings' row, the enable sheet, the card's tile.
    let artifacts: ArtifactsModel

    /// Pair again's scan cover, while it is up.
    var pairAgain: PairAgainModel?
    /// Another computer was paired and Switch confirmed: first run's project and model pages
    /// follow there (the app model takes it from here).
    @ObservationIgnored var onSwitchServer: (PairedServer) -> Void = { _ in }

    @ObservationIgnored private let services: any FirstRunServices
    @ObservationIgnored private let store: any HexSetupStoring
    @ObservationIgnored private let current: CurrentSetup
    @ObservationIgnored private let liveConnection: LiveConnection
    @ObservationIgnored private let checks: any ServerChecking
    @ObservationIgnored private let now: () -> Date

    init(
        setup: HexSetup,
        home: HomeModel,
        session: TodaySession? = nil,
        samplePreset: TodaySessionDemo? = nil,
        services: any FirstRunServices = LiveFirstRunServices(),
        store: any HexSetupStoring = HexSetupStore(),
        build: HexBuildInfo? = nil,
        checks: any ServerChecking = LiveServerChecks(),
        now: @escaping () -> Date = Date.init
    ) {
        self.setup = setup
        self.home = home
        let current = CurrentSetup(setup: setup)
        self.current = current
        let liveConnection = LiveConnection(setup.profile)
        self.liveConnection = liveConnection
        self.checks = checks
        self.now = now
        let stream = session == nil ? LiveEventStream(transport: OpenCodeEventTransport(connection: { liveConnection.current })) : nil
        realSession = session ?? Self.liveSession(current, connection: liveConnection, stream: stream!, toasts: home.toasts)
        let realSession = realSession
        artifacts = ArtifactsModel(
            api: session == nil ? OpenCodeArtifactsService(connection: { liveConnection.current }) : nil,
            directory: { current.setup.project.path },
            model: { current.setup.model },
            todaySessionID: { realSession.sessionID },
            toasts: home.toasts
        )
        self.build = build ?? .current
        self.services = services
        self.store = store
        home.sender = realSession
        realSession.serverName = setup.serverName
        realSession.computerHost = URL(string: setup.profile.baseURL)?.host
        realSession.serverCheck = { [weak self] in await self?.checkServer() ?? .ready }
        let artifacts = artifacts
        artifacts.serverName = { [weak self] in self?.setup.serverName ?? "" }
        artifacts.onSuggestion = { [weak self] text in
            self?.home.addSuggestion(text)
            self?.path = []
        }
        realSession.artifactSource = { rootID, sessions in await artifacts.cardArtifact(rootID: rootID, sessions: sessions) }
        realSession.onOpened = { await artifacts.sessionOpened() }
        realSession.onRecentTools = { artifacts.noteTools($0) }
        stream?.onLine = { artifacts.received(line: $0) }
        // Today's saved card, from the phone, so the first frame shows it (app plan, "Cold start").
        realSession.preopen()
        if let samplePreset { showSample(samplePreset) }
    }

    /// After home's first frame (app plan, "Cold start"): everything that needs the network. Today's
    /// session opens (the sign-in renewal, finding it, its rules, the stream), and the upload route
    /// and today's model's image input are read; each fills its slot when it arrives.
    func start() async {
        Task { await refreshImageInput() }
        Task { await refreshFileUploads() }
        await realSession.open()
    }

    /// Whether files can be uploaded, from the server's API contract and `paths.tmp` (app plan,
    /// "Attach files (M7)"): Files is dimmed until then, and on a server without them.
    func refreshFileUploads() async {
        guard let connection, let features = await checks.features(connection) else { return }
        let live = liveConnection
        let current = current
        home.uploader = HomeModel.uploader(features: features, connection: { live.current }, directory: { current.setup.project.path })
    }

    /// Whether today's model can see images, from the server's model list (app plan, "Attach").
    /// Unknown until read, and for a model the list doesn't have: counted as yes.
    func refreshImageInput() async {
        #if DEBUG
        // `-HexCodeNoVision YES`: today's model can't see images, to try that path.
        if UserDefaults.standard.bool(forKey: "HexCodeNoVision") {
            home.imageInput = ImageInput(canSee: false, modelName: setup.modelName ?? setup.model.modelID)
            return
        }
        #endif
        let choice = setup.model
        home.imageInput = ImageInput(canSee: true, modelName: setup.modelName ?? choice.modelID)
        guard let connection,
              let catalog = try? await services.models(connection).listModelOptions(directory: setup.project.path),
              setup.model == choice
        else { return }
        home.imageInput = ImageInput.of(choice, in: catalog.options, savedName: setup.modelName)
    }

    /// What home shows: the sample session when one is on, else the real one.
    var session: TodaySession { sampleSession ?? realSession }

    /// Today's session on the paired server, following the project and model chosen now, and the
    /// address and sign-in in use.
    private static func liveSession(_ current: CurrentSetup, connection: LiveConnection, stream: LiveEventStream, toasts: HexToaster) -> TodaySession {
        let service = OpenCodeSessionService(connection: { connection.current })
        stream.watchNetwork()
        return TodaySession(
            setup: DailySessionSetup(service: service, memory: DailySessionDefaults()),
            summary: StatusSummaryModel(store: SummaryDefaults()),
            directory: { current.setup.project.path },
            model: { current.setup.model },
            toasts: toasts,
            api: service,
            control: service,
            goals: service,
            stream: stream,
            ask: AskModel(store: AskDefaults())
        )
    }

    /// The clients read the new address or sign-in from here on; tiles and the signed-out line follow.
    private func followConnection() {
        liveConnection.update(setup.profile)
        artifacts.connectionChanged()
        realSession.computerHost = URL(string: setup.profile.baseURL)?.host
        realSession.serverName = setup.serverName
    }

    /// Before each setup (`ServerUpkeep`): the version, the address in use and the sign-in's
    /// renewal, saved when they changed.
    func checkServer() async -> UpkeepOutcome {
        let before = setup
        let (next, outcome) = await ServerUpkeep.run(before, checks: checks, now: now())
        // Settings may have changed the project or model meanwhile: only the server's parts apply.
        var merged = setup
        merged.profile = next.profile
        merged.serverVersion = next.serverVersion
        merged.signedInAt = next.signedInAt
        if merged != setup { _ = await save(merged) }
        return outcome
    }

    /// Closes today's session's stream before this model goes (Forget, or a switch to another computer).
    func close() {
        realSession.close()
    }

    // MARK: Pair again

    /// Server details' Pair again, and Signed out's: first run's scan as a full-screen cover.
    func startPairAgain() {
        pairAgain = PairAgainModel(
            current: setup,
            pairing: services.pairing,
            onSameComputer: { [weak self] server in await self?.pairedAgain(server) },
            onSwitch: { [weak self] server in
                self?.pairAgain = nil
                self?.onSwitchServer(server)
            }
        )
    }

    func cancelPairAgain() {
        pairAgain = nil
    }

    /// The same computer: its new sign-in and addresses replace the old ones (the old profile and
    /// its password go); the project, model and today's session stay.
    func pairedAgain(_ server: PairedServer) async {
        let next = PairAgain.replacing(setup, with: server, now: now())
        guard await save(next) else {
            pairAgain = nil
            return
        }
        pairAgain = nil
        toasts.show(PairAgain.pairedNotice(name: setup.serverName))
        await refreshFileUploads()
        await realSession.open()
    }

    /// The Sample session row: the next preset, then Off.
    func cycleSampleSession() {
        showSample(TodaySessionDemo.next(after: samplePreset))
    }

    private func showSample(_ preset: TodaySessionDemo?) {
        samplePreset = preset
        guard let preset else {
            sampleSession = nil
            home.sender = realSession
            return
        }
        let sample = preset.make(toasts: toasts)
        sample.computerHost = realSession.computerHost ?? "192.168.1.2"
        sample.serverName = setup.serverName
        sampleSession = sample
        home.sender = sample
        Task { await preset.start(sample) }
    }

    private var connection: ServerConnection? { ServerConnection(profile: setup.profile, serverProtocol: .v2) }

    var serverLabel: String { setup.serverName }

    /// Notices at the top of the screen, shared by home and every page over it.
    var toasts: HexToaster { home.toasts }

    func openSettings() { path = [.settings] }

    /// Back from the page on top.
    func back() {
        guard !path.isEmpty else { return }
        path.removeLast()
    }

    // MARK: Project

    func openProject() async {
        guard let connection else { return }
        let picker = ProjectPickerModel(projects: services.projectListing(connection), filesystem: services.filesystem(connection))
        projectPicker = picker
        path.append(.project)
        await picker.load()
    }

    /// Browse folders, pushed over the project list.
    func browseFolders() async {
        guard let picker = projectPicker else { return }
        path.append(.browse)
        await picker.browse()
    }

    /// A new project: today's session there opens at once.
    func pickProject(_ project: ChosenProject) async {
        var next = setup
        next.project = project
        guard await save(next) else { return }
        path = [.settings]
        toasts.show(HexToastItem(
            tone: .steer,
            title: String(localized: "settings.project.changed", defaultValue: "Now in \(project.name)")
        ))
        await realSession.projectChanged()
    }

    // MARK: Model

    func openModel() async {
        guard let connection else { return }
        let page = ModelPageModel(
            repository: services.models(connection),
            tester: services.tester(connection),
            directory: setup.project.path,
            saved: setup.model
        )
        modelPage = page
        saveError = nil
        path.append(.model)
        await page.load()
    }

    /// Save applies from the next message, after the pick passed its test.
    func saveModel() async {
        guard let choice = modelPage?.passedChoice else { return }
        var next = setup
        next.model = choice
        next.modelName = modelPage?.selectedModelName
        guard await save(next) else { return }
        path = [.settings]
        Task { await refreshImageInput() }
        await realSession.switchModel(choice)
    }

    // MARK: Start a new session

    /// The confirmation is open.
    var showsNewSession = false
    private(set) var isStartingSession = false

    /// The confirmation's extra lines: the agent still works there, and messages still wait.
    var newSessionBusy: Bool { realSession.agentBusy }
    var newSessionWaiting: Int { realSession.waitingCount }

    func confirmNewSession() {
        showsNewSession = true
    }

    /// Start new session: on success Settings closes and home shows the new session; if creating
    /// fails the sheet stays with the notice; if its rules fail, home shows "Couldn't open today's
    /// session".
    func startNewSession() async {
        guard !isStartingSession else { return }
        isStartingSession = true
        let result = await realSession.startNewSession()
        isStartingSession = false
        guard result != .failed else { return }
        showsNewSession = false
        toasts.inSheet = false
        path = []
    }

    // MARK: Developer settings

    func openGallery() { path.append(.gallery) }

    /// Copies the build line, for a bug report. Developer copy: English only.
    func copyBuild() {
        UIPasteboard.general.string = build.summary
        toasts.show(HexToastItem(tone: .steer, title: "Build copied", detail: build.summary))
    }

    // MARK: Placeholders

    func showPlaceholder() {
        toasts.show(HexToastItem(
            tone: .info,
            title: String(localized: "settings.soon", defaultValue: "Not in this version yet")
        ))
    }

    private func save(_ next: HexSetup) async -> Bool {
        isSaving = true
        defer { isSaving = false }
        do {
            try await store.save(next)
            setup = next
            saveError = nil
            return true
        } catch {
            saveError = String(localized: "settings.save_failed", defaultValue: "Couldn’t save this on the phone. Try again.")
            return false
        }
    }
}

/// The setup the real session reads when it opens: the project and model can change in Settings.
@MainActor
private final class CurrentSetup {
    var setup: HexSetup

    init(setup: HexSetup) {
        self.setup = setup
    }
}
