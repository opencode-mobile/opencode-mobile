import SwiftUI

/// Hardware keyboard shortcuts for the tablet layouts. Titles show in the iPadOS
/// menu bar and the hold-⌘ shortcut overlay.
nonisolated enum OpenCodeKeyboardShortcut: CaseIterable, Sendable {
    /// Collapse or reopen the leading pane of a Home or Settings split view.
    case toggleSidebar
    /// Open the session below the selected one in the Home list.
    case nextSession
    /// Open the session above the selected one in the Home list.
    case previousSession
    /// Put the caret in the visible Session Chat prompt.
    case focusComposer

    var title: String {
        switch self {
        case .toggleSidebar: "Toggle Sidebar"
        case .nextSession: "Next Session"
        case .previousSession: "Previous Session"
        case .focusComposer: "Focus Composer"
        }
    }

    var key: KeyEquivalent {
        switch self {
        case .toggleSidebar: "s"
        case .nextSession, .previousSession: .tab
        case .focusComposer: "l"
        }
    }

    var modifiers: EventModifiers {
        switch self {
        case .toggleSidebar: [.control, .command]
        case .nextSession: .control
        case .previousSession: [.control, .shift]
        case .focusComposer: .command
        }
    }
}

/// What currently owns keyboard text input.
nonisolated enum OpenCodeTextInputFocus: Equatable, Sendable {
    case none
    /// The Session Chat prompt (`OpenCodeSessionComposer`).
    case composer
    /// Any other field: search, settings inputs, dialogs, server address, terminal.
    case otherTextField
}

nonisolated enum OpenCodeKeyboardShortcutPolicy {
    /// Shortcuts stay out of the way while another field is being typed in, so
    /// the key event reaches that field. The composer itself does not block them.
    static func allowsShortcuts(textInputFocus: OpenCodeTextInputFocus) -> Bool {
        switch textInputFocus {
        case .none, .composer: true
        case .otherTextField: false
        }
    }

    /// Adds the window-level overlay check: nothing fires behind a sheet, dialog, or menu.
    static func allowsShortcuts(textInputFocus: OpenCodeTextInputFocus, overlayPresented: Bool) -> Bool {
        !overlayPresented && allowsShortcuts(textInputFocus: textInputFocus)
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
