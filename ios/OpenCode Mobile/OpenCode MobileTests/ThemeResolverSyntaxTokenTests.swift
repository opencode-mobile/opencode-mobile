import Testing
@testable import OpenCode_Mobile

struct ThemeResolverSyntaxTokenTests {
    @Test func resolverProvidesPathASyntaxFallbacksWhenOverridesAreMissing() {
        let light = ThemeResolver.resolve(variant: testVariant(), isDark: false)
        let dark = ThemeResolver.resolve(variant: testVariant(), isDark: true)

        #expect(light["syntax-variable"] == "#D1383D")
        #expect(dark["syntax-variable"] == "#E06C75")
        #expect(light["syntax-operator"] == "#318795")
        #expect(dark["syntax-operator"] == "#56B6C2")
        #expect(light["syntax-punctuation"] == light["text-strong"])
        #expect(dark["syntax-punctuation"] == dark["text-strong"])
        #expect(light["syntax-object"] == "#D1383D")
        #expect(dark["syntax-object"] == "#E06C75")
    }

    @Test func resolverKeepsPresetOverridesForPathASyntaxTokens() {
        let resolved = ThemeResolver.resolve(
            variant: testVariant(
                overrides: [
                    "syntax-variable": "#111111",
                    "syntax-operator": "#222222",
                    "syntax-punctuation": "#333333",
                    "syntax-object": "#444444",
                ]
            ),
            isDark: false
        )

        #expect(resolved["syntax-variable"] == "#111111")
        #expect(resolved["syntax-operator"] == "#222222")
        #expect(resolved["syntax-punctuation"] == "#333333")
        #expect(resolved["syntax-object"] == "#444444")
    }
}

private func testVariant(overrides: [String: String]? = nil) -> ThemeVariant {
    ThemeVariant(
        palette: PaletteSeeds(
            neutral: "#F7F7F7",
            ink: "#171311",
            primary: "#FF7A1A",
            success: "#12C905",
            warning: "#FFDC17",
            error: "#FC533A",
            info: "#A753AE",
            interactive: "#034CFF",
            diffAdd: "#9FF29A",
            diffDelete: "#FC533A"
        ),
        overrides: overrides
    )
}
