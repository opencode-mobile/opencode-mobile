import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SettingsTextTests {
    @Test func serversSubtitle_noProfiles() {
        #expect(SettingsView.serversSubtitle(count: 0, activeName: nil) == "settings.servers_summary.none")
        #expect(SettingsView.serversSubtitle(count: 0, activeName: nil).resolved == "No servers saved")
    }

    @Test func serversSubtitle_activeName_staysVerbatim() {
        let text = SettingsView.serversSubtitle(count: 2, activeName: "servers.title")

        #expect(text.resolved == "2 saved · servers.title active")
        #expect(SettingsView.serversSubtitle(count: 1, activeName: "servers.title").resolved == "1 saved · servers.title active")
    }

    @Test func serversSubtitle_noActive_countsSaved() {
        #expect(SettingsView.serversSubtitle(count: 1, activeName: nil).resolved == "1 saved")
        #expect(SettingsView.serversSubtitle(count: 3, activeName: nil).resolved == "3 saved")
    }

    @Test func recentWindowHours_isSanitizedAndPlural() {
        #expect(SettingsView.recentWindowHoursLabel(4).resolved == "4 hours")
        // 1 is not an allowed window and sanitizes to the nearest one.
        #expect(SettingsView.recentWindowHoursLabel(1).resolved == "2 hours")
    }

    @Test func presetText_keysMatchThemeAndBuiltInBlurbs() {
        #expect(SyntaxHighlightPreset.match.displayName == "Match theme")
        #expect(SyntaxHighlightPreset.match.displayBlurb == "Use the UI theme’s own tokens")
        // Built-in descriptions come from the catalog, not the preset file.
        #expect(SyntaxHighlightPreset(id: "nocturne", name: "Nocturne", blurb: "file text").displayBlurb == "One Dark family")
        #expect(ThemePreset(id: "linen", name: "Linen", blurb: "file text").displayBlurb == "Warm paper / espresso")
        // A preset the app does not know keeps its file's name and description.
        let custom = SyntaxHighlightPreset(id: "custom", name: "Custom", blurb: "Mine")
        #expect(custom.displayName == "Custom")
        #expect(custom.displayBlurb == "Mine")
        #expect(ThemePreset(id: "custom", name: "Custom", blurb: "Mine").displayBlurb == "Mine")
    }

    @Test func diagnosticsLabels() {
        #expect(DiagnosticLogsView.lastMinutesLabel(15).resolved == "Last 15 minutes")
        #expect(DiagnosticLogsView.lastMinutesLabel(1).resolved == "Last 1 minute")
        #expect(DiagnosticLogsView.rangeLabel(oldest: "a", newest: "a").resolved == "a")
        #expect(DiagnosticLogsView.rangeLabel(oldest: "a", newest: "b").resolved == "a → b")
    }

    @Test func timelineAndRowStyles_haveCatalogText() {
        #expect(SessionChatTimelineStyle.stepRail.settingsTitle.resolved == "Step Rail")
        #expect(HomeSessionRowStyle.compact.settingsSubtitle.resolved == "Two lines: title and time, then project / worktree")
    }
}
