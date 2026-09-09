import Foundation
import Testing
@testable import OpenCode_Mobile

struct ThemeSupportTests {
    @Test func validate_rejectsMissingPaletteSeeds() {
        let theme = sampleTheme(id: "broken", ink: "")
        #expect(throws: ThemeError.self) {
            try ThemeSupport.validate(theme)
        }
    }

    @Test func validate_acceptsCompletePalette() throws {
        try ThemeSupport.validate(sampleTheme(id: "valid"))
    }
}

struct ThemePersistenceTests {
    @Test func clear_removesSelectedThemeAndSyntaxIDs() {
        let defaults = isolatedDefaults()
        ThemePersistence.saveSelectedThemeID("kiln", defaults: defaults)
        ThemePersistence.saveSelectedSyntaxID("nocturne", defaults: defaults)

        ThemePersistence.clear(defaults: defaults)

        #expect(defaults.string(forKey: ThemePersistence.selectedThemeIDKey) == nil)
        #expect(defaults.string(forKey: ThemePersistence.selectedSyntaxIDKey) == nil)
        #expect(defaults.data(forKey: ThemePersistence.importedThemesKey) == nil)
    }

    private func isolatedDefaults() -> UserDefaults {
        let suite = "ThemePersistenceTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }
}

private func sampleTheme(id: String, name: String = "Sample", ink: String = "#222222") -> OpenCodeTheme {
    let palette = PaletteSeeds(
        neutral: "#111111",
        ink: ink,
        primary: "#333333",
        success: "#444444",
        warning: "#555555",
        error: "#666666",
        info: "#777777",
        interactive: nil,
        diffAdd: nil,
        diffDelete: nil
    )
    let variant = ThemeVariant(palette: palette, overrides: nil)
    return OpenCodeTheme(
        schema: nil,
        name: name,
        id: id,
        light: variant,
        dark: variant
    )
}
