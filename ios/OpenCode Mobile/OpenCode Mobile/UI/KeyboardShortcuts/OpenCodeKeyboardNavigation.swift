import Foundation

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

/// Esc priority: close the topmost overlay, else leave the focused text field or
/// control, else interrupt the running turn in Session Chat. Like the opencode
/// TUI, interrupting takes two presses: the first arms it, a second within
/// `interruptWindow` interrupts. Any Esc consumed by an earlier step disarms.
nonisolated enum OpenCodeEscapeResolver {
    static let interruptWindow: TimeInterval = 5

    static func resolve(
        _ context: OpenCodeEscapeContext,
        armedAt: Date?,
        now: Date
    ) -> OpenCodeEscapeResolution {
        if context.overlayPresented { return .init(action: .closeOverlay, armedAt: nil) }
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
