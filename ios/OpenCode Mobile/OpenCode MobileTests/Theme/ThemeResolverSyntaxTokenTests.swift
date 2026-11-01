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
    }

    @Test func resolverDefaultsTheFunctionNumberAndAttributeTokens() {
        let light = ThemeResolver.resolve(variant: testVariant(), isDark: false)
        let dark = ThemeResolver.resolve(variant: testVariant(), isDark: true)

        #expect(light["syntax-function"] == "#034CFF")
        #expect(dark["syntax-function"] == "#6D97FF") // interactive mixed 42% toward white
        #expect(light["syntax-number"] == light["syntax-constant"])
        #expect(dark["syntax-number"] == dark["syntax-constant"])
        #expect(light["syntax-attribute"] == "#FFDC17")
        #expect(dark["syntax-attribute"] == dark["syntax-type"])
        // The code surface comes only from a syntax preset; the retired primitive is gone.
        for tokens in [light, dark] {
            #expect(tokens.tokens["syntax-background"] == nil)
            #expect(tokens.tokens["syntax-foreground"] == nil)
            #expect(tokens.tokens["syntax-primitive"] == nil)
        }
    }

    @Test func overlaySyntax_carriesThePresetCodeSurface() {
        let base = ThemeResolver.resolve(variant: testVariant(), isDark: true)
        let overlaid = ThemeResolver.overlaySyntax(
            base,
            syntax: ["syntax-background": "#0D1117", "syntax-foreground": "#E6EDF3"]
        )

        #expect(overlaid.tokens["syntax-background"] == "#0D1117")
        #expect(overlaid.tokens["syntax-foreground"] == "#E6EDF3")
    }

    @Test func resolverProvidesDesktopAlignedInlineCodeBackground() {
        let light = ThemeResolver.resolve(variant: testVariant(), isDark: false)
        let dark = ThemeResolver.resolve(variant: testVariant(), isDark: true)

        #expect(light["markdown-code-bg"] == "#00000014")
        #expect(dark["markdown-code-bg"] == "#FFFFFF14")
        #expect(light["markdown-code-text"] == light["text-strong"])
        #expect(dark["markdown-code-text"] == dark["text-strong"])
    }

    @Test func resolverUsesNeutralChromeForLinksAndQuotes() {
        let light = ThemeResolver.resolve(variant: testVariant(), isDark: false)
        let dark = ThemeResolver.resolve(variant: testVariant(), isDark: true)

        #expect(light["markdown-link-text"] == light["text-strong"])
        #expect(dark["markdown-link-text"] == dark["text-strong"])
        #expect(light["markdown-link-underline"] == light["text-strong"])
        #expect(dark["markdown-link-underline"] == dark["text-strong"])
        #expect(light["markdown-link-text"] != light["interactive"])
        #expect(dark["markdown-link-text"] != dark["interactive"])
        #expect(light["markdown-quote-bar"] == light["border-weak-base"])
        #expect(dark["markdown-quote-bar"] == dark["border-weak-base"])
        #expect(light["markdown-quote-bar"] != light["interactive"])
        #expect(dark["markdown-quote-bar"] != dark["interactive"])
    }

    @Test func resolverKeepsPresetOverridesForPathASyntaxTokens() {
        let resolved = ThemeResolver.resolve(
            variant: testVariant(
                overrides: [
                    "syntax-variable": "#111111",
                    "syntax-operator": "#222222",
                    "syntax-punctuation": "#333333",
                ]
            ),
            isDark: false
        )

        #expect(resolved["syntax-variable"] == "#111111")
        #expect(resolved["syntax-operator"] == "#222222")
        #expect(resolved["syntax-punctuation"] == "#333333")
    }

    @Test func overlaySyntax_replacesSyntaxKeysAndIgnoresOthers() {
        let base = ThemeResolver.resolve(variant: testVariant(), isDark: false)
        let overlaid = ThemeResolver.overlaySyntax(
            base,
            syntax: [
                "syntax-keyword": "#AABBCC",
                "background-base": "#000000",
                "syntax-comment": "112233",
            ]
        )

        #expect(overlaid["syntax-keyword"] == "#AABBCC")
        #expect(overlaid["syntax-comment"] == "#112233")
        #expect(overlaid["background-base"] == base["background-base"])
        #expect(overlaid["text-strong"] == base["text-strong"])
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
