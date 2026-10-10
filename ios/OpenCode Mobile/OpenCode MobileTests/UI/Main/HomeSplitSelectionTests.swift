import Testing
@testable import OpenCode_Mobile

@Suite struct HomeSplitSelectionTests {
    @Test func wasRemoved_whenListedSessionDisappears() {
        #expect(HomeSplitSelection.wasRemoved(selectedID: "a", previousIDs: ["a", "b"], currentIDs: ["b"]))
    }

    @Test func notRemoved_whileStillListed() {
        #expect(!HomeSplitSelection.wasRemoved(selectedID: "a", previousIDs: ["a"], currentIDs: ["a", "c"]))
    }

    @Test func notRemoved_whenNeverListed() {
        // A fresh fork or child session opened before the list includes it.
        #expect(!HomeSplitSelection.wasRemoved(selectedID: "fork", previousIDs: ["a"], currentIDs: ["a"]))
    }

    @Test func notRemoved_withoutPreviousLoadedList() {
        #expect(!HomeSplitSelection.wasRemoved(selectedID: "a", previousIDs: nil, currentIDs: []))
    }

    @Test func notRemoved_forOptimisticPlaceholder() {
        let placeholder = OptimisticSessionCreate.placeholderSessionID
        #expect(!HomeSplitSelection.wasRemoved(selectedID: placeholder, previousIDs: [placeholder], currentIDs: []))
    }
}
