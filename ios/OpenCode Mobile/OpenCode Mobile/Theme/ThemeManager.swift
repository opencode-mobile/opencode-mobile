import Foundation
import Observation
import SwiftUI

@Observable
final class ThemeManager {

    static let shared = ThemeManager()

    private(set) var currentTheme: OpenCodeTheme
    private(set) var resolvedLight: ResolvedTokens
    private(set) var resolvedDark: ResolvedTokens
    private(set) var loadError: String?

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.currentTheme = ThemeManager.bundledTheme()
        self.resolvedLight = ResolvedTokens()
        self.resolvedDark = ResolvedTokens()
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

    // MARK: - Theme loading

    func loadBundledTheme() {
        currentTheme = Self.bundledTheme()
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
        if let existing = defaults.data(forKey: "importedThemes"),
           let dict = try? JSONDecoder().decode([String: Data].self, from: existing) {
            saved = dict
        }
        if let encoded = try? JSONEncoder().encode(theme) {
            saved[theme.id] = encoded
            if let data = try? JSONEncoder().encode(saved) {
                defaults.set(data, forKey: "importedThemes")
            }
        }
    }

    private func saveSelectedThemeID(_ id: String) {
        defaults.set(id, forKey: "selectedThemeID")
    }
}
