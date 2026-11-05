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
    private(set) var setup: HexSetup
    private(set) var projectPicker: ProjectPickerModel?
    private(set) var modelPage: ModelPageModel?
    private(set) var isSaving = false
    private(set) var saveError: String?
    let home: HomeModel
    /// Today's session on home; nil until round 2 wires OpenCode in (and with no demo preset).
    let session: TodaySession?
    let developer: DeveloperSettings
    let build: HexBuildInfo

    @ObservationIgnored private let services: any FirstRunServices
    @ObservationIgnored private let store: any HexSetupStoring

    init(
        setup: HexSetup,
        home: HomeModel,
        session: TodaySession? = nil,
        services: any FirstRunServices = LiveFirstRunServices(),
        store: any HexSetupStoring = HexSetupStore(),
        developer: DeveloperSettings? = nil,
        build: HexBuildInfo? = nil
    ) {
        self.setup = setup
        self.home = home
        self.session = session
        self.developer = developer ?? DeveloperSettings()
        self.build = build ?? .current
        self.services = services
        self.store = store
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

    /// A new project. Sessions are not wired up yet, so it only changes where the next one starts.
    func pickProject(_ project: ChosenProject) async {
        var next = setup
        next.project = project
        guard await save(next) else { return }
        path = [.settings]
        toasts.show(HexToastItem(
            tone: .steer,
            title: String(localized: "settings.project_changed", defaultValue: "Now in \(project.name)"),
            detail: String(localized: "settings.project_changed.sub", defaultValue: "The next session starts there.")
        ))
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
            title: String(localized: "settings.soon", defaultValue: "Not in this version yet"),
            detail: String(localized: "settings.soon.sub", defaultValue: "It comes with the session screen.")
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
