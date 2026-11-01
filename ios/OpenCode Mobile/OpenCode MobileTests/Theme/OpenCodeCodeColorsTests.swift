import Foundation
import SwiftUI
import Testing
@testable import OpenCode_Mobile

@MainActor
struct OpenCodeCodeColorsTests {
    @Test func everyRoleMapsToItsSyntaxTokenOrPlainCodeText() {
        let expected: [NativeCodeTokenRole: String?] = [
            .keyword: "syntax-keyword",
            .string: "syntax-string",
            .number: "syntax-number",
            .constant: "syntax-constant",
            .comment: "syntax-comment",
            .type: "syntax-type",
            .function: "syntax-function",
            .property: "syntax-property",
            .attribute: "syntax-attribute",
            .variable: nil,
            .operatorToken: nil,
            .punctuation: nil,
            .plain: nil,
            .tag: "syntax-keyword",
            .inserted: "diff-add",
            .deleted: "diff-delete",
            .warning: "syntax-diff-unknown",
            .critical: "syntax-critical",
        ]

        #expect(Set(expected.keys) == Set(NativeCodeTokenRole.allCases))
        for role in NativeCodeTokenRole.allCases {
            #expect(OpenCodeCodeColors.token(for: role) == expected[role] ?? nil, "role \(role)")
        }
    }

    @Test func plainRolesUseThePresetForegroundWhenItHasOne() {
        let tokens = presetTokens(syntax: "paper", isDark: true)
        let foreground = tokens["syntax-foreground"]

        #expect(foreground == "#E6EDF3")
        for role in [NativeCodeTokenRole.variable, .operatorToken, .punctuation, .plain] {
            #expect(OpenCodeCodeColors.hex(for: role, in: tokens) == foreground, "role \(role)")
        }
        #expect(OpenCodeCodeColors.hex(for: .function, in: tokens) == tokens["syntax-function"])
        #expect(OpenCodeCodeColors.hex(for: .type, in: tokens) == tokens["syntax-type"])
    }

    @Test func plainRolesUseTheThemeTextWithoutAPresetForeground() {
        let tokens = presetTokens(syntax: SyntaxHighlightPreset.matchID, isDark: false)

        #expect(tokens["syntax-foreground"] == nil)
        for role in [NativeCodeTokenRole.variable, .operatorToken, .punctuation, .plain] {
            #expect(OpenCodeCodeColors.hex(for: role, in: tokens) == tokens["text-strong"], "role \(role)")
        }
        #expect(OpenCodeCodeColors.hex(for: .number, in: tokens) == tokens["syntax-number"])
    }

    @Test func codeSurfaceUsesThePresetBackgroundAndFallsBackWithoutOne() {
        let paperLight = presetTokens(syntax: "paper", isDark: false)
        #expect(OpenCodeCodeColors.backgroundHex(in: paperLight) == "#FFFFFF")
        #expect(OpenCodeCodeColors.textHex(in: paperLight) == "#1F2328")

        let nocturneDark = presetTokens(syntax: "nocturne", isDark: true)
        #expect(OpenCodeCodeColors.backgroundHex(in: nocturneDark) != nil)
        #expect(OpenCodeCodeColors.textHex(in: nocturneDark) == nocturneDark["syntax-foreground"])

        // Match theme and Neon light keep the UI theme's code surface.
        for (syntax, isDark) in [(SyntaxHighlightPreset.matchID, false), (SyntaxHighlightPreset.matchID, true), ("neon", false)] {
            let tokens = presetTokens(syntax: syntax, isDark: isDark)
            #expect(OpenCodeCodeColors.backgroundHex(in: tokens) == nil, "\(syntax) dark: \(isDark)")
            #expect(OpenCodeCodeColors.textHex(in: tokens) == tokens["text-strong"], "\(syntax) dark: \(isDark)")
        }
        #expect(OpenCodeCodeColors.backgroundHex(in: presetTokens(syntax: "neon", isDark: true)) != nil)
    }

    @Test func bundledSyntaxPresetsCarryEveryColoredRoleAndTheirEditorSurface() {
        let manager = ThemeManager(defaults: isolatedDefaults())
        let presets = manager.availableSyntaxPresets().filter { !$0.isMatch }

        #expect(Set(presets.map(\.id)) == ["nocturne", "citrus", "paper", "neon"])
        for preset in presets {
            for (mode, tokens) in [("light", preset.light), ("dark", preset.dark)] {
                for role in NativeCodeTokenRole.allCases {
                    guard let token = OpenCodeCodeColors.token(for: role), token.hasPrefix("syntax-"),
                          !["syntax-diff-unknown", "syntax-critical"].contains(token)
                    else { continue }
                    #expect(tokens[token] != nil, "\(preset.id) \(mode) lacks \(token)")
                }
                let hasSurface = !(preset.id == "neon" && mode == "light")
                #expect((tokens["syntax-background"] != nil) == hasSurface, "\(preset.id) \(mode) background")
                #expect((tokens["syntax-foreground"] != nil) == hasSurface, "\(preset.id) \(mode) foreground")
            }
        }
    }

    @Test func bundledThemesGiveTheirOwnColorsForTheNewRoles() {
        let manager = ThemeManager(defaults: isolatedDefaults())
        for preset in manager.availablePresets() {
            manager.applyPreset(id: preset.id)
            for (mode, variant) in [("light", manager.currentTheme.light), ("dark", manager.currentTheme.dark)] {
                for token in ["syntax-function", "syntax-number", "syntax-attribute"] {
                    #expect(variant.overrides?[token] != nil, "\(preset.id) \(mode) lacks \(token)")
                }
            }
        }
    }

    private func presetTokens(syntax: String, isDark: Bool) -> [String: String] {
        let manager = ThemeManager(defaults: isolatedDefaults())
        manager.applyPreset(id: "porcelain")
        manager.applySyntaxPreset(id: syntax)
        return manager.tokens(for: isDark ? .dark : .light).tokens
    }

    private func isolatedDefaults() -> UserDefaults {
        let suite = "OpenCodeCodeColorsTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }
}
