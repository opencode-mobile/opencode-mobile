import Foundation
import Observation
import SwiftUI

@Observable
final class ThemeManager {

    static let shared = ThemeManager()

    private enum StorageKeys {
        static let importedThemes = "importedThemes"
        static let selectedThemeID = "selectedThemeID"
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
    private(set) var loadError: String?

    private let defaults: UserDefaults
    private var cachedPresets: [ThemePreset]?

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
    }

    func tokens(for colorScheme: ColorScheme) -> ResolvedTokens {
        colorScheme == .dark ? resolvedDark : resolvedLight
    }

    // MARK: - Presets

    func availablePresets() -> [ThemePreset] {
        if let cachedPresets {
            return cachedPresets
        }
        let presets = Self.loadPresetManifest()
        cachedPresets = presets
        return presets
    }

    func applyPreset(id: String) {
        guard let theme = Self.loadPreset(id: id) else {
            loadError = "Theme preset not found"
            return
        }
        do {
            try validate(theme)
            currentTheme = theme
            resolve()
            saveSelectedThemeID(id)
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
        let decoder = JSONDecoder()
        let theme = try decoder.decode(OpenCodeTheme.self, from: data)
        try validate(theme)
        currentTheme = theme
        resolve()
        persistImportedTheme(theme)
        saveSelectedThemeID(theme.id)
        loadError = nil
    }

    func importTheme(from url: URL) throws {
        let data = try Data(contentsOf: url)
        try importTheme(from: data)
    }

    // MARK: - Validation

    private func validate(_ theme: OpenCodeTheme) throws {
        let required = ["neutral", "ink", "primary", "success", "warning", "error", "info"]
        for variant in [theme.light, theme.dark] {
            let mirror = Mirror(reflecting: variant.palette)
            let missing = required.filter { key in
                !mirror.children.contains { child in
                    guard child.label == key, let value = child.value as? String else { return false }
                    return !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                }
            }
            if !missing.isEmpty {
                throw ThemeError.missingPaletteSeeds(missing)
            }
        }
    }

    // MARK: - Persistence

    private static func loadInitialTheme(defaults: UserDefaults) -> OpenCodeTheme {
        let defaultTheme = loadPreset(id: defaultPresetID) ?? bundledTheme()
        guard let selectedID = defaults.string(forKey: StorageKeys.selectedThemeID),
              !selectedID.isEmpty
        else {
            return defaultTheme
        }
        if let imported = loadImportedTheme(id: selectedID, defaults: defaults) {
            return imported
        }
        if let preset = loadPreset(id: selectedID) {
            return preset
        }
        return defaultTheme
    }

    private static func loadImportedTheme(id: String, defaults: UserDefaults) -> OpenCodeTheme? {
        guard let themesData = defaults.data(forKey: StorageKeys.importedThemes),
              let themes = try? JSONDecoder().decode([String: Data].self, from: themesData),
              let themeData = themes[id]
        else {
            return nil
        }
        return try? JSONDecoder().decode(OpenCodeTheme.self, from: themeData)
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

    private func persistImportedTheme(_ theme: OpenCodeTheme) {
        var saved: [String: Data] = [:]
        if let existing = defaults.data(forKey: StorageKeys.importedThemes),
           let dict = try? JSONDecoder().decode([String: Data].self, from: existing) {
            saved = dict
        }
        if let encoded = try? JSONEncoder().encode(theme) {
            saved[theme.id] = encoded
            if let data = try? JSONEncoder().encode(saved) {
                defaults.set(data, forKey: StorageKeys.importedThemes)
            }
        }
    }

    private func saveSelectedThemeID(_ id: String) {
        defaults.set(id, forKey: StorageKeys.selectedThemeID)
    }

    func clearAll() {
        defaults.removeObject(forKey: StorageKeys.importedThemes)
        defaults.removeObject(forKey: StorageKeys.selectedThemeID)
        loadBundledTheme()
    }
}
