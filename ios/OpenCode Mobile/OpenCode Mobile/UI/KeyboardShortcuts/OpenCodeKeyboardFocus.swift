import GameController
import Observation
import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass

/// Live hardware-keyboard focus state shared by the design-system focus modifier,
/// the Esc router, and prompts.
@Observable
final class OpenCodeKeyboardFocusState {
    static let shared = OpenCodeKeyboardFocusState()

    /// A hardware keyboard is attached (iPad keyboard, Bluetooth, the Simulator's).
    private(set) var hardwareKeyboardConnected = GCKeyboard.coalesced != nil
    /// A design-system control (not a text field) has keyboard focus.
    private(set) var hasFocusedControl = false
    /// Layer of the topmost window overlay (sheet, dialog, menu), 0 for none. Only
    /// controls in that layer are Tab stops, so focus cannot wander behind it.
    private(set) var topOverlayLayer = 0

    /// The control a waiting prompt is entered at (see `setPromptEntry`).
    private(set) var promptEntryKey: AnyHashable?
    /// The control the next Tab lands on, after leaving a text field that wanted to
    /// hand focus to it (see `setNextTabTarget`).
    private(set) var nextTabTarget: AnyHashable?

    /// The last input was a key press, not a touch or pointer click; the focus ring
    /// shows only then (see `OpenCodeFocusRingPolicy`).
    private(set) var lastInputWasKeyboard = true
    /// Counts Tab and arrow presses, so a prompt can tell focus the user moved into it
    /// from focus iPadOS moved there by itself.
    @ObservationIgnored private(set) var navigationKeyCount = 0
    @ObservationIgnored private var lastTouchAt: Date?

    /// The control that last took keyboard focus, so a closing overlay can hand
    /// Tab back to the control that opened it.
    @ObservationIgnored private(set) var lastFocusedControl: AnyHashable?
    @ObservationIgnored private var focusedControls: Set<AnyHashable> = []
    @ObservationIgnored private var observers: [NSObjectProtocol] = []
    @ObservationIgnored private var promptEntries: [(owner: UUID, key: AnyHashable)] = []

    private init() {
        let center = NotificationCenter.default
        for name in [Notification.Name.GCKeyboardDidConnect, .GCKeyboardDidDisconnect] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.refreshKeyboard() }
            })
        }
        observeKeyPresses()
    }

    private func refreshKeyboard() {
        let connected = GCKeyboard.coalesced != nil
        if connected != hardwareKeyboardConnected { hardwareKeyboardConnected = connected }
        observeKeyPresses()
        if connected { OpenCodeKeyboardAnchor.reclaimSoon() }
    }

    /// Key presses reach UIKit as usual; this only watches them.
    private func observeKeyPresses() {
        GCKeyboard.coalesced?.keyboardInput?.keyChangedHandler = { _, _, keyCode, pressed in
            guard pressed else { return }
            DispatchQueue.main.async {
                OpenCodeKeyboardFocusState.shared.noteKeyPress(
                    navigation: [.tab, .upArrow, .downArrow, .leftArrow, .rightArrow].contains(keyCode)
                )
            }
        }
    }

    /// A hardware key went down. Also called by controls that handle keys themselves.
    func noteKeyPress(navigation: Bool) {
        if navigation { navigationKeyCount += 1 }
        if !lastInputWasKeyboard { lastInputWasKeyboard = true }
    }

    /// A touch or pointer click began anywhere in the window.
    func noteTouch() {
        lastTouchAt = Date()
        if lastInputWasKeyboard { lastInputWasKeyboard = false }
        // Once the touch has been handled and focus has settled.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
            guard let self, OpenCodeFocusRingPolicy.clearsFocusAfterTouch(
                controlFocused: hasFocusedControl,
                lastInputWasKeyboard: lastInputWasKeyboard,
                textInputFocused: OpenCodeTextInputFocusMonitor.shared.focus != .none
            ) else { return }
            OpenCodeKeyboardAnchor.takeFocus()
        }
    }

    func controlFocusChanged(_ key: AnyHashable, focused: Bool) {
        if focused {
            focusedControls.insert(key)
            lastFocusedControl = key
            // Focus that follows a touch is touch focus; any other is keyboard focus.
            let byTouch = OpenCodeFocusRingPolicy.focusFollowsTouch(touchAt: lastTouchAt, focusAt: Date())
            if lastInputWasKeyboard == byTouch { lastInputWasKeyboard = !byTouch }
        } else {
            focusedControls.remove(key)
        }
        let next = !focusedControls.isEmpty
        if next != hasFocusedControl { hasFocusedControl = next }
        if focused, nextTabTarget != nil { nextTabTarget = nil }
        if !next { OpenCodeKeyboardAnchor.reclaimSoon() }
    }

    /// A permission or question prompt on screen publishes the control focus enters
    /// it at. While one is published and nothing has focus, that control is the only
    /// Tab stop, so the first Tab lands on it.
    func setPromptEntry<Key: Hashable>(_ owner: UUID, key: Key?) {
        promptEntries.removeAll { $0.owner == owner }
        if let key {
            promptEntries.append((owner, AnyHashable(key)))
            OpenCodeKeyboardAnchor.reclaimSoon()
        }
        let next = promptEntries.last?.key
        if next != promptEntryKey { promptEntryKey = next }
    }

    /// Leaving a text field toward a control: iPadOS SwiftUI cannot move focus in
    /// code out of a UIKit text field, so the field stops editing and the next Tab
    /// lands on `key`.
    func setNextTabTarget<Key: Hashable>(_ key: Key) {
        nextTabTarget = AnyHashable(key)
    }

    /// The only Tab stop right now, if any, while nothing has keyboard focus: a
    /// control a text field handed focus to, else a waiting prompt's entry control.
    /// (iPadOS SwiftUI cannot move focus in code while nothing is focused, so the
    /// system's own Tab does the move.)
    var tabRestriction: AnyHashable? {
        guard !hasFocusedControl, OpenCodeTextInputFocusMonitor.shared.focus == .none else { return nil }
        return OpenCodeTabRestriction.target(nextTabTarget: nextTabTarget, promptEntry: promptEntryKey)
    }

    fileprivate func setTopOverlayLayer(_ layer: Int) {
        if layer != topOverlayLayer { topOverlayLayer = layer }
    }
}

/// Esc for the whole app. One press does one thing, see `OpenCodeEscapeResolver`.
@Observable
final class OpenCodeEscapeRouter {
    static let shared = OpenCodeEscapeRouter()

    /// When the first Esc armed an interrupt; nil when not armed. Drives the
    /// "Esc again to interrupt" hint.
    private(set) var interruptArmedAt: Date?

    @ObservationIgnored var now: () -> Date = Date.init
    @ObservationIgnored private var interrupts: [(owner: UUID, interrupt: () -> Void)] = []
    @ObservationIgnored private var expiryTask: Task<Void, Never>?

    /// Session Chat publishes its interrupt while its session is busy and Stop is
    /// enabled, and nil otherwise; nil disarms a pending interrupt.
    func setInterrupt(_ owner: UUID, interrupt: (() -> Void)?) {
        interrupts.removeAll { $0.owner == owner }
        if let interrupt {
            interrupts.append((owner, interrupt))
        } else if interrupts.isEmpty {
            disarm()
        }
    }

    func handleEscape() {
        let responder = UIResponder.openCodeCurrentFirstResponder()
        let isAnchor = responder is OpenCodeKeyboardAnchor.AnchorView
        let textInput = responder is UITextInput
        let context = OpenCodeEscapeContext(
            overlayPresented: OpenCodeOverlayEscape.hasOverlay,
            textInputFocused: textInput,
            controlFocused: OpenCodeKeyboardFocusState.shared.hasFocusedControl || (responder != nil && !isAnchor && !textInput),
            interruptAvailable: interrupts.last != nil
        )
        let resolution = OpenCodeEscapeResolver.resolve(context, armedAt: interruptArmedAt, now: now())
        switch resolution.action {
        case .closeOverlay:
            OpenCodeOverlayEscape.dismissNewest()
        case .leaveTextInput:
            if (responder as? OpenCodeEscapeHandlingTextInput)?.handleEscape() != true {
                OpenCodeKeyboardAnchor.takeFocus()
            }
        case .clearFocus:
            OpenCodeKeyboardAnchor.takeFocus()
        case .armInterrupt:
            break
        case .interrupt:
            interrupts.last?.interrupt()
        case .none:
            break
        }
        if resolution.armedAt != nil {
            arm(at: resolution.armedAt)
        } else {
            disarm()
        }
    }

    private func arm(at date: Date?) {
        interruptArmedAt = date
        expiryTask?.cancel()
        expiryTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(OpenCodeEscapeResolver.interruptWindow))
            guard !Task.isCancelled, let self, self.interruptArmedAt == date else { return }
            self.interruptArmedAt = nil
        }
    }

    private func disarm() {
        expiryTask?.cancel()
        expiryTask = nil
        if interruptArmedAt != nil { interruptArmedAt = nil }
    }
}

/// A text input with its own Esc behaviour (the custom answer of a question
/// prompt hands focus back to its option row).
protocol OpenCodeEscapeHandlingTextInput: AnyObject {
    /// Handles Esc while editing; false leaves it to the default (stop editing).
    func handleEscape() -> Bool
}

/// Window-level stack of overlays that Esc closes, newest first: bottom sheets,
/// anchored cards, dialogs, modals, and overflow menus. Overlay hosts carry the
/// Esc command themselves; the app router uses the same stack when focus is
/// elsewhere.
enum OpenCodeOverlayEscape {
    private static var overlays: [(owner: ObjectIdentifier, layer: Int, opener: AnyHashable?, dismiss: () -> Void)] = []
    private static var layerSyncScheduled = false

    static func makeCommand() -> UIKeyCommand {
        let command = UIKeyCommand(
            input: UIKeyCommand.inputEscape,
            modifierFlags: [],
            action: #selector(UIResponder.openCodeDismissNewestOverlay(_:))
        )
        command.wantsPriorityOverSystemBehavior = true
        return command
    }

    /// Registers (or refreshes) a presented overlay; a new one becomes the newest.
    /// `layer` is the overlay host's `OpenCodeOverlayHostingController.layer`.
    static func register(_ owner: AnyObject, layer: Int, dismiss: @escaping () -> Void) {
        let id = ObjectIdentifier(owner)
        if let index = overlays.firstIndex(where: { $0.owner == id }) {
            overlays[index].dismiss = dismiss
            overlays[index].layer = layer
        } else {
            let state = OpenCodeKeyboardFocusState.shared
            overlays.append((id, layer, state.hasFocusedControl ? state.lastFocusedControl : nil, dismiss))
        }
        syncTopLayer()
    }

    static func unregister(_ owner: AnyObject) {
        guard let index = overlays.firstIndex(where: { $0.owner == ObjectIdentifier(owner) }) else { return }
        let opener = overlays.remove(at: index).opener
        syncTopLayer()
        // The overlay's focused control is gone. Nothing takes focus in its place
        // (iPadOS would focus the first control on screen); the next Tab goes back
        // to the control that opened it.
        if let opener { OpenCodeKeyboardFocusState.shared.setNextTabTarget(opener) }
        OpenCodeKeyboardAnchor.clearAutomaticFocus()
    }

    // Hosts register from `updateUIView`; publish on the next turn, outside the SwiftUI update.
    private static func syncTopLayer() {
        guard !layerSyncScheduled else { return }
        layerSyncScheduled = true
        DispatchQueue.main.async {
            layerSyncScheduled = false
            OpenCodeKeyboardFocusState.shared.setTopOverlayLayer(overlays.last?.layer ?? 0)
        }
    }

    static var hasOverlay: Bool { !overlays.isEmpty }

    static func dismissNewest() {
        overlays.last?.dismiss()
    }
}

extension UIResponder {
    @objc fileprivate func openCodeDismissNewestOverlay(_ sender: UIKeyCommand) {
        // Through the router, so a pending interrupt is disarmed too.
        OpenCodeEscapeRouter.shared.handleEscape()
    }
}

/// Hosts a window-level overlay (sheet, anchored card, dialog, menu). It carries
/// the Esc command and can take first responder, so Esc reaches it.
final class OpenCodeOverlayHostingController<Content: View>: UIHostingController<Content> {
    private let escapeCommand = OpenCodeOverlayEscape.makeCommand()
    /// Keyboard focus layer of this overlay's content, see `OpenCodeOverlayLayerTrait`.
    let layer = OpenCodeOverlayLayerTrait.nextLayer()

    override init(rootView: Content) {
        super.init(rootView: rootView)
        traitOverrides[OpenCodeOverlayLayerTrait.self] = layer
    }

    @available(*, unavailable)
    @MainActor required dynamic init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override var canBecomeFirstResponder: Bool { true }

    override var keyCommands: [UIKeyCommand]? {
        [escapeCommand] + (super.keyCommands ?? [])
    }

    // Claim the Esc action explicitly, so the hosting controller's own action
    // filtering cannot drop it on the way up the responder chain.
    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        action == #selector(UIResponder.openCodeDismissNewestOverlay(_:))
            || super.canPerformAction(action, withSender: sender)
    }

    /// Esc reaches the overlay through the responder chain. A field inside it
    /// already routes there; otherwise it takes first responder, unless another
    /// text input still holds it (a sheet that keeps the keyboard over the composer).
    func takeEscapeFocusIfFree() {
        guard !isFirstResponder else { return }
        let current = UIResponder.openCodeCurrentFirstResponder()
        if let view = current as? UIView, view.isDescendant(of: self.view) { return }
        if current is UITextInput { return }
        becomeFirstResponder()
    }
}

/// An invisible view in the main window that holds first responder while nothing
/// else does, so Esc, and Tab into a waiting prompt, still reach the app. Taking
/// it also clears SwiftUI keyboard focus and ends text editing. Only used while a
/// hardware keyboard is connected.
enum OpenCodeKeyboardAnchor {
    private static let anchors = NSHashTable<AnchorView>.weakObjects()
    private static var reclaimScheduled = false

    /// On the next turn, takes first responder if nothing holds it.
    static func reclaimSoon() {
        guard !reclaimScheduled else { return }
        reclaimScheduled = true
        DispatchQueue.main.async {
            reclaimScheduled = false
            guard OpenCodeKeyboardFocusState.shared.hardwareKeyboardConnected,
                  UIResponder.openCodeCurrentFirstResponder() == nil
            else { return }
            keyAnchor?.becomeFirstResponder()
        }
    }

    /// After the focused control goes away, iPadOS focuses the first control on
    /// screen by itself, where a stray Return would press it. Clears that focus, now
    /// and once more after the system has moved it. A text field being typed in
    /// keeps the keyboard.
    static func clearAutomaticFocus() {
        for delay in [0.0, 0.3] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                guard OpenCodeKeyboardFocusState.shared.hardwareKeyboardConnected,
                      OpenCodeTextInputFocusMonitor.shared.focus == .none,
                      !OpenCodeOverlayEscape.hasOverlay
                else { return }
                takeFocus()
            }
        }
    }

    /// Takes first responder now, from any text field or focused control.
    static func takeFocus() {
        guard let anchor = keyAnchor else {
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
            return
        }
        anchor.becomeFirstResponder()
    }

    private static var keyAnchor: AnchorView? {
        let all = anchors.allObjects.filter { $0.window != nil }
        return all.first { $0.window?.isKeyWindow == true } ?? all.first
    }

    fileprivate static func add(_ view: AnchorView) {
        anchors.add(view)
        reclaimSoon()
    }

    class AnchorView: UIView {
        override var canBecomeFirstResponder: Bool { window != nil }

        override var keyCommands: [UIKeyCommand]? {
            [Self.escapeCommand]
        }

        override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
            action == #selector(openCodeAnchorEscape(_:))
                || super.canPerformAction(action, withSender: sender)
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard let window else { return }
            OpenCodeKeyboardAnchor.add(self)
            if !(window.gestureRecognizers ?? []).contains(where: { $0 is OpenCodeTouchObserver }) {
                window.addGestureRecognizer(OpenCodeTouchObserver())
            }
        }

        @objc func openCodeAnchorEscape(_ sender: UIKeyCommand) {
            OpenCodeEscapeRouter.shared.handleEscape()
        }

        private static let escapeCommand: UIKeyCommand = {
            let command = UIKeyCommand(
                input: UIKeyCommand.inputEscape,
                modifierFlags: [],
                action: #selector(openCodeAnchorEscape(_:))
            )
            command.wantsPriorityOverSystemBehavior = true
            return command
        }()

    }
}

/// Esc while a control or text field in the main window has focus. Installed on
/// the window's root view controller, which is on the responder chain of
/// everything in the main window.
enum OpenCodeEscapeKeyCommand {
    static let command: UIKeyCommand = {
        let command = UIKeyCommand(
            input: UIKeyCommand.inputEscape,
            modifierFlags: [],
            action: #selector(UIViewController.openCodePerformEscape(_:))
        )
        command.wantsPriorityOverSystemBehavior = true
        return command
    }()

    static func install(on viewController: UIViewController) {
        if !(viewController.keyCommands?.contains(command) ?? false) {
            viewController.addKeyCommand(command)
        }
    }
}

extension UIViewController {
    @objc fileprivate func openCodePerformEscape(_ sender: UIKeyCommand) {
        OpenCodeEscapeRouter.shared.handleEscape()
    }
}

/// Invisible view in the app root: the keyboard anchor, and the root view
/// controller's Esc command.
struct OpenCodeKeyboardAnchorView: UIViewRepresentable {
    func makeUIView(context: Context) -> RootAnchor {
        RootAnchor()
    }

    func updateUIView(_ uiView: RootAnchor, context: Context) {}

    final class RootAnchor: OpenCodeKeyboardAnchor.AnchorView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let root = window?.rootViewController {
                OpenCodeEscapeKeyCommand.install(on: root)
            }
        }
    }
}

/// Which window overlay a view is drawn in: 0 for the main window content, else
/// the overlay host's layer. Bridged to SwiftUI as `openCodeOverlayLayer`.
struct OpenCodeOverlayLayerTrait: UITraitDefinition {
    static let defaultValue = 0
    private static var lastLayer = 0

    static func nextLayer() -> Int {
        lastLayer += 1
        return lastLayer
    }
}

private struct OpenCodeOverlayLayerKey: EnvironmentKey, UITraitBridgedEnvironmentKey {
    static let defaultValue = 0

    static func read(from traitCollection: UITraitCollection) -> Int {
        traitCollection[OpenCodeOverlayLayerTrait.self]
    }

    static func write(to mutableTraits: inout UIMutableTraits, value: Int) {
        mutableTraits[OpenCodeOverlayLayerTrait.self] = value
    }
}

extension EnvironmentValues {
    var openCodeOverlayLayer: Int {
        get { self[OpenCodeOverlayLayerKey.self] }
        set { self[OpenCodeOverlayLayerKey.self] = newValue }
    }
}

/// Notes every touch or pointer click in the window without taking part in it:
/// it fails at once and never delays or cancels touches.
private final class OpenCodeTouchObserver: UIGestureRecognizer, UIGestureRecognizerDelegate {
    init() {
        super.init(target: nil, action: nil)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
        delegate = self
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        OpenCodeKeyboardFocusState.shared.noteTouch()
        state = .failed
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        true
    }
}
