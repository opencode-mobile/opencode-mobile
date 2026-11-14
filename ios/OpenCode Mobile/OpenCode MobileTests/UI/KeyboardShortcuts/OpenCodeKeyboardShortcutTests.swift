import SwiftUI
import UIKit
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
        #expect(OpenCodeKeyboardShortcut.allCases.map { String(localized: $0.title) } == [
            "Toggle Sidebar",
            "Next Session",
            "Previous Session",
            "Focus Composer",
            "New Session",
            "Command Palette",
            "Choose Model",
            "Settings",
            "Search Sessions",
            "Project 1", "Project 2", "Project 3", "Project 4", "Project 5",
            "Project 6", "Project 7", "Project 8", "Project 9",
            "Allow Once",
            "Always Allow",
            "Deny",
            "Deny with a Note",
        ])
    }

    @Test func titles_areCatalogKeys() {
        #expect(OpenCodeKeyboardShortcut.allCases.map(\.title.key) == [
            "keyboard_shortcuts.toggle_sidebar",
            "keyboard_shortcuts.next_session",
            "keyboard_shortcuts.previous_session",
            "keyboard_shortcuts.focus_composer",
            "keyboard_shortcuts.new_session",
            "keyboard_shortcuts.command_palette",
            "keyboard_shortcuts.choose_model",
            "keyboard_shortcuts.settings",
            "keyboard_shortcuts.search_sessions",
        ] + Array(repeating: "keyboard_shortcuts.project", count: 9) + [
            "keyboard_shortcuts.allow_once",
            "keyboard_shortcuts.always_allow",
            "keyboard_shortcuts.deny",
            "keyboard_shortcuts.deny_with_note",
        ])
    }

    @Test func projectSwitch_namesTheProject() {
        let title = OpenCodeKeyboardShortcut.showProjectTitle("acme-invoices")
        #expect(title.key == "keyboard_shortcuts.show_project")
        #expect(String(localized: title) == "Show acme-invoices")
    }

    @Test func bindings_areUnique() {
        // Allow Once / Next / Submit share ⌘⏎ and Deny / Dismiss share ⌘⌫ as one
        // prompt chord each, so every key combination still has one owner.
        let combos = OpenCodeKeyboardShortcut.allCases.map { "\($0.key.character)-\($0.modifiers.rawValue)" }
        #expect(Set(combos).count == combos.count)
    }

    @Test func tier2Bindings_matchTheApprovedMap() {
        #expect(OpenCodeKeyboardShortcut.newSession.key == "n")
        #expect(OpenCodeKeyboardShortcut.newSession.modifiers == .command)
        #expect(OpenCodeKeyboardShortcut.commandPalette.key == "k")
        #expect(OpenCodeKeyboardShortcut.commandPalette.modifiers == .command)
        #expect(OpenCodeKeyboardShortcut.modelPicker.key == "m")
        #expect(OpenCodeKeyboardShortcut.modelPicker.modifiers == [.command, .shift])
        #expect(OpenCodeKeyboardShortcut.settings.key == ",")
        #expect(OpenCodeKeyboardShortcut.settings.modifiers == .command)
        #expect(OpenCodeKeyboardShortcut.searchSessions.key == "f")
        #expect(OpenCodeKeyboardShortcut.searchSessions.modifiers == .command)
        for number in 1...9 {
            #expect(OpenCodeKeyboardShortcut.switchProject(number).key.character == Character(String(number)))
            #expect(OpenCodeKeyboardShortcut.switchProject(number).modifiers == .command)
        }
        #expect(OpenCodeKeyboardShortcut.promptPrimary.key == .return)
        #expect(OpenCodeKeyboardShortcut.promptPrimary.modifiers == .command)
        #expect(OpenCodeKeyboardShortcut.promptAlwaysAllow.key == .return)
        #expect(OpenCodeKeyboardShortcut.promptAlwaysAllow.modifiers == [.option, .command])
        #expect(OpenCodeKeyboardShortcut.promptReject.key == .delete)
        #expect(OpenCodeKeyboardShortcut.promptReject.modifiers == .command)
        #expect(OpenCodeKeyboardShortcut.promptRejectWithNote.key == .delete)
        #expect(OpenCodeKeyboardShortcut.promptRejectWithNote.modifiers == [.option, .command])
        #expect(OpenCodeKeyboardShortcut.promptRejectWithNote.isPromptChord)
    }

    @Test func matching_needsExactModifiers() {
        #expect(OpenCodeKeyboardShortcut.matching(key: .return, modifiers: .command) == [.promptPrimary])
        #expect(OpenCodeKeyboardShortcut.matching(key: .return, modifiers: [.option, .command]) == [.promptAlwaysAllow])
        #expect(OpenCodeKeyboardShortcut.matching(key: .return, modifiers: []).isEmpty)
        #expect(OpenCodeKeyboardShortcut.matching(key: .return, modifiers: [.shift, .command]).isEmpty)
        #expect(OpenCodeKeyboardShortcut.matching(key: "m", modifiers: .command).isEmpty)
        #expect(OpenCodeKeyboardShortcut.matching(key: "m", modifiers: [.command, .shift]) == [.modelPicker])
        #expect(OpenCodeKeyboardShortcut.matching(key: "n", modifiers: [.command, .shift]).isEmpty)
        #expect(OpenCodeKeyboardShortcut.matching(key: "3", modifiers: .command) == [.switchProject(3)])
        #expect(OpenCodeKeyboardShortcut.matching(key: "3", modifiers: []).isEmpty)
        #expect(OpenCodeKeyboardShortcut.matching(key: .delete, modifiers: .command) == [.promptReject])
        #expect(OpenCodeKeyboardShortcut.matching(key: .delete, modifiers: [.option, .command]) == [.promptRejectWithNote])
        #expect(OpenCodeKeyboardShortcut.matching(key: .delete, modifiers: []).isEmpty)
        // The #358 chords are unchanged.
        #expect(OpenCodeKeyboardShortcut.matching(key: "s", modifiers: [.control, .command]) == [.toggleSidebar])
        #expect(OpenCodeKeyboardShortcut.matching(key: "l", modifiers: .command) == [.focusComposer])
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
    private final class ComposerInput: UITextView, OpenCodeComposerTextInput {
        var textInputRole: OpenCodeTextInputFocus { .composer }
    }

    @Test func composerTextView_isComposer() {
        #expect(OpenCodeTextInputFocusMonitor.classify(ComposerInput()) == .composer)
        let prompt = PlaceholderTextView()
        prompt.textInputRole = .composer
        #expect(OpenCodeTextInputFocusMonitor.classify(prompt) == .composer)
    }

    @Test func customAnswerField_isPromptAnswer() {
        let field = PlaceholderTextView()
        field.textInputRole = .promptAnswer
        #expect(OpenCodeTextInputFocusMonitor.classify(field) == .promptAnswer)
    }

    @Test func otherMultiLineFields_areOtherTextFields() {
        // Feedback and other multi-line `OpenCodeTextInput`s share the view class; the
        // role, not the class or its Esc handler, decides.
        let feedback = PlaceholderTextView()
        #expect(OpenCodeTextInputFocusMonitor.classify(feedback) == .otherTextField)
        feedback.onEscape = {}
        #expect(OpenCodeTextInputFocusMonitor.classify(feedback) == .otherTextField)
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

@Suite struct OpenCodeShortcutGatingTests {
    private let general: [OpenCodeKeyboardShortcut] = [
        .newSession, .commandPalette, .modelPicker, .settings, .searchSessions, .switchProject(1), .focusComposer,
    ]

    private func allows(_ shortcut: OpenCodeKeyboardShortcut, _ focus: OpenCodeTextInputFocus, overlay: Bool = false) -> Bool {
        OpenCodeKeyboardShortcutPolicy.allows(shortcut, textInputFocus: focus, overlayPresented: overlay)
    }

    @Test func generalShortcuts_fireWithNothingFocusedOrInTheComposer() {
        for shortcut in general {
            #expect(allows(shortcut, .none))
            #expect(allows(shortcut, .composer))
        }
    }

    @Test func generalShortcuts_stayOutOfOtherTextFields() {
        for shortcut in general {
            #expect(!allows(shortcut, .otherTextField))
            #expect(!allows(shortcut, .promptAnswer))
        }
    }

    @Test func nothing_firesBehindASheetDialogOrMenu() {
        for shortcut in OpenCodeKeyboardShortcut.allCases {
            for focus in [OpenCodeTextInputFocus.none, .composer, .promptAnswer, .otherTextField] {
                #expect(!allows(shortcut, focus, overlay: true))
            }
        }
    }

    @Test func promptChords_fireWithNothingFocused() {
        for chord in [OpenCodeKeyboardShortcut.promptPrimary, .promptAlwaysAllow, .promptReject, .promptRejectWithNote] {
            #expect(allows(chord, .none))
            #expect(!allows(chord, .otherTextField))
        }
    }

    @Test func customAnswerField_keepsCommandDeleteButSubmitsOnCommandReturn() {
        #expect(allows(.promptPrimary, .promptAnswer))
        #expect(!allows(.promptReject, .promptAnswer))
        #expect(!allows(.promptAlwaysAllow, .promptAnswer))
        // The deny note field is a prompt answer too: ⌥⌘⌫ and ⌘⌫ stay with the text.
        #expect(!allows(.promptRejectWithNote, .promptAnswer))
    }
}

@Suite struct OpenCodeKeyRepeatGuardTests {
    private let start = Date(timeIntervalSince1970: 100)

    @Test func firstPress_fires() {
        var guardState = OpenCodeKeyRepeatGuard<OpenCodeKeyboardShortcut>()
        let firstPress = guardState.attempt(.promptPrimary, now: start)
        #expect(firstPress)
    }

    @Test func heldKeyRepeats_doNotFire() {
        var guardState = OpenCodeKeyRepeatGuard<OpenCodeKeyboardShortcut>()
        _ = guardState.attempt(.promptPrimary, now: start)
        let firstRepeat = guardState.attempt(.promptPrimary, now: start.addingTimeInterval(0.5))
        #expect(!firstRepeat)
        // Each repeat restarts the pause, so a long hold never fires again.
        let secondRepeat = guardState.attempt(.promptPrimary, now: start.addingTimeInterval(0.58))
        #expect(!secondRepeat)
        let laterRepeat = guardState.attempt(.promptPrimary, now: start.addingTimeInterval(1.1))
        #expect(!laterRepeat)
    }

    @Test func aNewPressRightAfterLettingGo_fires() {
        var guardState = OpenCodeKeyRepeatGuard<OpenCodeKeyboardShortcut>()
        let firstPress = guardState.attempt(.promptPrimary, now: start)
        #expect(firstPress)
        guardState.keyReleased()
        // ⌘⏎ on a permission, then again at once on the queued one.
        let pressAfterRelease = guardState.attempt(.promptPrimary, now: start.addingTimeInterval(0.15))
        #expect(pressAfterRelease)
    }

    @Test func withoutAKeyUp_aPauseCountsAsLettingGo() {
        var guardState = OpenCodeKeyRepeatGuard<OpenCodeKeyboardShortcut>()
        _ = guardState.attempt(.promptPrimary, now: start)
        let pressAfterPause = guardState.attempt(.promptPrimary, now: start.addingTimeInterval(0.6))
        #expect(pressAfterPause)
    }

    @Test func heldEscape_armsOnceAndNeverInterrupts() {
        var guardState = OpenCodeKeyRepeatGuard<UIKeyboardHIDUsage>()
        let firstEscape = guardState.attempt(.keyboardEscape, now: start)
        #expect(firstEscape)
        let firstRepeat = guardState.attempt(.keyboardEscape, now: start.addingTimeInterval(0.45))
        #expect(!firstRepeat)
        let laterRepeat = guardState.attempt(.keyboardEscape, now: start.addingTimeInterval(0.53))
        #expect(!laterRepeat)
        guardState.keyReleased()
        // Esc again on purpose, inside the interrupt window.
        let secondEscape = guardState.attempt(.keyboardEscape, now: start.addingTimeInterval(0.8))
        #expect(secondEscape)
    }

    @Test func otherChords_areNotHeldByThisOne() {
        var guardState = OpenCodeKeyRepeatGuard<OpenCodeKeyboardShortcut>()
        _ = guardState.attempt(.promptPrimary, now: start)
        let otherChord = guardState.attempt(.promptReject, now: start.addingTimeInterval(0.1))
        #expect(otherChord)
    }
}
