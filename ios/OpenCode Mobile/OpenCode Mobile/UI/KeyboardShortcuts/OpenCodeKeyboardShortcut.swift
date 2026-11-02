import SwiftUI

/// Hardware keyboard shortcuts. Titles show in the iPadOS menu bar and the hold-⌘
/// shortcut overlay; a published action can replace the title (the prompt chords
/// read "Allow Once" or "Next", a project switch names the project).
nonisolated enum OpenCodeKeyboardShortcut: Hashable, CaseIterable, Sendable {
    /// Collapse or reopen the leading pane of a Home or Settings split view.
    case toggleSidebar
    /// Open the session below the selected one in the Home list.
    case nextSession
    /// Open the session above the selected one in the Home list.
    case previousSession
    /// Put the caret in the visible Session Chat prompt.
    case focusComposer
    /// Start a session in the project of the current context.
    case newSession
    /// Open the slash-command list over the Session Chat prompt.
    case commandPalette
    /// Open the Session Chat model picker.
    case modelPicker
    /// Open Settings from Home.
    case settings
    /// Show the Nth project of the Home dock's project order (1...9).
    case switchProject(Int)
    /// Allow Once on a permission prompt; Next or Submit on a question prompt.
    case promptPrimary
    /// Always Allow on a permission prompt that offers it.
    case promptAlwaysAllow
    /// Deny a permission prompt; Dismiss a question prompt.
    case promptReject

    static var allCases: [Self] {
        [
            .toggleSidebar, .nextSession, .previousSession, .focusComposer,
            .newSession, .commandPalette, .modelPicker, .settings,
        ] + (1...9).map { .switchProject($0) } + [.promptPrimary, .promptAlwaysAllow, .promptReject]
    }

    var title: String {
        switch self {
        case .toggleSidebar: "Toggle Sidebar"
        case .nextSession: "Next Session"
        case .previousSession: "Previous Session"
        case .focusComposer: "Focus Composer"
        case .newSession: "New Session"
        case .commandPalette: "Command Palette"
        case .modelPicker: "Choose Model"
        case .settings: "Settings"
        case let .switchProject(number): "Project \(number)"
        case .promptPrimary: "Allow Once"
        case .promptAlwaysAllow: "Always Allow"
        case .promptReject: "Deny"
        }
    }

    var key: KeyEquivalent {
        switch self {
        case .toggleSidebar: "s"
        case .nextSession, .previousSession: .tab
        case .focusComposer: "l"
        case .newSession: "n"
        case .commandPalette: "k"
        case .modelPicker: "m"
        case .settings: ","
        case let .switchProject(number): KeyEquivalent(Character(String(number)))
        case .promptPrimary, .promptAlwaysAllow: .return
        case .promptReject: .delete
        }
    }

    var modifiers: EventModifiers {
        switch self {
        case .toggleSidebar: [.control, .command]
        case .nextSession: .control
        case .previousSession: [.control, .shift]
        case .focusComposer, .newSession, .commandPalette, .settings, .switchProject, .promptPrimary, .promptReject:
            .command
        case .modelPicker: [.command, .shift]
        case .promptAlwaysAllow: [.option, .command]
        }
    }

    /// Answers the visible permission or question prompt.
    var isPromptChord: Bool {
        switch self {
        case .promptPrimary, .promptAlwaysAllow, .promptReject: true
        default: false
        }
    }

    /// The shortcut bound to exactly this key and modifiers (extra or missing
    /// modifiers do not match).
    static func matching(key: KeyEquivalent, modifiers: EventModifiers) -> [Self] {
        allCases.filter { $0.key == key && $0.modifiers == modifiers }
    }
}

/// What currently owns keyboard text input.
nonisolated enum OpenCodeTextInputFocus: Equatable, Sendable {
    case none
    /// The Session Chat prompt (`OpenCodeSessionComposer`).
    case composer
    /// "Type your own answer" in a question prompt.
    case promptAnswer
    /// Any other field: search, settings inputs, dialogs, server address, terminal.
    case otherTextField
}

nonisolated enum OpenCodeKeyboardShortcutPolicy {
    /// Shortcuts stay out of the way while another field is being typed in, so
    /// the key event reaches that field. The composer itself does not block them.
    static func allowsShortcuts(textInputFocus: OpenCodeTextInputFocus) -> Bool {
        switch textInputFocus {
        case .none, .composer: true
        case .promptAnswer, .otherTextField: false
        }
    }

    /// Adds the window-level overlay check: nothing fires behind a sheet, dialog, or menu.
    static func allowsShortcuts(textInputFocus: OpenCodeTextInputFocus, overlayPresented: Bool) -> Bool {
        !overlayPresented && allowsShortcuts(textInputFocus: textInputFocus)
    }

    /// Per shortcut. Prompt chords are deliberate, so they fire while nothing has
    /// focus; in "Type your own answer" only ⌘⏎ (save and Next / Submit) fires,
    /// and ⌘⌫ stays with the field (it deletes text).
    static func allows(
        _ shortcut: OpenCodeKeyboardShortcut,
        textInputFocus: OpenCodeTextInputFocus,
        overlayPresented: Bool
    ) -> Bool {
        guard !overlayPresented else { return false }
        if shortcut.isPromptChord, textInputFocus == .promptAnswer {
            return shortcut == .promptPrimary
        }
        return allowsShortcuts(textInputFocus: textInputFocus)
    }
}

/// Holding a chord's keys makes the system repeat it; only the first press fires.
/// Every attempt (fired or not) restarts the window, so a held key never fires
/// again, while a new press after letting go does.
nonisolated enum OpenCodeShortcutRepeatGuard {
    static let window: TimeInterval = 0.6

    static func fires(lastAttemptAt: Date?, now: Date) -> Bool {
        guard let lastAttemptAt else { return true }
        let elapsed = now.timeIntervalSince(lastAttemptAt)
        return elapsed < 0 || elapsed >= window
    }
}

nonisolated enum OpenCodeSessionNavigationDirection: Sendable {
    case next
    case previous
}

nonisolated enum OpenCodeSessionNavigation {
    /// Session to open for Next/Previous Session over the list as rendered, top to
    /// bottom. No wrap-around. With no selection (or one not in the list), next
    /// opens the first session and previous opens the last.
    static func adjacentSessionID(
        orderedIDs: [String],
        selectedID: String?,
        direction: OpenCodeSessionNavigationDirection
    ) -> String? {
        guard !orderedIDs.isEmpty else { return nil }
        guard let selectedID, let index = orderedIDs.firstIndex(of: selectedID) else {
            switch direction {
            case .next: return orderedIDs.first
            case .previous: return orderedIDs.last
            }
        }
        switch direction {
        case .next:
            let target = orderedIDs.index(after: index)
            return target < orderedIDs.endIndex ? orderedIDs[target] : nil
        case .previous:
            return index > orderedIDs.startIndex ? orderedIDs[orderedIDs.index(before: index)] : nil
        }
    }
}
