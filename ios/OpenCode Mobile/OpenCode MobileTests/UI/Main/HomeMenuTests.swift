import Testing
@testable import OpenCode_Mobile

struct HomeMenuTests {
    @Test func settingsAloneHasNoDivider() {
        let entries = HomeMenu.entries(features: [], openSettings: {})
        #expect(entries.map(\.id) == [HomeMenu.settingsID])
        #expect(entries.first?.title == "common.settings")
        #expect(entries.first?.icon == .settings)
        #expect(entries.first?.dividerAbove == false)
    }

    @Test func featureEntriesKeepTheirOrderAndSettingsClosesAfterADivider() {
        let entries = HomeMenu.entries(
            features: [feature("search"), feature("archived"), feature("worktrees")],
            openSettings: {}
        )
        #expect(entries.map(\.id) == ["search", "archived", "worktrees", HomeMenu.settingsID])
        #expect(entries.map(\.dividerAbove) == [false, false, false, true])
    }

    @Test func settingsEntryOpensSettings() {
        var opened = 0
        let entries = HomeMenu.entries(features: [], openSettings: { opened += 1 })
        entries.last?.action()
        #expect(opened == 1)
    }

    private func feature(_ id: String) -> OpenCodeOverflowMenuEntry {
        OpenCodeOverflowMenuEntry(id: id, title: .verbatim(id), icon: .search, action: {})
    }
}
