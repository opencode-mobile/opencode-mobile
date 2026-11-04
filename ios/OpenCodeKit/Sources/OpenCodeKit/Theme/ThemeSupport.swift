import Foundation

public enum ThemeSupport {
    public static func validate(_ theme: OpenCodeTheme) throws {
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

public enum ThemePersistence {
    public static let importedThemesKey = "importedThemes"
    public static let selectedThemeIDKey = "selectedThemeID"
    public static let selectedSyntaxIDKey = "selectedSyntaxID"

    public static func saveSelectedThemeID(
        _ id: String,
        defaults: UserDefaults
    ) {
        defaults.set(id, forKey: selectedThemeIDKey)
    }

    public static func saveSelectedSyntaxID(
        _ id: String,
        defaults: UserDefaults
    ) {
        defaults.set(id, forKey: selectedSyntaxIDKey)
    }

    public static func clear(defaults: UserDefaults) {
        defaults.removeObject(forKey: importedThemesKey)
        defaults.removeObject(forKey: selectedThemeIDKey)
        defaults.removeObject(forKey: selectedSyntaxIDKey)
    }
}
