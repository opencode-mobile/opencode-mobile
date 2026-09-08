import Foundation

enum ThemeSupport {
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
