import Foundation
import Observation
import SwiftUI

@Observable
final class ThemeManager {

    static let shared = ThemeManager()

    private enum StorageKeys {
        static let importedThemes = ThemePersistence.importedThemesKey
        static let selectedThemeID = ThemePersistence.selectedThemeIDKey
    }

    private static let defaultPresetID = "oc-2"
    private static let presetBundleSubdirectories: [String?] = [
        "ThemePresets",
        "Resources/ThemePresets",
        nil,
    ]

    private(set) var currentTheme: OpenCodeTheme
    private(set) var resolvedLight: ResolvedTokens
    private(set) var resolvedDark: ResolvedTokens
    private(set) var revision: UInt64 = 0
    private(set) var loadError: String?

    private let defaults: UserDefaults
    private var cachedManifestPresets: [ThemePreset]?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.resolvedLight = ResolvedTokens()
        self.resolvedDark = ResolvedTokens()
        self.currentTheme = Self.loadInitialTheme(defaults: defaults)
        resolve()
    }

    // MARK: - Resolution

    func resolve() {
        resolvedLight = ThemeResolver.resolve(variant: currentTheme.light, isDark: false)
        resolvedDark = ThemeResolver.resolve(variant: currentTheme.dark, isDark: true)
        revision &+= 1
    }

    func tokens(for colorScheme: ColorScheme) -> ResolvedTokens {
        colorScheme == .dark ? resolvedDark : resolvedLight
    }

    // MARK: - Presets

    func availablePresets() -> [ThemePreset] {
        ThemeSupport.mergePresets(
            bundled: manifestPresets(),
            imported: ThemePersistence.importedPresets(defaults: defaults)
        )
    }

    func applyPreset(id: String) {
        guard let theme = Self.loadPreset(id: id) else {
            loadError = "Theme preset not found"
            return
        }
        do {
            try ThemeSupport.validate(theme)
            currentTheme = theme
            resolve()
            ThemePersistence.saveSelectedThemeID(id, defaults: defaults)
            loadError = nil
        } catch {
            loadError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    // MARK: - Theme loading

    func loadBundledTheme() {
        currentTheme = Self.loadPreset(id: Self.defaultPresetID) ?? Self.bundledTheme()
        resolve()
        loadError = nil
    }

    func importTheme(from data: Data) throws {
        let previousTheme = currentTheme
        do {
            try ThemeSupport.assertImportSize(data.count)
            let decoder = JSONDecoder()
            let theme = try decoder.decode(OpenCodeTheme.self, from: data)
            try ThemeSupport.validate(theme)
            try ThemeSupport.assertNoPresetIdCollision(
                themeID: theme.id,
                bundledPresetIDs: manifestPresetIDs()
            )
            currentTheme = theme
            resolve()
            try ThemePersistence.persistImportedTheme(theme, defaults: defaults)
            ThemePersistence.saveSelectedThemeID(theme.id, defaults: defaults)
            loadError = nil
        } catch {
            currentTheme = previousTheme
            resolve()
            loadError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            throw error
        }
    }

    func importTheme(from url: URL) throws {
        let data = try Data(contentsOf: url)
        try importTheme(from: data)
    }

    // MARK: - Persistence

    private static func loadInitialTheme(defaults: UserDefaults) -> OpenCodeTheme {
        let defaultTheme = loadPreset(id: defaultPresetID) ?? bundledTheme()
        guard let selectedID = defaults.string(forKey: StorageKeys.selectedThemeID),
              !selectedID.isEmpty
        else {
            return defaultTheme
        }
        if let imported = ThemePersistence.loadImportedTheme(id: selectedID, defaults: defaults) {
            return imported
        }
        if let preset = loadPreset(id: selectedID) {
            return preset
        }
        return defaultTheme
    }

    private func manifestPresets() -> [ThemePreset] {
        if let cachedManifestPresets {
            return cachedManifestPresets
        }
        let presets = Self.loadPresetManifest()
        cachedManifestPresets = presets
        return presets
    }

    private func manifestPresetIDs() -> Set<String> {
        Set(manifestPresets().map(\.id))
    }

    private static func presetResourceURL(
        name: String,
        extension ext: String = "json"
    ) -> URL? {
        for subdirectory in presetBundleSubdirectories {
            if let url = Bundle.main.url(
                forResource: name,
                withExtension: ext,
                subdirectory: subdirectory
            ) {
                return url
            }
        }
        return nil
    }

    private static func loadPresetManifest() -> [ThemePreset] {
        for subdirectory in presetBundleSubdirectories {
            guard let url = Bundle.main.url(
                forResource: "manifest",
                withExtension: "json",
                subdirectory: subdirectory
            ),
            let data = try? Data(contentsOf: url),
            let presets = try? JSONDecoder().decode([ThemePreset].self, from: data),
            presets.count > 1
            else {
                continue
            }
            return presets
        }
        return [ThemePreset(id: defaultPresetID, name: "OC-2")]
    }

    private static func loadPreset(id: String) -> OpenCodeTheme? {
        guard let url = presetResourceURL(name: id),
              let data = try? Data(contentsOf: url)
        else {
            return nil
        }
        return try? JSONDecoder().decode(OpenCodeTheme.self, from: data)
    }

    private static func bundledTheme() -> OpenCodeTheme {
        guard let asset = NSDataAsset(name: "ThemeJson") else {
            fatalError("ThemeJson data asset not found")
        }
        do {
            let decoder = JSONDecoder()
            return try decoder.decode(OpenCodeTheme.self, from: asset.data)
        } catch {
            fatalError("Failed to load bundled oc-2.json: \(error)")
        }
    }

    func clearAll() {
        ThemePersistence.clear(defaults: defaults)
        loadBundledTheme()
    }
}
