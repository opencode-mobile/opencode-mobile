import Testing
@testable import OpenCode_Mobile

@Suite struct HomeSplitSelectionTests {
    @Test func wasRemoved_whenListedSessionDisappears() {
        #expect(HomeSplitSelection.wasRemoved(selectedID: "a", previous: listing(["a": 2, "b": 1]), current: listing(["b": 1])))
    }

    @Test func notRemoved_whileStillListed() {
        #expect(!HomeSplitSelection.wasRemoved(selectedID: "a", previous: listing(["a": 1]), current: listing(["a": 1, "c": 2])))
    }

    @Test func notRemoved_whenNeverListed() {
        // A fresh fork or child session opened before the list includes it.
        #expect(!HomeSplitSelection.wasRemoved(selectedID: "fork", previous: listing(["a": 1]), current: listing(["a": 1])))
    }

    @Test func notRemoved_withoutPreviousLoadedList() {
        #expect(!HomeSplitSelection.wasRemoved(selectedID: "a", previous: nil, current: listing([:])))
    }

    @Test func notRemoved_forOptimisticPlaceholder() {
        let placeholder = OptimisticSessionCreate.placeholderSessionID
        #expect(!HomeSplitSelection.wasRemoved(selectedID: placeholder, previous: listing([placeholder: 1]), current: listing([:])))
    }

    @Test func notRemoved_whenItOnlyFellOutOfAWindowWithMore() {
        // Newer sessions pushed "old" past the window's oldest row; the server still has it.
        let previous = listing(["old": 10, "b": 20], hasMore: true)
        let current = listing(["b": 20, "new": 30], hasMore: true)

        #expect(!HomeSplitSelection.wasRemoved(selectedID: "old", previous: previous, current: current))
    }

    @Test func removed_whenMissingInsideAWindowWithMore() {
        // Newer than the window's oldest row, so the window would still list it.
        let previous = listing(["mid": 25, "b": 20], hasMore: true)
        let current = listing(["b": 20, "new": 30], hasMore: true)

        #expect(HomeSplitSelection.wasRemoved(selectedID: "mid", previous: previous, current: current))
    }

    @Test func removed_whenMissingFromACompleteWindow() {
        let previous = listing(["old": 10, "b": 20], hasMore: false)
        let current = listing(["b": 20, "new": 30], hasMore: false)

        #expect(HomeSplitSelection.wasRemoved(selectedID: "old", previous: previous, current: current))
    }

    @Test func listingReadsEachSectionsWindow() {
        let section = ProjectSectionItem(
            id: "/repo",
            worktree: "/repo",
            displayName: "repo",
            pathSubtitle: "/repo",
            letter: "R",
            letterAccent: .violet,
            allSessions: [SessionListItem(id: "a", title: "A", worktree: "/repo", updatedAtEpochMs: 5)],
            loadError: nil,
            hasMoreSessions: true,
            oldestWindowUpdatedAtEpochMs: 5
        )

        let loaded = HomeLoadedSessions(sections: [section])

        #expect(loaded.sessions == ["a": HomeLoadedSessions.Listed(worktree: "/repo", updatedAtEpochMs: 5)])
        #expect(loaded.windows == ["/repo": HomeLoadedSessions.Window(hasMore: true, oldestUpdatedAtEpochMs: 5)])
    }

    private func listing(_ updatedAt: [String: Int64], hasMore: Bool = false) -> HomeLoadedSessions {
        HomeLoadedSessions(
            sessions: updatedAt.mapValues { HomeLoadedSessions.Listed(worktree: "/repo", updatedAtEpochMs: $0) },
            windows: ["/repo": HomeLoadedSessions.Window(hasMore: hasMore, oldestUpdatedAtEpochMs: updatedAt.values.min())]
        )
    }
}
