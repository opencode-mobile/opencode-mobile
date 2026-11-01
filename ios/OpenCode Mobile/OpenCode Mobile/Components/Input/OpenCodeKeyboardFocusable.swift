import Observation
import SwiftUI

/// Outline of the focus ring, matched to the control's own shape.
enum OpenCodeFocusRingShape {
    /// Drawn just outside the control.
    case roundedRectangle(cornerRadius: CGFloat)
    case capsule
    case circle
    /// Drawn inside the control's frame, for full-width rows whose edges meet the
    /// screen or a scroll view's clip.
    case insetRoundedRectangle(cornerRadius: CGFloat, horizontalInset: CGFloat = 0, verticalInset: CGFloat = 0)
}

/// The one focus indication of the design system: a 2 pt ring in the interactive
/// color around the control that has hardware-keyboard focus (Android: the Material 3
/// focus indication mapped to the same token).
enum OpenCodeFocusRing {
    static let lineWidth: CGFloat = 2
    /// Gap between an outside ring and the control.
    static let outset: CGFloat = 3
    static var color: Color { OpenCodeColors.interactive }
}

extension View {
    /// Makes a design-system control a hardware-keyboard focus target: it is a Tab
    /// stop (skipped while disabled), shows the focus ring while focused, and Return
    /// or Space runs `activate`. Inside `openCodeFocusGroup` the arrow keys move
    /// between members and only one member is a Tab stop.
    ///
    /// Apply it inside components, never per screen.
    func openCodeKeyboardFocusable(
        _ ring: OpenCodeFocusRingShape,
        enabled: Bool = true,
        activate: (() -> Void)?
    ) -> some View {
        modifier(OpenCodeKeyboardFocusableModifier(ring: ring, enabled: enabled, activate: activate))
    }

    /// Groups the focusable controls inside (a list, a row of buttons, prompt
    /// options): Tab enters at the last focused member, arrows along `axis` move
    /// between members, Tab again leaves the group.
    func openCodeFocusGroup(_ axis: OpenCodeFocusGroupAxis) -> some View {
        modifier(OpenCodeFocusGroupModifier(axis: axis))
    }

    /// Joins the focusable controls inside to an existing group, for groups that
    /// span separately hosted views (collection view cells).
    func openCodeFocusGroup(_ model: OpenCodeFocusGroupModel) -> some View {
        environment(\.openCodeFocusGroup, model)
    }

    /// Names the next focusable control inside this view, so keyboard rules can
    /// refer to it (a prompt's entry control, the next Tab stop after a field).
    func openCodeFocusKey<Key: Hashable>(_ key: Key) -> some View {
        environment(\.openCodeFocusKey, AnyHashable(key))
    }

    /// Lets code inside see whether, and which, control in it has keyboard focus.
    func openCodeFocusRequester(_ requester: OpenCodeFocusRequester) -> some View {
        environment(\.openCodeFocusRequester, requester)
    }

    /// Gallery and previews: draws the focus ring without keyboard focus.
    func openCodeFocusRingPreview(_ shown: Bool = true) -> some View {
        environment(\.openCodeFocusRingPreview, shown)
    }
}

/// Reports which control inside it has keyboard focus. (iPadOS SwiftUI cannot
/// move focus in code while nothing, or a UIKit text field, is focused, so moving
/// focus to a named control goes through `OpenCodeKeyboardFocusState` Tab rules.)
@Observable
final class OpenCodeFocusRequester {
    /// The control that has focus, if any (its name, or an internal key).
    fileprivate(set) var focusedKey: AnyHashable?

    fileprivate func setFocused(_ key: AnyHashable, focused: Bool) {
        if focused {
            if focusedKey != key { focusedKey = key }
        } else if focusedKey == key {
            focusedKey = nil
        }
    }
}

/// Members of one `openCodeFocusGroup`. Frames are not observed: they change on
/// every scroll and only matter when an arrow key is pressed.
@Observable
final class OpenCodeFocusGroupModel {
    let axis: OpenCodeFocusGroupAxis
    /// The member Tab lands on. Observed, so only that member is focusable.
    private(set) var tabStopKey: AnyHashable?
    /// A member an arrow key moved to; it takes focus and clears this.
    fileprivate(set) var pendingFocusKey: AnyHashable?

    @ObservationIgnored private var frames: [AnyHashable: CGRect] = [:]
    @ObservationIgnored private var registration: [AnyHashable] = []
    @ObservationIgnored private var activeKey: AnyHashable?

    init(axis: OpenCodeFocusGroupAxis) {
        self.axis = axis
    }

    fileprivate func register(_ key: AnyHashable) {
        if !registration.contains(key) { registration.append(key) }
        refreshTabStop()
    }

    fileprivate func unregister(_ key: AnyHashable) {
        registration.removeAll { $0 == key }
        frames[key] = nil
        refreshTabStop()
    }

    fileprivate func updateFrame(_ key: AnyHashable, frame: CGRect) {
        frames[key] = frame
    }

    fileprivate func didFocus(_ key: AnyHashable) {
        activeKey = key
        refreshTabStop()
    }

    /// Makes `key` the Tab stop ahead of focusing it programmatically.
    fileprivate func activate(_ key: AnyHashable) {
        activeKey = key
        refreshTabStop()
    }

    fileprivate func consumePendingFocus(_ key: AnyHashable) {
        if pendingFocusKey == key { pendingFocusKey = nil }
    }

    /// Handles an arrow key from the focused member; false lets the key through.
    fileprivate func move(from key: AnyHashable, arrow: OpenCodeArrowKey) -> Bool {
        switch OpenCodeFocusGroupNavigation.move(from: key, orderedIDs: orderedKeys, key: arrow, axis: axis) {
        case let .focus(target):
            activate(target)
            pendingFocusKey = target
            return true
        case .stay:
            return true
        case .ignore:
            return false
        }
    }

    /// Members in reading order along the group's axis.
    private var orderedKeys: [AnyHashable] {
        registration.enumerated().sorted { lhs, rhs in
            let a = frames[lhs.element]
            let b = frames[rhs.element]
            guard let a, let b else { return lhs.offset < rhs.offset }
            switch axis {
            case .vertical:
                if abs(a.minY - b.minY) > 1 { return a.minY < b.minY }
                return a.minX < b.minX
            case .horizontal:
                if abs(a.minX - b.minX) > 1 { return a.minX < b.minX }
                return a.minY < b.minY
            }
        }
        .map(\.element)
    }

    private func refreshTabStop() {
        let next = OpenCodeFocusGroupNavigation.tabStop(activeID: activeKey, orderedIDs: orderedKeys)
        if next != tabStopKey { tabStopKey = next }
    }
}

private struct OpenCodeFocusGroupModifier: ViewModifier {
    // Created up front: members register in their own onAppear, which can run
    // before this view's.
    @State private var model: OpenCodeFocusGroupModel

    init(axis: OpenCodeFocusGroupAxis) {
        _model = State(initialValue: OpenCodeFocusGroupModel(axis: axis))
    }

    func body(content: Content) -> some View {
        content.environment(\.openCodeFocusGroup, model)
    }
}

private struct OpenCodeKeyboardFocusableModifier: ViewModifier {
    let ring: OpenCodeFocusRingShape
    let enabled: Bool
    let activate: (() -> Void)?

    @Environment(\.openCodeFocusGroup) private var group
    @Environment(\.openCodeFocusRequester) private var requester
    @Environment(\.openCodeFocusKey) private var explicitKey
    @Environment(\.openCodeFocusRingPreview) private var ringPreview
    @Environment(\.openCodeOverlayLayer) private var overlayLayer
    @FocusState private var isFocused: Bool
    @State private var autoKey = UUID()

    private var key: AnyHashable { explicitKey ?? AnyHashable(autoKey) }

    private var isTabStop: Bool {
        guard enabled else { return false }
        let focusState = OpenCodeKeyboardFocusState.shared
        // Behind an open sheet, dialog, or menu, nothing is a Tab stop.
        guard focusState.topOverlayLayer == overlayLayer else { return false }
        // A waiting prompt's entry control is the only Tab stop while nothing has focus.
        if let restriction = focusState.tabRestriction { return key == restriction }
        guard let group else { return true }
        return group.tabStopKey == nil || group.tabStopKey == key
    }

    func body(content: Content) -> some View {
        content
            // Controls nested inside this one (a row's trailing button) are not
            // named by this key or members of this group.
            .environment(\.openCodeFocusKey, nil)
            .environment(\.openCodeFocusGroup, nil)
            .overlay {
                if ringPreview || OpenCodeFocusRingPolicy.isVisible(
                    focused: isFocused,
                    hardwareKeyboardConnected: OpenCodeKeyboardFocusState.shared.hardwareKeyboardConnected,
                    lastInputWasKeyboard: OpenCodeKeyboardFocusState.shared.lastInputWasKeyboard
                ) {
                    ringView.allowsHitTesting(false)
                }
            }
            // Not `interactions: .activate`: on iPadOS without Full Keyboard Access
            // such views are never reached by Tab (checked on an iPadOS 26.5
            // simulator). Touch focus hides the ring instead (`OpenCodeFocusRingPolicy`).
            .focusable(isTabStop)
            .focusEffectDisabled()
            .focused($isFocused)
            // Key down only: holding Return or Space does not activate again.
            .onKeyPress(.return, phases: .down) { _ in performActivate() }
            .onKeyPress(.space, phases: .down) { _ in performActivate() }
            .onKeyPress(keys: [.upArrow, .downArrow, .leftArrow, .rightArrow], phases: [.down, .repeat]) { press in
                OpenCodeKeyboardFocusState.shared.noteKeyPress(navigation: true)
                guard let group, let arrow = OpenCodeArrowKey(press.key) else { return .ignored }
                return group.move(from: key, arrow: arrow) ? .handled : .ignored
            }
            .onGeometryChange(for: CGRect.self) { proxy in
                proxy.frame(in: .global)
            } action: { frame in
                group?.updateFrame(key, frame: frame)
            }
            .onAppear {
                if enabled { group?.register(key) }
                focusIfRequested()
            }
            .onDisappear {
                group?.unregister(key)
                requester?.setFocused(key, focused: false)
                OpenCodeKeyboardFocusState.shared.controlFocusChanged(key, focused: false)
            }
            .onChange(of: enabled) { _, enabled in
                if enabled {
                    group?.register(key)
                } else {
                    group?.unregister(key)
                }
            }
            .onChange(of: isFocused) { _, focused in
                if focused { group?.didFocus(key) }
                requester?.setFocused(key, focused: focused)
                OpenCodeKeyboardFocusState.shared.controlFocusChanged(key, focused: focused)
            }
            .onChange(of: group?.pendingFocusKey) { _, _ in focusIfRequested() }
    }

    private func performActivate() -> KeyPress.Result {
        OpenCodeKeyboardFocusState.shared.noteKeyPress(navigation: false)
        guard enabled, let activate else { return .ignored }
        activate()
        return .handled
    }

    /// An arrow key in the group moved focus here (focus is already on a sibling,
    /// so SwiftUI can move it in code).
    private func focusIfRequested() {
        guard group?.pendingFocusKey == key, enabled else { return }
        group?.consumePendingFocus(key)
        group?.activate(key)
        // Focusable flips on with the Tab stop; focus on the next turn, once it has.
        DispatchQueue.main.async { isFocused = true }
    }

    @ViewBuilder
    private var ringView: some View {
        let stroke = OpenCodeFocusRing.color
        let width = OpenCodeFocusRing.lineWidth
        let outset = OpenCodeFocusRing.outset
        switch ring {
        case let .roundedRectangle(cornerRadius):
            RoundedRectangle(cornerRadius: cornerRadius + outset, style: .continuous)
                .strokeBorder(stroke, lineWidth: width)
                .padding(-outset)
        case .capsule:
            Capsule().strokeBorder(stroke, lineWidth: width).padding(-outset)
        case .circle:
            Circle().strokeBorder(stroke, lineWidth: width).padding(-outset)
        case let .insetRoundedRectangle(cornerRadius, horizontalInset, verticalInset):
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .strokeBorder(stroke, lineWidth: width)
                .padding(.horizontal, horizontalInset)
                .padding(.vertical, verticalInset)
        }
    }
}

private extension OpenCodeArrowKey {
    init?(_ key: KeyEquivalent) {
        switch key {
        case .upArrow: self = .up
        case .downArrow: self = .down
        case .leftArrow: self = .left
        case .rightArrow: self = .right
        default: return nil
        }
    }
}

private struct OpenCodeFocusGroupKey: EnvironmentKey {
    static let defaultValue: OpenCodeFocusGroupModel? = nil
}

private struct OpenCodeFocusRequesterKey: EnvironmentKey {
    static let defaultValue: OpenCodeFocusRequester? = nil
}

private struct OpenCodeFocusKeyKey: EnvironmentKey {
    static let defaultValue: AnyHashable? = nil
}

private struct OpenCodeFocusRingPreviewKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    fileprivate var openCodeFocusGroup: OpenCodeFocusGroupModel? {
        get { self[OpenCodeFocusGroupKey.self] }
        set { self[OpenCodeFocusGroupKey.self] = newValue }
    }

    fileprivate var openCodeFocusRequester: OpenCodeFocusRequester? {
        get { self[OpenCodeFocusRequesterKey.self] }
        set { self[OpenCodeFocusRequesterKey.self] = newValue }
    }

    fileprivate var openCodeFocusKey: AnyHashable? {
        get { self[OpenCodeFocusKeyKey.self] }
        set { self[OpenCodeFocusKeyKey.self] = newValue }
    }

    fileprivate var openCodeFocusRingPreview: Bool {
        get { self[OpenCodeFocusRingPreviewKey.self] }
        set { self[OpenCodeFocusRingPreviewKey.self] = newValue }
    }
}

#Preview("Focus ring") {
    VStack(spacing: 20) {
        OpenCodeButton(variant: .compactText(title: "Allow once"), action: {})
            .openCodeFocusRingPreview()
        OpenCodeButton(variant: .iconCircle(.arrowUpward, accessibilityLabel: "Send"), contrast: .accent, action: {})
            .openCodeFocusRingPreview()
        OpenCodeListItem(title: "Minimal patch", subtitle: "Touch only the failing path", style: .card, action: {})
            .openCodeFocusRingPreview()
    }
    .padding()
    .openCodeTheme()
}
