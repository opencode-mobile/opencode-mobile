import Foundation
import Observation
import OpenCodeKit
import SwiftUI

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
    }

    /// The navigation stack over home. Back and the edge swipe pop it directly.
    var path: [Route] = []
    private(set) var setup: HexSetup
    private(set) var projectPicker: ProjectPickerModel?
    private(set) var modelPage: ModelPageModel?
    private(set) var isSaving = false
    private(set) var saveError: String?
    private(set) var note: HomeToast?
    let home: HomeModel

    @ObservationIgnored private let services: any FirstRunServices
    @ObservationIgnored private let store: any HexSetupStoring
    @ObservationIgnored private var noteTask: Task<Void, Never>?

    init(setup: HexSetup, home: HomeModel, services: any FirstRunServices = LiveFirstRunServices(), store: any HexSetupStoring = HexSetupStore()) {
        self.setup = setup
        self.home = home
        self.services = services
        self.store = store
    }

    private var connection: ServerConnection? { ServerConnection(profile: setup.profile, serverProtocol: .v2) }

    var serverLabel: String { setup.serverName }

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
        show(HomeToast(
            title: String(localized: "settings.project_changed", defaultValue: "Now in \(project.name)"),
            subtitle: String(localized: "settings.project_changed.sub", defaultValue: "The next session starts there.")
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

    // MARK: Placeholders

    func showPlaceholder() {
        show(HomeToast(
            title: String(localized: "settings.soon", defaultValue: "Not in this version yet"),
            subtitle: String(localized: "settings.soon.sub", defaultValue: "It comes with the session screen.")
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

    private func show(_ next: HomeToast) {
        note = next
        noteTask?.cancel()
        noteTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2.4))
            guard !Task.isCancelled, self?.note?.id == next.id else { return }
            self?.note = nil
        }
    }
}
