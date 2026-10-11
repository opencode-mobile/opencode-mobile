import SwiftUI
import Testing
@testable import OpenCode_Mobile

@Suite struct OpenCodeKeyboardShortcutTests {
    @Test func bindings_matchTheSharedSpec() {
        #expect(OpenCodeKeyboardShortcut.toggleSidebar.key == "s")
        #expect(OpenCodeKeyboardShortcut.toggleSidebar.modifiers == [.control, .command])
        #expect(OpenCodeKeyboardShortcut.nextSession.key == .tab)
        #expect(OpenCodeKeyboardShortcut.nextSession.modifiers == .control)
        #expect(OpenCodeKeyboardShortcut.previousSession.key == .tab)
        #expect(OpenCodeKeyboardShortcut.previousSession.modifiers == [.control, .shift])
        #expect(OpenCodeKeyboardShortcut.focusComposer.key == "l")
        #expect(OpenCodeKeyboardShortcut.focusComposer.modifiers == .command)
    }

    @Test func titles_areTheOverlayLabels() {
        #expect(OpenCodeKeyboardShortcut.allCases.map(\.title) == [
            "Toggle Sidebar",
            "Next Session",
            "Previous Session",
            "Focus Composer",
        ])
    }

    @Test func bindings_areUnique() {
        let combos = OpenCodeKeyboardShortcut.allCases.map { "\($0.key.character)-\($0.modifiers.rawValue)" }
        #expect(Set(combos).count == combos.count)
    }
}

@Suite struct OpenCodeKeyboardShortcutPolicyTests {
    @Test func noTextInput_allowsShortcuts() {
        #expect(OpenCodeKeyboardShortcutPolicy.allowsShortcuts(textInputFocus: .none))
    }

    @Test func composerFocused_allowsShortcuts() {
        #expect(OpenCodeKeyboardShortcutPolicy.allowsShortcuts(textInputFocus: .composer))
    }

    @Test func otherTextFieldFocused_blocksShortcuts() {
        #expect(!OpenCodeKeyboardShortcutPolicy.allowsShortcuts(textInputFocus: .otherTextField))
    }

    @Test func overlay_blocksShortcutsWhateverHasFocus() {
        for focus in [OpenCodeTextInputFocus.none, .composer, .otherTextField] {
            #expect(!OpenCodeKeyboardShortcutPolicy.allowsShortcuts(textInputFocus: focus, overlayPresented: true))
        }
        #expect(OpenCodeKeyboardShortcutPolicy.allowsShortcuts(textInputFocus: .composer, overlayPresented: false))
        #expect(!OpenCodeKeyboardShortcutPolicy.allowsShortcuts(textInputFocus: .otherTextField, overlayPresented: false))
    }
}

@Suite struct OpenCodeSessionNavigationTests {
    private let ids = ["a", "b", "c"]

    private func adjacent(_ selected: String?, _ direction: OpenCodeSessionNavigationDirection) -> String? {
        OpenCodeSessionNavigation.adjacentSessionID(orderedIDs: ids, selectedID: selected, direction: direction)
    }

    @Test func next_movesDown() {
        #expect(adjacent("a", .next) == "b")
        #expect(adjacent("b", .next) == "c")
    }

    @Test func previous_movesUp() {
        #expect(adjacent("c", .previous) == "b")
        #expect(adjacent("b", .previous) == "a")
    }

    @Test func atEnds_doesNotWrap() {
        #expect(adjacent("c", .next) == nil)
        #expect(adjacent("a", .previous) == nil)
    }

    @Test func nothingSelected_nextPicksFirstAndPreviousPicksLast() {
        #expect(adjacent(nil, .next) == "a")
        #expect(adjacent(nil, .previous) == "c")
    }

    @Test func selectionNotRendered_actsLikeNothingSelected() {
        #expect(adjacent("hidden", .next) == "a")
        #expect(adjacent("hidden", .previous) == "c")
    }

    @Test func emptyList_selectsNothing() {
        #expect(OpenCodeSessionNavigation.adjacentSessionID(orderedIDs: [], selectedID: nil, direction: .next) == nil)
        #expect(OpenCodeSessionNavigation.adjacentSessionID(orderedIDs: [], selectedID: "a", direction: .previous) == nil)
    }

    @Test func singleSession_selectedHasNoNeighbours() {
        #expect(OpenCodeSessionNavigation.adjacentSessionID(orderedIDs: ["a"], selectedID: "a", direction: .next) == nil)
        #expect(OpenCodeSessionNavigation.adjacentSessionID(orderedIDs: ["a"], selectedID: "a", direction: .previous) == nil)
    }
}

@MainActor
@Suite struct OpenCodeTextInputFocusClassificationTests {
    private final class ComposerInput: UITextView, OpenCodeComposerTextInput {}

    @Test func composerTextView_isComposer() {
        #expect(OpenCodeTextInputFocusMonitor.classify(ComposerInput()) == .composer)
        #expect(OpenCodeTextInputFocusMonitor.classify(PlaceholderTextView()) == .composer)
    }

    @Test func otherTextInputs_areOtherTextField() {
        #expect(OpenCodeTextInputFocusMonitor.classify(UITextField()) == .otherTextField)
        #expect(OpenCodeTextInputFocusMonitor.classify(UITextView()) == .otherTextField)
        #expect(OpenCodeTextInputFocusMonitor.classify(UISearchTextField()) == .otherTextField)
    }

    @Test func nonTextResponders_areNone() {
        #expect(OpenCodeTextInputFocusMonitor.classify(nil) == .none)
        #expect(OpenCodeTextInputFocusMonitor.classify(UIView()) == .none)
        #expect(OpenCodeTextInputFocusMonitor.classify(UIApplication.shared) == .none)
    }
}
