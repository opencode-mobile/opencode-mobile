import Foundation

/// One model a harness can run. `slug` is the wire value of a model selection.
nonisolated struct T3HarnessModel: Equatable, Sendable {
    var slug: String
    var name: String
    var shortName: String? = nil
    var isDefault: Bool = false
    var isLegacy: Bool = false

    /// Compact label for chips and picker rows.
    var label: String { shortName ?? name }
}

/// A configured provider instance of the T3 environment (`ServerProvider`).
/// `instanceID` is the routing key every model selection carries.
nonisolated struct T3Harness: Equatable, Sendable {
    var instanceID: String
    var displayName: String
    var driver: String
    var enabled: Bool
    /// Installed, authenticated (or unknown), and not in an error / disabled probe state.
    var available: Bool
    /// Runtime modes the harness accepts; empty when the server does not say.
    var supportedRuntimeModes: [String] = []
    var requiresNewThreadForModelChange: Bool = false
    var models: [T3HarnessModel] = []

    /// Can start a new thread right now.
    var isUsable: Bool { enabled && available }

    /// The model a fresh selection on this harness uses: its default, else its first.
    var defaultModel: T3HarnessModel? {
        models.first(where: \.isDefault) ?? models.first
    }

    func model(slug: String) -> T3HarnessModel? {
        models.first { $0.slug == slug }
    }
}

/// Provider catalogue and global default from `server.getConfig`.
nonisolated struct T3HarnessCatalog: Equatable, Sendable {
    var harnesses: [T3Harness]
    /// `settings.defaultModelSelection`; nil when the user never chose one.
    var defaultSelection: T3ModelSelection?

    static let empty = T3HarnessCatalog(harnesses: [], defaultSelection: nil)

    func harness(instanceID: String) -> T3Harness? {
        harnesses.first { $0.instanceID == instanceID }
    }

    var usableHarnesses: [T3Harness] {
        harnesses.filter { $0.isUsable && !$0.models.isEmpty }
    }

    /// `Codex · GPT-5` for a selection, using catalogue names when known.
    func label(for selection: T3ModelSelection) -> String {
        let harness = harness(instanceID: selection.instanceID)
        let provider = harness?.displayName ?? T3ProviderLabel.displayName(selection.instanceID) ?? selection.instanceID
        let model = harness?.model(slug: selection.model)?.label ?? selection.model
        return model.isEmpty ? provider : "\(provider) · \(model)"
    }
}

nonisolated extension T3JSON {
    static let serverGetConfigTag = "server.getConfig"

    /// Parses a `ServerConfig` reply. Providers missing a routing key are
    /// skipped instead of failing the whole catalogue (the contract is
    /// forward-compatible on provider shapes).
    static func harnessCatalog(from value: Any?) -> T3HarnessCatalog? {
        guard let config = object(value) else { return nil }
        let harnesses = array(config["providers"]).compactMap(harness(from:))
        let settings = object(config["settings"])
        return T3HarnessCatalog(
            harnesses: harnesses,
            defaultSelection: modelSelection(from: settings?["defaultModelSelection"])
        )
    }

    static func modelSelection(from value: Any?) -> T3ModelSelection? {
        guard let object = object(value),
              let instanceID = string(object, "instanceId", "provider"),
              let model = string(object["model"])
        else { return nil }
        return T3ModelSelection(instanceID: instanceID, model: model)
    }

    static func harness(from value: Any?) -> T3Harness? {
        guard let provider = object(value), let instanceID = string(provider, "instanceId") else { return nil }
        let driver = string(provider, "driver") ?? instanceID
        let status = string(provider, "status")?.lowercased() ?? "ready"
        let authStatus = string(object(provider["auth"])?["status"])?.lowercased() ?? "unknown"
        let availability = string(provider, "availability")?.lowercased() ?? "available"
        let installed = provider["installed"] == nil ? true : bool(provider["installed"])
        let available = installed
            && availability != "unavailable"
            && authStatus != "unauthenticated"
            && status != "error"
            && status != "disabled"
        let modes = array(provider["supportedRuntimeModes"])
            .compactMap { string($0) }
            .filter { T3RuntimeModeLabel.allModes.contains($0) }
        return T3Harness(
            instanceID: instanceID,
            displayName: string(provider, "displayName") ?? T3ProviderLabel.displayName(driver) ?? driver,
            driver: driver,
            enabled: provider["enabled"] == nil ? true : bool(provider["enabled"]),
            available: available,
            supportedRuntimeModes: modes,
            requiresNewThreadForModelChange: bool(provider["requiresNewThreadForModelChange"]),
            models: array(provider["models"]).compactMap(harnessModel(from:))
        )
    }

    static func harnessModel(from value: Any?) -> T3HarnessModel? {
        guard let model = object(value), let slug = string(model, "slug") else { return nil }
        return T3HarnessModel(
            slug: slug,
            name: string(model, "name") ?? slug,
            shortName: string(model, "shortName"),
            isDefault: bool(model["isDefault"]),
            isLegacy: bool(model["isLegacy"])
        )
    }
}

/// Picks the model selection a new thread launches with, following the T3 web
/// client: project override, then the global default, then the first usable
/// harness. A stored selection is kept as-is while its harness is usable;
/// falling back to another harness resets the model to that harness's default.
nonisolated enum T3ModelSelectionResolver {
    static let noHarnessMessage = "No T3 harness is available. Configure a provider in T3 Code first."

    /// - Parameters:
    ///   - preferred: an explicit or remembered pick; tried before the project override.
    static func resolve(
        catalog: T3HarnessCatalog,
        projectDefault: T3ModelSelection?,
        preferred: T3ModelSelection? = nil
    ) -> T3ModelSelection? {
        for candidate in [preferred, projectDefault, catalog.defaultSelection].compactMap({ $0 }) {
            guard let harness = catalog.harness(instanceID: candidate.instanceID), harness.isUsable else { continue }
            if !candidate.model.isEmpty { return candidate }
            if let model = harness.defaultModel {
                return T3ModelSelection(instanceID: harness.instanceID, model: model.slug)
            }
        }
        for harness in catalog.harnesses where harness.isUsable {
            if let model = harness.defaultModel {
                return T3ModelSelection(instanceID: harness.instanceID, model: model.slug)
            }
        }
        return nil
    }
}

/// Whether a started thread may switch to another selection, mirroring the
/// T3 web client: a thread that has not started may change anything; a
/// started one keeps its harness, and keeps its model too when the harness
/// requires a new thread for model changes.
nonisolated enum T3ModelChangeGate {
    static let switchHarnessNotice = "Start a new thread to switch harnesses. This thread cannot switch harnesses after it has started."

    static func modelChangeNotice(harnessName: String) -> String {
        "Start a new thread to change models. \(harnessName) does not allow switching models after a conversation has started."
    }

    static func blockReason(
        catalog: T3HarnessCatalog?,
        current: T3ModelSelection?,
        next: T3ModelSelection,
        hasStarted: Bool
    ) -> String? {
        guard hasStarted, let current, current != next else { return nil }
        if current.instanceID != next.instanceID { return switchHarnessNotice }
        guard let harness = catalog?.harness(instanceID: current.instanceID),
              harness.requiresNewThreadForModelChange
        else { return nil }
        return modelChangeNotice(harnessName: harness.displayName)
    }

    /// True when nothing but the current selection is allowed (model locked).
    static func isLocked(catalog: T3HarnessCatalog?, current: T3ModelSelection?, hasStarted: Bool) -> Bool {
        guard hasStarted, let current else { return false }
        return catalog?.harness(instanceID: current.instanceID)?.requiresNewThreadForModelChange == true
    }
}
