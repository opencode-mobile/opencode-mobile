import Foundation
import Testing
@testable import OpenCode_Mobile

struct ThemeSupportTests {
    @Test func mergePresets_appendsImportedThemesNotInManifest() {
        let bundled = [
            ThemePreset(id: "oc-2", name: "OC-2"),
            ThemePreset(id: "dracula", name: "Dracula"),
        ]
        let imported = [
            ThemePreset(id: "my-theme", name: "My Theme"),
            ThemePreset(id: "dracula", name: "Duplicate Dracula"),
        ]

        let merged = ThemeSupport.mergePresets(bundled: bundled, imported: imported)

        #expect(merged.map(\.id) == ["oc-2", "dracula", "my-theme"])
    }

    @Test func assertNoPresetIdCollision_rejectsBundledIDs() {
        #expect(throws: ThemeError.self) {
            try ThemeSupport.assertNoPresetIdCollision(
                themeID: "dracula",
                bundledPresetIDs: ["oc-2", "dracula"]
            )
        }
    }

    @Test func assertImportSize_rejectsOversizedPayload() {
        #expect(throws: ThemeError.self) {
            try ThemeSupport.assertImportSize(ThemeSupport.maxImportBytes + 1)
        }
    }

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
    @Test func persistAndRestoreImportedTheme() throws {
        let defaults = isolatedDefaults()
        let theme = sampleTheme(id: "custom-import", name: "Custom Import")

        try ThemePersistence.persistImportedTheme(theme, defaults: defaults)
        ThemePersistence.saveSelectedThemeID(theme.id, defaults: defaults)

        let restored = ThemePersistence.loadImportedTheme(id: theme.id, defaults: defaults)
        #expect(restored?.id == theme.id)
        #expect(restored?.name == theme.name)
    }

    @Test func importedPresets_listsPersistedThemes() throws {
        let defaults = isolatedDefaults()
        let theme = sampleTheme(id: "listed-import", name: "Listed Import")
        try ThemePersistence.persistImportedTheme(theme, defaults: defaults)

        let presets = ThemePersistence.importedPresets(defaults: defaults)
        #expect(presets.map(\.id) == ["listed-import"])
    }

    @Test func clear_removesPersistedThemes() throws {
        let defaults = isolatedDefaults()
        let theme = sampleTheme(id: "clear-me", name: "Clear Me")
        try ThemePersistence.persistImportedTheme(theme, defaults: defaults)
        ThemePersistence.saveSelectedThemeID(theme.id, defaults: defaults)

        ThemePersistence.clear(defaults: defaults)

        #expect(ThemePersistence.loadImportedTheme(id: theme.id, defaults: defaults) == nil)
        #expect(defaults.string(forKey: ThemePersistence.selectedThemeIDKey) == nil)
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
