import Foundation
import Observation
import SwiftUI

@Observable
public final class ThemeManager {

    public static let shared = ThemeManager()

    /// The app whose theme picker this is: presets whose manifest `apps` leaves it out are not
    /// offered. Hex Code sets `"hexcode"` before first use.
    public static var appID = "opencode-mobile"

    private enum StorageKeys {
        public static let selectedThemeID = ThemePersistence.selectedThemeIDKey
    }

    private static let defaultPresetID = "porcelain"
    private static let defaultPresetName = "Porcelain"
    private static let presetBundleSubdirectories: [String?] = [
        "ThemePresets",
        "Resources/ThemePresets",
        nil,
    ]

    public private(set) var currentTheme: OpenCodeTheme
    public private(set) var currentSyntaxPreset: SyntaxHighlightPreset
    public private(set) var resolvedLight: ResolvedTokens
    public private(set) var resolvedDark: ResolvedTokens
    public private(set) var revision: UInt64 = 0
    /// Why the last theme or syntax preset change failed; shown in Settings.
    public private(set) var loadError: OpenCodeText?

    private let defaults: UserDefaults
    private var cachedManifestPresets: [ThemePreset]?
    private var cachedSyntaxPresets: [SyntaxHighlightPreset]?

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.resolvedLight = ResolvedTokens()
        self.resolvedDark = ResolvedTokens()
        self.currentTheme = Self.loadInitialTheme(defaults: defaults)
        self.currentSyntaxPreset = Self.loadInitialSyntaxPreset(defaults: defaults)
        resolve()
    }

    // MARK: - Resolution

    public func resolve() {
        let light = ThemeResolver.resolve(variant: currentTheme.light, isDark: false)
        let dark = ThemeResolver.resolve(variant: currentTheme.dark, isDark: true)
        resolvedLight = ThemeResolver.overlaySyntax(light, syntax: currentSyntaxPreset.light)
        resolvedDark = ThemeResolver.overlaySyntax(dark, syntax: currentSyntaxPreset.dark)
        revision &+= 1
    }

    public func tokens(for colorScheme: ColorScheme) -> ResolvedTokens {
        colorScheme == .dark ? resolvedDark : resolvedLight
    }

    // MARK: - Presets

    public func availablePresets() -> [ThemePreset] {
        manifestPresets()
    }

    public func availableSyntaxPresets() -> [SyntaxHighlightPreset] {
        syntaxPresets()
    }

    public func resolvedPreview(themeId: String) -> (ResolvedTokens, ResolvedTokens)? {
        guard let theme = Self.loadPreset(id: themeId) else { return nil }
        return (
            ThemeResolver.resolve(variant: theme.light, isDark: false),
            ThemeResolver.resolve(variant: theme.dark, isDark: true)
        )
    }

    public func syntaxTokensForPreview(preset: SyntaxHighlightPreset, isDark: Bool) -> [String: String] {
        if preset.isMatch {
            let variant = isDark ? currentTheme.dark : currentTheme.light
            let tokens = ThemeResolver.resolve(variant: variant, isDark: isDark).tokens
            return tokens.filter { $0.key.hasPrefix("syntax-") }
        }
        return isDark ? preset.dark : preset.light
    }

    public func applySyntaxPreset(id: String) {
        guard let preset = syntaxPresets().first(where: { $0.id == id }) else {
            loadError = "theme.error.syntax_preset_not_found"
            return
        }
        currentSyntaxPreset = preset
        resolve()
        ThemePersistence.saveSelectedSyntaxID(id, defaults: defaults)
        loadError = nil
    }

    public func applyPreset(id: String) {
        guard let theme = Self.loadPreset(id: id) else {
            loadError = "theme.error.preset_not_found"
            return
        }
        do {
            try ThemeSupport.validate(theme)
            currentTheme = theme
            resolve()
            ThemePersistence.saveSelectedThemeID(id, defaults: defaults)
            loadError = nil
        } catch {
            loadError = .verbatim(
                (error as? LocalizedError)?.errorDescription ?? error.localizedDescription,
                fallback: "theme.error.apply_failed"
            )
        }
    }

    // MARK: - Theme loading

    public func loadBundledTheme() {
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
        let presets = Self.loadPresetManifest().filter { $0.isOffered(in: Self.appID) }
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
            fatalError("Failed to load bundled porcelain.json: \(error)")
        }
    }

    public func clearAll() {
        ThemePersistence.clear(defaults: defaults)
        loadBundledTheme()
    }
}
