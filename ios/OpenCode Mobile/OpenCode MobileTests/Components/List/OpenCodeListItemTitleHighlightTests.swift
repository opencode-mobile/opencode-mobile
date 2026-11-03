import SwiftUI
import Testing
@testable import OpenCode_Mobile

@MainActor
@Suite struct OpenCodeListItemTitleHighlightTests {
    @Test func highlight_coversOnlyTheRange() throws {
        let title = "Search sessions on Home"
        let range = try #require(title.range(of: "sessions"))
        let attributed = try #require(OpenCodeListItem<EmptyView>.highlightedTitle(title, range: range))

        let highlighted = attributed.runs.filter { $0.backgroundColor != nil }
        #expect(highlighted.count == 1)
        let run = try #require(highlighted.first)
        #expect(String(attributed[run.range].characters) == "sessions")
        #expect(String(attributed.characters) == title)
    }

    @Test func emptyOrForeignRange_drawsThePlainTitle() {
        let title = "Home"
        #expect(OpenCodeListItem<EmptyView>.highlightedTitle(title, range: title.startIndex ..< title.startIndex) == nil)
        let longer = "Home screen search"
        let outside = longer.range(of: "search")!
        #expect(OpenCodeListItem<EmptyView>.highlightedTitle(title, range: outside) == nil)
    }
}
