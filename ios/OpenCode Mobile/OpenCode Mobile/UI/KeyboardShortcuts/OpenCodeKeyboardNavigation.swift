import Foundation
import SwiftUI

// Pure rules for hardware-keyboard navigation (#869 Tier 1). The SwiftUI and UIKit
// wiring lives in `OpenCodeKeyboardFocus.swift` and the design-system focus modifier.

/// Arrow key that moves focus inside a focus group.
nonisolated enum OpenCodeArrowKey: Sendable {
    case up
    case down
    case left
    case right
}

/// Direction a focus group is laid out in. Arrows across the axis are left alone.
nonisolated enum OpenCodeFocusGroupAxis: Sendable {
    case vertical
    case horizontal
}

/// What an arrow key does inside a focus group.
nonisolated enum OpenCodeFocusGroupMove<ID: Hashable>: Equatable {
    /// Move focus to this member.
    case focus(ID)
    /// At the first or last member: keep focus and swallow the key.
    case stay
    /// Not an arrow for this group's axis: let the key through.
    case ignore
}

/// Roving focus inside a group (a list, a row of buttons, prompt options): one
/// member is the group's single Tab stop, arrows move between members.
nonisolated enum OpenCodeFocusGroupNavigation {
    /// The member Tab lands on: the last focused one while it is still in the
    /// group, else the first. Nil for an empty group.
    static func tabStop<ID: Hashable>(activeID: ID?, orderedIDs: [ID]) -> ID? {
        if let activeID, orderedIDs.contains(activeID) { return activeID }
        return orderedIDs.first
    }

    /// Where an arrow key moves focus from `currentID`, in reading order. No wrap-around.
    static func move<ID: Hashable>(
        from currentID: ID,
        orderedIDs: [ID],
        key: OpenCodeArrowKey,
        axis: OpenCodeFocusGroupAxis
    ) -> OpenCodeFocusGroupMove<ID> {
        let step: Int
        switch (axis, key) {
        case (.vertical, .down), (.horizontal, .right): step = 1
        case (.vertical, .up), (.horizontal, .left): step = -1
        default: return .ignore
        }
        guard let index = orderedIDs.firstIndex(of: currentID) else {
            return orderedIDs.first.map { .focus($0) } ?? .ignore
        }
        let target = index + step
        guard orderedIDs.indices.contains(target) else { return .stay }
        return .focus(orderedIDs[target])
    }
}

/// While nothing has keyboard focus, at most one control is a Tab stop, so the
/// first Tab lands on it: one a text field handed focus to when it was left, else
/// the entry control of a waiting prompt. Nil leaves every control a Tab stop.
nonisolated enum OpenCodeTabRestriction {
    static func target<Key: Hashable>(nextTabTarget: Key?, promptEntry: Key?) -> Key? {
        nextTabTarget ?? promptEntry
    }
}

/// Enabled focusable controls on screen, counted by key: several views can share a
/// key, and a replacement can appear before the view it replaces is gone. A Tab
/// target handed over to a control must be one of them, or no control would be a
/// Tab stop.
nonisolated struct OpenCodeTabStopRegistry {
    private var counts: [AnyHashable: Int] = [:]

    func contains(_ key: AnyHashable) -> Bool {
        counts[key] != nil
    }

    mutating func appeared(_ key: AnyHashable) {
        counts[key, default: 0] += 1
    }

    /// True when the last control with `key` went away.
    @discardableResult
    mutating func disappeared(_ key: AnyHashable) -> Bool {
        guard let count = counts[key] else { return false }
        if count > 1 {
            counts[key] = count - 1
            return false
        }
        counts[key] = nil
        return true
    }
}

/// Tab in a multi-line field leaves the field instead of typing a tab character.
nonisolated enum OpenCodeTabKeyPolicy {
    /// Tab and Shift+Tab leave; while an input method is composing, or with ⌘, ⌥ or
    /// ⌃ (Next Session is ⌃Tab), the key is left alone.
    static func leavesField(otherModifiers: Bool, composing: Bool) -> Bool {
        !otherModifiers && !composing
    }
}

/// Whether hardware Return may send the prompt, decided from the field's live text
/// when the Return arrives (the screen model can lag queued key insertions).
nonisolated struct OpenCodeReturnKeyRule: Equatable, Sendable {
    /// Every send condition except the draft text (model ready, nothing sending,
    /// no suggestion list open).
    var readyIgnoringText: Bool
    /// Something else is sendable without text (attachments).
    var hasOtherContent: Bool

    /// Same as a Send button enabled exactly when `enabled`.
    static func fixed(_ enabled: Bool) -> Self {
        Self(readyIgnoringText: enabled, hasOtherContent: true)
    }

    func submitEnabled(text: String) -> Bool {
        guard readyIgnoringText else { return false }
        return hasOtherContent || !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

/// The focus ring shows only for keyboard focus, like `:focus-visible`: with a
/// hardware keyboard connected and the last input a key press, not a touch.
nonisolated enum OpenCodeFocusRingPolicy {
    static func isVisible(focused: Bool, hardwareKeyboardConnected: Bool, lastInputWasKeyboard: Bool) -> Bool {
        focused && hardwareKeyboardConnected && lastInputWasKeyboard
    }

    /// After a touch, keyboard focus the touch left on a control (iPadOS moves it,
    /// even onto a control that was not touched) is cleared: it shows no ring, and
    /// a stray Return would press that control.
    static func clearsFocusAfterTouch(
        controlFocused: Bool,
        lastInputWasKeyboard: Bool,
        textInputFocused: Bool
    ) -> Bool {
        controlFocused && !lastInputWasKeyboard && !textInputFocused
    }

    /// Focus that lands this soon after a touch was caused by the touch.
    static let touchFocusWindow: TimeInterval = 0.5

    static func focusFollowsTouch(touchAt: Date?, focusAt: Date) -> Bool {
        guard let touchAt else { return false }
        let elapsed = focusAt.timeIntervalSince(touchAt)
        return elapsed >= 0 && elapsed < touchFocusWindow
    }
}

/// What a hardware-keyboard Return does in a multi-line prompt field.
nonisolated enum OpenCodeReturnKeyAction: Equatable, Sendable {
    /// Run the field's submit action (send the prompt, save a custom answer).
    case submit
    /// Swallow the key: the field's submit action is unavailable, and Return
    /// must not add a line in its place.
    case ignore
    /// Leave the key to the text system: a new line, or committing IME text.
    case passThrough
}

nonisolated enum OpenCodeReturnKeyPolicy {
    /// How long a hardware Return waits for its line. The line arrives after keys
    /// typed before it, sometimes after keys pressed after it, but never this late.
    static let pendingReturnWindow: TimeInterval = 1

    static func pendingReturnApplies(pressedAt: Date?, now: Date) -> Bool {
        guard let pressedAt else { return false }
        let elapsed = now.timeIntervalSince(pressedAt)
        return elapsed >= 0 && elapsed < pendingReturnWindow
    }

    /// Return submits, Shift+Return adds a line. While an input method is composing
    /// (marked text), Return commits the composition, so it always passes through.
    /// Other modifiers (⌘, ⌥, ⌃) are left to key commands and the text system.
    static func action(
        shift: Bool,
        otherModifiers: Bool,
        composing: Bool,
        submitEnabled: Bool
    ) -> OpenCodeReturnKeyAction {
        if composing || shift || otherModifiers { return .passThrough }
        return submitEnabled ? .submit : .ignore
    }
}

/// What one Esc press does. First match wins, see `OpenCodeEscapeResolver`.
nonisolated enum OpenCodeEscapeAction: Equatable, Sendable {
    /// Close the topmost sheet, dialog, menu, or popover.
    case closeOverlay
    /// Hand Esc to the screen that claims it (Home search: clear the query, then close).
    case screenEscape
    /// Stop editing the focused text field.
    case leaveTextInput
    /// Take keyboard focus off the focused control.
    case clearFocus
    /// First Esc toward an interrupt: show "Esc again to interrupt".
    case armInterrupt
    /// Second Esc within the window: interrupt the running turn.
    case interrupt
    case none
}

nonisolated struct OpenCodeEscapeContext: Equatable, Sendable {
    var overlayPresented = false
    /// A screen on top claims Esc for itself, focused field or not.
    var screenClaimsEscape = false
    var textInputFocused = false
    var controlFocused = false
    /// Session Chat is on screen, its session is busy, and Stop is enabled.
    var interruptAvailable = false
}

nonisolated struct OpenCodeEscapeResolution: Equatable, Sendable {
    let action: OpenCodeEscapeAction
    /// When the interrupt is armed after this press; nil when it is not.
    let armedAt: Date?
}

/// Esc priority: close the topmost overlay, else let a screen that claims Esc handle
/// it, else leave the focused text field or control, else interrupt the running turn
/// in Session Chat. Like the opencode TUI, interrupting takes two presses: the
/// first arms it, a second within `interruptWindow` interrupts. Any Esc consumed by an earlier step disarms.
nonisolated enum OpenCodeEscapeResolver {
    static let interruptWindow: TimeInterval = 5

    static func resolve(
        _ context: OpenCodeEscapeContext,
        armedAt: Date?,
        now: Date
    ) -> OpenCodeEscapeResolution {
        if context.overlayPresented { return .init(action: .closeOverlay, armedAt: nil) }
        if context.screenClaimsEscape { return .init(action: .screenEscape, armedAt: nil) }
        if context.textInputFocused { return .init(action: .leaveTextInput, armedAt: nil) }
        if context.controlFocused { return .init(action: .clearFocus, armedAt: nil) }
        guard context.interruptAvailable else { return .init(action: .none, armedAt: nil) }
        if isArmed(armedAt: armedAt, now: now, interruptAvailable: true) {
            return .init(action: .interrupt, armedAt: nil)
        }
        return .init(action: .armInterrupt, armedAt: now)
    }

    /// Whether an earlier Esc still arms the interrupt (and its hint shows).
    static func isArmed(armedAt: Date?, now: Date, interruptAvailable: Bool) -> Bool {
        guard interruptAvailable, let armedAt else { return false }
        let elapsed = now.timeIntervalSince(armedAt)
        return elapsed >= 0 && elapsed < interruptWindow
    }
}

/// "Press Tab to answer with the keyboard" under a permission or question prompt.
nonisolated enum OpenCodePromptKeyboardHint {
    static let text = "Press Tab to answer with the keyboard"

    /// Shown only with a hardware keyboard, and only until focus is inside the prompt.
    static func isVisible(hardwareKeyboardConnected: Bool, focusInsidePrompt: Bool) -> Bool {
        hardwareKeyboardConnected && !focusInsidePrompt
    }
}

/// "Esc again to interrupt", shown while the first Esc arms an interrupt.
nonisolated enum OpenCodeInterruptKeyboardHint {
    static let text = "Esc again to interrupt"
}

/// Keyboard focus targets inside a permission or question prompt.
nonisolated enum OpenCodePromptFocusTarget: Hashable, Sendable {
    case option(Int)
    case customOption
    /// Action buttons in reading order: Deny, Always, Allow once for permissions;
    /// Dismiss, Back, Next / Submit for questions.
    case action(Int)
    /// Next, or Submit on the last question.
    case primaryAction
}

nonisolated enum OpenCodePromptFocus {
    /// Where the first Tab or arrow key lands in a prompt that has nothing focused:
    /// the first option of a question, the first enabled action of a permission.
    /// Nil when nothing in the prompt can take focus (all actions disabled).
    static func initialTarget(
        optionCount: Int,
        customAllowed: Bool,
        optionsEnabled: Bool,
        actionsEnabled: [Bool]
    ) -> OpenCodePromptFocusTarget? {
        if optionsEnabled {
            if optionCount > 0 { return .option(0) }
            if customAllowed { return .customOption }
        }
        return actionsEnabled.firstIndex(of: true).map { .action($0) }
    }

    /// A new request arrived: a prompt replaced the composer, or the next queued
    /// request replaced the one just answered. Focus left on the previous request
    /// (or on the composer) must not carry over, so it is cleared.
    static func clearsFocusOnRequestChange(previousRequestID: String?, requestID: String?) -> Bool {
        guard let requestID else { return false }
        return requestID != previousRequestID
    }

    /// The request on screen went away (answered, dismissed, or answered elsewhere)
    /// while keyboard focus was on it: its control is gone, and iPadOS would focus
    /// the first control on screen instead, so that focus is cleared.
    static func clearsFocusOnLeave(previousRequestID: String?, requestID: String?, focusWasInPrompt: Bool) -> Bool {
        guard previousRequestID != nil, previousRequestID != requestID else { return false }
        return focusWasInPrompt
    }

    /// Focus counts as inside the prompt only when the user moved it there with Tab
    /// or an arrow key after this request arrived. iPadOS refocuses on its own when
    /// the focused composer control disappears, and the only Tab stop left can be
    /// the prompt's first control; that automatic focus does not count.
    static func focusEnteredByKeyboard(focusInPrompt: Bool, navigationKeysSinceArrival: Bool) -> Bool {
        focusInPrompt && navigationKeysSinceArrival
    }

    /// When a request arrives, keyboard focus is cleared so nothing is focused:
    /// iPadOS would otherwise move it to the first control on screen, where a stray
    /// Return would press it. Focus the user moved into the prompt, or another text
    /// field being typed in, stays.
    static func clearsFocusOnArrival(
        hardwareKeyboardConnected: Bool,
        focusInsidePrompt: Bool,
        otherTextFieldFocused: Bool
    ) -> Bool {
        hardwareKeyboardConnected && !focusInsidePrompt && !otherTextFieldFocused
    }

    /// Return in "Type your own answer" moves focus to Next / Submit; when that is
    /// disabled (nothing typed yet) focus stays in the field.
    static func targetAfterCustomAnswerReturn(primaryActionEnabled: Bool) -> OpenCodePromptFocusTarget? {
        primaryActionEnabled ? .primaryAction : nil
    }
}

// MARK: - Tier 2: prompt chords, digit picks, prompt history

/// The visible prompt, as far as the prompt chords need it.
nonisolated enum OpenCodePromptChordState: Equatable, Sendable {
    case none
    /// `actionsEnabled` is false while a reply is in flight or the session is read-only.
    case permission(requestID: String, actionsEnabled: Bool, alwaysOffered: Bool)
    /// The "Deny with a note" step of a permission; `sendEnabled` once the note is not blank.
    case permissionNote(requestID: String, sendEnabled: Bool)
    case question(requestID: String, isLastStep: Bool, nextEnabled: Bool, submitEnabled: Bool, dismissEnabled: Bool)
}

/// What a prompt chord does; each goes through the same path as its button.
nonisolated enum OpenCodePromptChordOutcome: Equatable, Sendable {
    case allowOnce
    case alwaysAllow
    case deny
    /// Opens the "Deny with a note" step.
    case denyWithNote
    /// Sends the note of the "Deny with a note" step.
    case sendNote
    case next
    case submit
    case dismiss

    /// Title in the menu bar and the hold-⌘ overlay.
    var title: LocalizedStringResource {
        switch self {
        case .allowOnce: LocalizedStringResource("keyboard_shortcuts.allow_once", defaultValue: "Allow Once")
        case .alwaysAllow: LocalizedStringResource("keyboard_shortcuts.always_allow", defaultValue: "Always Allow")
        case .deny: LocalizedStringResource("keyboard_shortcuts.deny", defaultValue: "Deny")
        case .denyWithNote: LocalizedStringResource("keyboard_shortcuts.deny_with_note", defaultValue: "Deny with a Note")
        case .sendNote: LocalizedStringResource("keyboard_shortcuts.send_note", defaultValue: "Send Note")
        case .next: LocalizedStringResource("keyboard_shortcuts.next_question", defaultValue: "Next Question")
        case .submit: LocalizedStringResource("keyboard_shortcuts.submit_answers", defaultValue: "Submit Answers")
        case .dismiss: LocalizedStringResource("keyboard_shortcuts.dismiss_question", defaultValue: "Dismiss Question")
        }
    }
}

nonisolated enum OpenCodePromptChordRule {
    /// ⌘⏎ allows once or goes Next / Submits; ⌥⌘⏎ always allows when offered;
    /// ⌘⌫ denies or dismisses; ⌥⌘⌫ opens "Deny with a note" on a permission, whose
    /// note step only takes ⌘⏎ (Send). Nil when the chord does nothing now (no
    /// prompt, the button is disabled, or a reply is in flight).
    static func outcome(_ shortcut: OpenCodeKeyboardShortcut, state: OpenCodePromptChordState) -> OpenCodePromptChordOutcome? {
        switch (shortcut, state) {
        case let (.promptPrimary, .permission(_, enabled, _)):
            return enabled ? .allowOnce : nil
        case let (.promptAlwaysAllow, .permission(_, enabled, offered)):
            return enabled && offered ? .alwaysAllow : nil
        case let (.promptReject, .permission(_, enabled, _)):
            return enabled ? .deny : nil
        case let (.promptRejectWithNote, .permission(_, enabled, _)):
            return enabled ? .denyWithNote : nil
        case let (.promptPrimary, .permissionNote(_, sendEnabled)):
            return sendEnabled ? .sendNote : nil
        case let (.promptPrimary, .question(_, isLastStep, nextEnabled, submitEnabled, _)):
            if isLastStep { return submitEnabled ? .submit : nil }
            return nextEnabled ? .next : nil
        case let (.promptReject, .question(_, _, _, _, dismissEnabled)):
            return dismissEnabled ? .dismiss : nil
        default:
            return nil
        }
    }
}

/// Bare 1–9 in a question prompt pick that option, like tapping it: a single
/// select selects it, a multi select toggles it.
nonisolated enum OpenCodeQuestionDigitPick {
    /// Only once keyboard focus is inside the prompt and not in a text field, so a
    /// digit typed anywhere else never answers; and only a bare digit (⌘2, ⌃2, ⌥2
    /// are other shortcuts). Keypad digits count.
    static func optionIndex(
        for character: Character,
        modifiers: EventModifiers,
        optionCount: Int,
        focusInsidePrompt: Bool,
        textFieldFocused: Bool,
        optionsEnabled: Bool
    ) -> Int? {
        guard modifiers.subtracting([.numericPad, .capsLock]).isEmpty,
              focusInsidePrompt, !textFieldFocused, optionsEnabled,
              let digit = character.wholeNumberValue, (1...9).contains(digit),
              String(character) == String(digit)
        else { return nil }
        let index = digit - 1
        return index < optionCount ? index : nil
    }
}

nonisolated enum OpenCodePromptHistoryDirection: Sendable {
    /// ↑: an older prompt.
    case older
    /// ↓: a newer prompt, then back to the empty prompt.
    case newer
}

/// The prompts ↑ recalls: this session's sent user prompts, oldest first, without
/// sends still pending or failed and without blank ones.
nonisolated enum OpenCodePromptHistory {
    struct Message: Equatable, Sendable {
        let isUser: Bool
        let text: String
        /// Not delivered (still sending, or the send failed).
        let isPending: Bool
    }

    static func entries(_ messages: [Message]) -> [String] {
        messages.compactMap { message in
            guard message.isUser, !message.isPending,
                  !message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else { return nil }
            return message.text
        }
    }
}

/// ↑ in an empty prompt (no text, no attachments) with the caret at the start
/// recalls this session's sent prompts, newest first; ↓ steps forward and finally
/// back to empty. In a recalled multi-line prompt ↑ steps only from the first
/// line and ↓ only from the last; elsewhere they move the caret. Any edit ends
/// browsing (the text no longer matches the recalled prompt).
nonisolated struct OpenCodePromptHistoryBrowser: Equatable, Sendable {
    /// Index into the history being shown; nil while not browsing.
    private(set) var index: Int?

    init() {}

    /// The text to show for this key, or nil to leave the key to the text field.
    /// `history` is oldest first.
    mutating func step(
        _ direction: OpenCodePromptHistoryDirection,
        history: [String],
        text: String,
        hasAttachments: Bool = false,
        caretAtStart: Bool,
        caretOnFirstLine: Bool = true,
        caretOnLastLine: Bool = true
    ) -> String? {
        if let index, !(history.indices.contains(index) && history[index] == text) {
            self.index = nil
        }
        guard let index else {
            guard direction == .older, text.isEmpty, !hasAttachments, caretAtStart,
                  let last = history.indices.last
            else { return nil }
            self.index = last
            return history[last]
        }
        // Inside a recalled multi-line prompt the arrows move the caret first.
        switch direction {
        case .older where !caretOnFirstLine, .newer where !caretOnLastLine:
            return nil
        default:
            break
        }
        switch direction {
        case .older:
            let older = max(index - 1, 0)
            self.index = older
            return history[older]
        case .newer:
            if index < history.count - 1 {
                self.index = index + 1
                return history[index + 1]
            }
            self.index = nil
            return ""
        }
    }
}
