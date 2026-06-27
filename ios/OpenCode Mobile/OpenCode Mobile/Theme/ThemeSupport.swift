import Foundation

enum ThemeSupport {
    static let maxImportBytes = 512 * 1024

    static func mergePresets(
        bundled: [ThemePreset],
        imported: [ThemePreset]
    ) -> [ThemePreset] {
        let bundledIDs = Set(bundled.map(\.id))
        let extra =
            imported
                .filter { !bundledIDs.contains($0.id) }
                .sorted {
                    $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
                }
        return bundled + extra
    }

    static func assertImportSize(_ byteCount: Int) throws {
        guard byteCount <= maxImportBytes else {
            throw ThemeError.importTooLarge(maxImportBytes)
        }
    }

    static func assertNoPresetIdCollision(
        themeID: String,
        bundledPresetIDs: Set<String>
    ) throws {
        if bundledPresetIDs.contains(themeID) {
            throw ThemeError.presetIdConflict(themeID)
        }
    }

    static func validate(_ theme: OpenCodeTheme) throws {
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
}

enum ThemePersistence {
    static let importedThemesKey = "importedThemes"
    static let selectedThemeIDKey = "selectedThemeID"

    static func importedPresets(defaults: UserDefaults) -> [ThemePreset] {
        guard let themesData = defaults.data(forKey: importedThemesKey),
              let themes = try? JSONDecoder().decode([String: Data].self, from: themesData)
        else {
            return []
        }
        return themes.compactMap { _, data in
            guard let theme = try? JSONDecoder().decode(OpenCodeTheme.self, from: data) else {
                return nil
            }
            return ThemePreset(id: theme.id, name: theme.name)
        }
        .sorted {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    static func loadImportedTheme(
        id: String,
        defaults: UserDefaults
    ) -> OpenCodeTheme? {
        guard let themesData = defaults.data(forKey: importedThemesKey),
              let themes = try? JSONDecoder().decode([String: Data].self, from: themesData),
              let themeData = themes[id]
        else {
            return nil
        }
        return try? JSONDecoder().decode(OpenCodeTheme.self, from: themeData)
    }

    static func persistImportedTheme(
        _ theme: OpenCodeTheme,
        defaults: UserDefaults
    ) throws {
        var saved: [String: Data] = [:]
        if let existing = defaults.data(forKey: importedThemesKey),
           let dict = try? JSONDecoder().decode([String: Data].self, from: existing) {
            saved = dict
        }
        let encoded = try JSONEncoder().encode(theme)
        saved[theme.id] = encoded
        let container = try JSONEncoder().encode(saved)
        defaults.set(container, forKey: importedThemesKey)
    }

    static func saveSelectedThemeID(
        _ id: String,
        defaults: UserDefaults
    ) {
        defaults.set(id, forKey: selectedThemeIDKey)
    }

    static func clear(defaults: UserDefaults) {
        defaults.removeObject(forKey: importedThemesKey)
        defaults.removeObject(forKey: selectedThemeIDKey)
    }
}
