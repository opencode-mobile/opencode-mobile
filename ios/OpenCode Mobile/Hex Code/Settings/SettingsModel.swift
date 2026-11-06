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
        didSet { current.setup = setup }
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
    let developer: DeveloperSettings
    let build: HexBuildInfo

    @ObservationIgnored private let services: any FirstRunServices
    @ObservationIgnored private let store: any HexSetupStoring
    @ObservationIgnored private let current: CurrentSetup

    init(
        setup: HexSetup,
        home: HomeModel,
        session: TodaySession? = nil,
        samplePreset: TodaySessionDemo? = nil,
        services: any FirstRunServices = LiveFirstRunServices(),
        store: any HexSetupStoring = HexSetupStore(),
        developer: DeveloperSettings? = nil,
        build: HexBuildInfo? = nil
    ) {
        self.setup = setup
        self.home = home
        let current = CurrentSetup(setup: setup)
        self.current = current
        realSession = session ?? Self.liveSession(current, toasts: home.toasts)
        self.developer = developer ?? DeveloperSettings()
        self.build = build ?? .current
        self.services = services
        self.store = store
        home.sender = realSession
        if let samplePreset { showSample(samplePreset) }
    }

    /// What home shows: the sample session when one is on, else the real one.
    var session: TodaySession { sampleSession ?? realSession }

    /// Today's session on the paired server, following the project and model chosen now.
    private static func liveSession(_ current: CurrentSetup, toasts: HexToaster) -> TodaySession {
        let connection = ServerConnection(profile: current.setup.profile, serverProtocol: .v2)
        let service = connection.map(OpenCodeSessionService.init(connection:))
        let stream = connection.map { LiveEventStream(transport: OpenCodeEventTransport(connection: $0)) }
        stream?.watchNetwork()
        let session = TodaySession(
            setup: DailySessionSetup(service: service ?? UnpairedSessionService(), memory: DailySessionDefaults()),
            summary: StatusSummaryModel(store: SummaryDefaults()),
            directory: { current.setup.project.path },
            model: { current.setup.model },
            toasts: toasts,
            api: service,
            stream: stream
        )
        session.computerHost = URL(string: current.setup.profile.baseURL)?.host
        return session
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
        await realSession.switchModel(choice)
    }

    // MARK: Version and developer settings

    /// A tap on the Version row; the tenth quick one turns developer settings on.
    func tapVersion() {
        guard developer.tapVersion() else { return }
        toasts.show(HexToastItem(tone: .steer, title: String(localized: "settings.developer.on", defaultValue: "Developer settings on")))
    }

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

/// A saved server that no longer makes a connection: setup fails, with Try again.
private struct UnpairedSessionService: DailySessionService {
    struct Unpaired: Error {}

    func sessionsPage(directory: String, search: String, limit: Int, cursor: String?) async throws -> SessionListingPage {
        throw Unpaired()
    }

    func createSession(title: String, directory: String, model: ModelChoice) async throws -> SessionListing {
        throw Unpaired()
    }

    func putMessageRules(sessionID: String) async throws {
        throw Unpaired()
    }
}
