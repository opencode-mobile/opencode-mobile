import Observation
import SwiftUI
import UIKit

/// Handler a screen publishes for one `OpenCodeKeyboardShortcut`.
///
/// Equal when published by the same view instance. Closures are never equal, and
/// a fresh value on every render makes the scene rebuild its commands, which
/// re-renders the publisher: an endless loop. Handlers must therefore read live
/// state (`@State`, observable models) rather than values captured at render time.
struct OpenCodeKeyboardShortcutAction: Equatable {
    let publisherID: UUID
    let perform: () -> Void

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.publisherID == rhs.publisherID
    }
}

private struct ToggleSidebarShortcutKey: FocusedValueKey {
    typealias Value = OpenCodeKeyboardShortcutAction
}

private struct NextSessionShortcutKey: FocusedValueKey {
    typealias Value = OpenCodeKeyboardShortcutAction
}

private struct PreviousSessionShortcutKey: FocusedValueKey {
    typealias Value = OpenCodeKeyboardShortcutAction
}

private struct FocusComposerShortcutKey: FocusedValueKey {
    typealias Value = OpenCodeKeyboardShortcutAction
}

extension FocusedValues {
    var openCodeToggleSidebar: OpenCodeKeyboardShortcutAction? {
        get { self[ToggleSidebarShortcutKey.self] }
        set { self[ToggleSidebarShortcutKey.self] = newValue }
    }

    var openCodeNextSession: OpenCodeKeyboardShortcutAction? {
        get { self[NextSessionShortcutKey.self] }
        set { self[NextSessionShortcutKey.self] = newValue }
    }

    var openCodePreviousSession: OpenCodeKeyboardShortcutAction? {
        get { self[PreviousSessionShortcutKey.self] }
        set { self[PreviousSessionShortcutKey.self] = newValue }
    }

    var openCodeFocusComposer: OpenCodeKeyboardShortcutAction? {
        get { self[FocusComposerShortcutKey.self] }
        set { self[FocusComposerShortcutKey.self] = newValue }
    }
}

extension OpenCodeKeyboardShortcut {
    fileprivate var focusedValueKeyPath: WritableKeyPath<FocusedValues, OpenCodeKeyboardShortcutAction?> {
        switch self {
        case .toggleSidebar: \.openCodeToggleSidebar
        case .nextSession: \.openCodeNextSession
        case .previousSession: \.openCodePreviousSession
        case .focusComposer: \.openCodeFocusComposer
        }
    }
}

/// Scene commands for the tablet shortcuts, in the View menu. Each command is
/// listed (menu bar and hold-⌘ overlay) only while the visible screen publishes a
/// handler for it and no other text field, sheet, or dialog has the keyboard.
/// The menu can lag a render behind, so firing re-checks the gate.
struct OpenCodeKeyboardShortcutCommands: Commands {
    @FocusedValue(\.openCodeToggleSidebar) private var toggleSidebar
    @FocusedValue(\.openCodeNextSession) private var nextSession
    @FocusedValue(\.openCodePreviousSession) private var previousSession
    @FocusedValue(\.openCodeFocusComposer) private var focusComposer

    var body: some Commands {
        let _ = OpenCodeKeyCommandBridge.update(next: nextSession, previous: previousSession)
        // One group in the View menu, replacing the system sidebar group so ⌃⌘S
        // has a single owner. (Items in a separate `after: .sidebar` group do not
        // show on iPadOS.)
        CommandGroup(replacing: .sidebar) {
            command(.toggleSidebar, toggleSidebar)
            command(.nextSession, nextSession)
            command(.previousSession, previousSession)
            command(.focusComposer, focusComposer)
        }
    }

    @ViewBuilder
    private func command(
        _ shortcut: OpenCodeKeyboardShortcut,
        _ action: OpenCodeKeyboardShortcutAction?
    ) -> some View {
        if let action {
            Button(shortcut.title) {
                guard OpenCodeKeyboardShortcutGate.allowsShortcutsNow() else { return }
                action.perform()
            }
            .keyboardShortcut(shortcut.key, modifiers: shortcut.modifiers)
        }
    }
}

extension View {
    /// Publishes `action` for `shortcut` while this view is on screen. Pass nil when
    /// the shortcut does not apply (screen covered, no split, no prompt composer).
    /// The handler is withheld while another text field, sheet, or dialog is active.
    func openCodeKeyboardShortcut(
        _ shortcut: OpenCodeKeyboardShortcut,
        action: (() -> Void)?
    ) -> some View {
        modifier(OpenCodeKeyboardShortcutPublisher(shortcut: shortcut, action: action))
    }
}

private struct OpenCodeKeyboardShortcutPublisher: ViewModifier {
    let shortcut: OpenCodeKeyboardShortcut
    let action: (() -> Void)?
    @State private var publisherID = UUID()

    func body(content: Content) -> some View {
        let published = OpenCodeKeyboardShortcutGate.allowsShortcuts ? action : nil
        content.focusedSceneValue(
            shortcut.focusedValueKeyPath,
            published.map { OpenCodeKeyboardShortcutAction(publisherID: publisherID, perform: $0) }
        )
    }
}

/// Combines the focus policy with live app state.
enum OpenCodeKeyboardShortcutGate {
    /// Observable state; decides which shortcuts are listed and matched. A disabled
    /// (unlisted) shortcut leaves the key event to the focused field.
    static var allowsShortcuts: Bool {
        OpenCodeKeyboardShortcutPolicy.allowsShortcuts(
            textInputFocus: OpenCodeTextInputFocusMonitor.shared.focus,
            overlayPresented: OpenCodeWindowOverlayRegistry.shared.hasOverlay
        )
    }

    /// Re-check when a shortcut fires, against the live first responder and any
    /// UIKit presentation (connection setup, photo and file pickers, share sheet).
    /// Also covers inputs that post no editing notifications, such as the web
    /// terminal.
    static func allowsShortcutsNow() -> Bool {
        let focus = OpenCodeTextInputFocusMonitor.classify(UIResponder.openCodeCurrentFirstResponder())
        guard OpenCodeKeyboardShortcutPolicy.allowsShortcuts(
            textInputFocus: focus,
            overlayPresented: OpenCodeWindowOverlayRegistry.shared.hasOverlayNow
        ) else { return false }
        let keyWindow = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
        return keyWindow?.rootViewController?.presentedViewController == nil
    }
}

/// Tracks which text input is being edited from UIKit's begin/end editing
/// notifications. Every SwiftUI `TextField`, `TextEditor`, and search field is
/// backed by a `UITextField` or `UITextView`, so this sees them all, plus the
/// UIKit-backed composer.
@Observable
final class OpenCodeTextInputFocusMonitor {
    static let shared = OpenCodeTextInputFocusMonitor()

    private(set) var focus: OpenCodeTextInputFocus = .none

    @ObservationIgnored private weak var editingInput: UIResponder?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    private init() {
        let center = NotificationCenter.default
        let begin = [UITextField.textDidBeginEditingNotification, UITextView.textDidBeginEditingNotification]
        let end = [UITextField.textDidEndEditingNotification, UITextView.textDidEndEditingNotification]
        for name in begin {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] note in
                let input = note.object as? UIResponder
                MainActor.assumeIsolated { self?.didBeginEditing(input) }
            })
        }
        for name in end {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] note in
                let input = note.object as? UIResponder
                MainActor.assumeIsolated { self?.didEndEditing(input) }
            })
        }
    }

    static func classify(_ responder: UIResponder?) -> OpenCodeTextInputFocus {
        guard let responder, responder is UITextInput else { return .none }
        return responder is OpenCodeComposerTextInput ? .composer : .otherTextField
    }

    private func didBeginEditing(_ input: UIResponder?) {
        editingInput = input
        update(Self.classify(input))
    }

    private func didEndEditing(_ input: UIResponder?) {
        // Moving between fields ends the old one first; ignore a late end for a
        // field that is no longer the one being edited.
        guard input === editingInput || editingInput == nil else { return }
        editingInput = nil
        update(.none)
    }

    private func update(_ next: OpenCodeTextInputFocus) {
        if focus != next {
            focus = next
        }
    }
}

extension UIResponder {
    private static weak var openCodeCapturedFirstResponder: UIResponder?

    /// The window's current first responder, found by sending a no-op action
    /// through the responder chain.
    static func openCodeCurrentFirstResponder() -> UIResponder? {
        openCodeCapturedFirstResponder = nil
        UIApplication.shared.sendAction(
            #selector(openCodeCaptureFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
        return openCodeCapturedFirstResponder
    }

    @objc private func openCodeCaptureFirstResponder() {
        UIResponder.openCodeCapturedFirstResponder = self
    }
}


/// ⌃Tab and ⌃⇧Tab are claimed by iPadOS keyboard focus navigation before menu
/// commands see them, and SwiftUI menu shortcuts cannot ask for priority. While
/// Next/Previous Session apply, this registers matching `UIKeyCommand`s with
/// `wantsPriorityOverSystemBehavior` on the window's root view controller, which
/// is on the responder chain of everything in the main window (overlay windows
/// and their text fields are not). The menu items stay for the menu bar and the
/// hold-⌘ overlay; these commands carry no title so nothing is listed twice.
enum OpenCodeKeyCommandBridge {
    private static var nextAction: OpenCodeKeyboardShortcutAction?
    private static var previousAction: OpenCodeKeyboardShortcutAction?
    private static weak var host: UIViewController?
    private static var syncScheduled = false

    private static let nextCommand = priorityCommand(
        .nextSession,
        action: #selector(UIViewController.openCodePerformNextSession(_:))
    )
    private static let previousCommand = priorityCommand(
        .previousSession,
        action: #selector(UIViewController.openCodePerformPreviousSession(_:))
    )

    private static func priorityCommand(_ shortcut: OpenCodeKeyboardShortcut, action: Selector) -> UIKeyCommand {
        let command = UIKeyCommand(
            input: "\t",
            modifierFlags: shortcut.uiKeyModifierFlags,
            action: action
        )
        command.wantsPriorityOverSystemBehavior = true
        return command
    }

    /// Called from the commands body with the resolved handlers.
    static func update(next: OpenCodeKeyboardShortcutAction?, previous: OpenCodeKeyboardShortcutAction?) {
        nextAction = next
        previousAction = previous
        scheduleSync()
    }

    /// Called by `OpenCodeKeyCommandBridgeAnchor` once it is in a window.
    static func attach(to viewController: UIViewController?) {
        guard host !== viewController else { return }
        if let host {
            host.removeKeyCommand(nextCommand)
            host.removeKeyCommand(previousCommand)
        }
        host = viewController
        scheduleSync()
    }

    static func perform(_ shortcut: OpenCodeKeyboardShortcut) {
        let action = shortcut == .nextSession ? nextAction : previousAction
        guard let action, OpenCodeKeyboardShortcutGate.allowsShortcutsNow() else { return }
        action.perform()
    }

    // Commands bodies run during a SwiftUI update; touch UIKit on the next turn.
    private static func scheduleSync() {
        guard !syncScheduled else { return }
        syncScheduled = true
        DispatchQueue.main.async {
            syncScheduled = false
            guard let host else { return }
            sync(nextCommand, active: nextAction != nil, on: host)
            sync(previousCommand, active: previousAction != nil, on: host)
        }
    }

    private static func sync(_ command: UIKeyCommand, active: Bool, on host: UIViewController) {
        let installed = host.keyCommands?.contains(command) ?? false
        if active, !installed {
            host.addKeyCommand(command)
        } else if !active, installed {
            host.removeKeyCommand(command)
        }
    }
}

extension OpenCodeKeyboardShortcut {
    fileprivate var uiKeyModifierFlags: UIKeyModifierFlags {
        var flags: UIKeyModifierFlags = []
        if modifiers.contains(.control) { flags.insert(.control) }
        if modifiers.contains(.shift) { flags.insert(.shift) }
        if modifiers.contains(.command) { flags.insert(.command) }
        if modifiers.contains(.option) { flags.insert(.alternate) }
        return flags
    }
}

extension UIViewController {
    // The first view controller on the responder chain handles these; any of them
    // forwards to the bridge.
    @objc fileprivate func openCodePerformNextSession(_ sender: UIKeyCommand) {
        OpenCodeKeyCommandBridge.perform(.nextSession)
    }

    @objc fileprivate func openCodePerformPreviousSession(_ sender: UIKeyCommand) {
        OpenCodeKeyCommandBridge.perform(.previousSession)
    }
}

/// Invisible view that hands the window's root view controller to the bridge.
struct OpenCodeKeyCommandBridgeAnchor: UIViewRepresentable {
    func makeUIView(context: Context) -> AnchorView {
        AnchorView()
    }

    func updateUIView(_ uiView: AnchorView, context: Context) {}

    final class AnchorView: UIView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let root = window?.rootViewController {
                OpenCodeKeyCommandBridge.attach(to: root)
            }
        }
    }
}
