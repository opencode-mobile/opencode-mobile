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
    private(set) var currentSyntaxPreset: SyntaxHighlightPreset
    private(set) var resolvedLight: ResolvedTokens
    private(set) var resolvedDark: ResolvedTokens
    private(set) var revision: UInt64 = 0
    private(set) var loadError: String?

    private let defaults: UserDefaults
    private var cachedManifestPresets: [ThemePreset]?
    private var cachedSyntaxPresets: [SyntaxHighlightPreset]?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.resolvedLight = ResolvedTokens()
        self.resolvedDark = ResolvedTokens()
        self.currentTheme = Self.loadInitialTheme(defaults: defaults)
        self.currentSyntaxPreset = Self.loadInitialSyntaxPreset(defaults: defaults)
        resolve()
    }

    // MARK: - Resolution

    func resolve() {
        let light = ThemeResolver.resolve(variant: currentTheme.light, isDark: false)
        let dark = ThemeResolver.resolve(variant: currentTheme.dark, isDark: true)
        resolvedLight = ThemeResolver.overlaySyntax(light, syntax: currentSyntaxPreset.light)
        resolvedDark = ThemeResolver.overlaySyntax(dark, syntax: currentSyntaxPreset.dark)
        revision &+= 1
    }

    func tokens(for colorScheme: ColorScheme) -> ResolvedTokens {
        colorScheme == .dark ? resolvedDark : resolvedLight
    }

    // MARK: - Presets

    func availablePresets() -> [ThemePreset] {
        manifestPresets()
    }

    func availableSyntaxPresets() -> [SyntaxHighlightPreset] {
        syntaxPresets()
    }

    func resolvedPreview(themeId: String) -> (ResolvedTokens, ResolvedTokens)? {
        guard let theme = Self.loadPreset(id: themeId) else { return nil }
        return (
            ThemeResolver.resolve(variant: theme.light, isDark: false),
            ThemeResolver.resolve(variant: theme.dark, isDark: true)
        )
    }

    func syntaxTokensForPreview(preset: SyntaxHighlightPreset, isDark: Bool) -> [String: String] {
        if preset.isMatch {
            let variant = isDark ? currentTheme.dark : currentTheme.light
            let tokens = ThemeResolver.resolve(variant: variant, isDark: isDark).tokens
            return tokens.filter { $0.key.hasPrefix("syntax-") }
        }
        return isDark ? preset.dark : preset.light
    }

    func applySyntaxPreset(id: String) {
        guard let preset = syntaxPresets().first(where: { $0.id == id }) else {
            loadError = "Syntax highlight preset not found"
            return
        }
        currentSyntaxPreset = preset
        resolve()
        ThemePersistence.saveSelectedSyntaxID(id, defaults: defaults)
        loadError = nil
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
        currentSyntaxPreset = .match
        resolve()
        loadError = nil
    }

    // MARK: - Persistence

    private static func loadInitialSyntaxPreset(defaults: UserDefaults) -> SyntaxHighlightPreset {
        guard let selectedID = defaults.string(forKey: ThemePersistence.selectedSyntaxIDKey),
              !selectedID.isEmpty
        else {
            return .match
        }
        return loadSyntaxPreset(id: selectedID) ?? .match
    }

    private func syntaxPresets() -> [SyntaxHighlightPreset] {
        if let cachedSyntaxPresets {
            return cachedSyntaxPresets
        }
        let presets = Self.loadSyntaxManifest()
        cachedSyntaxPresets = presets
        return presets
    }

    private static func loadSyntaxManifest() -> [SyntaxHighlightPreset] {
        for subdirectory in ["SyntaxPresets", "Resources/SyntaxPresets", nil] {
            guard let url = Bundle.main.url(
                forResource: "syntax-manifest",
                withExtension: "json",
                subdirectory: subdirectory
            ),
            let data = try? Data(contentsOf: url),
            let entries = try? JSONDecoder().decode([SyntaxHighlightPreset].self, from: data),
            !entries.isEmpty
            else {
                continue
            }
            return entries.map { entry in
                if entry.id == SyntaxHighlightPreset.matchID {
                    return SyntaxHighlightPreset(
                        id: entry.id,
                        name: entry.name,
                        blurb: entry.blurb,
                        light: [:],
                        dark: [:]
                    )
                }
                return loadSyntaxPreset(id: entry.id) ?? entry
            }
        }
        return [.match]
    }

    private static func loadSyntaxPreset(id: String) -> SyntaxHighlightPreset? {
        if id == SyntaxHighlightPreset.matchID { return .match }
        for subdirectory in ["SyntaxPresets", "Resources/SyntaxPresets", nil] {
            if let url = Bundle.main.url(forResource: id, withExtension: "json", subdirectory: subdirectory),
               let data = try? Data(contentsOf: url),
               let preset = try? JSONDecoder().decode(SyntaxHighlightPreset.self, from: data)
            {
                return preset
            }
        }
        return nil
    }

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
