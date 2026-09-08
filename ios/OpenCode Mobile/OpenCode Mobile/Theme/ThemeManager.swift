import Foundation
import Observation
import SwiftUI

@Observable
final class ThemeManager {

    static let shared = ThemeManager()

    private enum StorageKeys {
        static let selectedThemeID = ThemePersistence.selectedThemeIDKey
    }

    private static let defaultPresetID = "graphite"
    private static let defaultPresetName = "Graphite"
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
        manifestPresets()
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

    // MARK: - Persistence

    private static func loadInitialTheme(defaults: UserDefaults) -> OpenCodeTheme {
        let defaultTheme = loadPreset(id: defaultPresetID) ?? bundledTheme()
        guard let selectedID = defaults.string(forKey: StorageKeys.selectedThemeID),
              !selectedID.isEmpty
        else {
            return defaultTheme
        }
        return loadPreset(id: selectedID) ?? defaultTheme
    }

    private func manifestPresets() -> [ThemePreset] {
        if let cachedManifestPresets {
            return cachedManifestPresets
        }
        let presets = Self.loadPresetManifest()
        cachedManifestPresets = presets
        return presets
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
        return [ThemePreset(id: defaultPresetID, name: defaultPresetName)]
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
            fatalError("Failed to load bundled graphite.json: \(error)")
        }
    }

    func clearAll() {
        ThemePersistence.clear(defaults: defaults)
        loadBundledTheme()
    }
}
