#if DEBUG
import SwiftUI

/// One state of a component, as the HTML design system gallery names it in
/// `scripts/component-gallery/captures/contract.json`.
struct ComponentCaptureState: Identifiable {
    let id: String
    /// Fixed content height for overlays and full-screen components; nil for natural height.
    let height: CGFloat?
    /// Where the native state differs from the contract's props, recorded in the manifest.
    let note: String?
    /// The state is a focused text field: keyboard focus stays, the caret is hidden, and the
    /// keyboard sits below the tile.
    let focused: Bool
    /// Extra wait before the crop, for states that appear after a delay.
    let delay: Duration
    /// The control takes real keyboard focus on appear, with a hardware keyboard treated as
    /// connected, so the app's own focus ring policy draws the ring.
    let keyboardFocus: Bool
    let content: () -> AnyView

    init(
        _ id: String,
        height: CGFloat? = nil,
        note: String? = nil,
        focused: Bool = false,
        delay: Duration = .zero,
        keyboardFocus: Bool = false,
        @ViewBuilder content: @escaping () -> some View
    ) {
        self.id = id
        self.height = height
        self.note = note
        self.focused = focused
        self.delay = delay
        self.keyboardFocus = keyboardFocus
        self.content = { AnyView(content()) }
    }
}

/// A contract state iOS does not draw, with the reason recorded in the capture manifest.
struct ComponentCaptureSkip: Identifiable {
    let id: String
    let reason: String
}

/// How a component sits inside its full-width capture tile; matches the HTML cell's `align`.
enum ComponentCaptureAlignment {
    /// Natural width, centred.
    case center
    /// Fills the tile's width.
    case stretch
}

struct ComponentCaptureEntry {
    /// The contract's component id, such as `filter-tabs`.
    let componentID: String
    var alignment: ComponentCaptureAlignment = .stretch
    var states: [ComponentCaptureState]
    var skipped: [ComponentCaptureSkip] = []
}

/// A contract component with no iOS counterpart at all.
struct ComponentCaptureUnavailable {
    let componentID: String
    let reason: String
}

/// Debug-only states the native capture test renders, keyed by contract component id. Adding a
/// component is adding an entry to one of the category lists; the capture screen and UI test
/// pick it up.
enum ComponentCaptureRegistry {
    static let entries: [ComponentCaptureEntry] =
        controlsEntries + listsEntries + navigationEntries + chatEntries + sessionEntries
            + diffsEntries + foundationsEntries

    static let unavailable: [ComponentCaptureUnavailable] = [
        .init(componentID: "text-size-slider", reason: "Android only: iOS uses the Step Slider"),
        .init(componentID: "picker-card", reason: "Android only"),
    ]

    static func entry(for componentID: String) -> ComponentCaptureEntry? {
        entries.first { $0.componentID == componentID }
    }

    /// The run's plan for the UI test: which requested components have tiles, and every skip
    /// and note it should write to the manifest.
    static func metaJSON(for componentIDs: [String]) -> String {
        var registered: [String] = []
        var skipped: [[String: String]] = []
        var notes: [[String: String]] = []
        for id in componentIDs {
            if let entry = entry(for: id) {
                registered.append(id)
                skipped += entry.skipped.map { ["component": id, "state": $0.id, "reason": $0.reason] }
                notes += entry.states.compactMap { state in
                    state.note.map { ["component": id, "state": state.id, "note": $0] }
                }
            } else if let missing = unavailable.first(where: { $0.componentID == id }) {
                skipped.append(["component": id, "state": "*", "reason": missing.reason])
            }
        }
        let payload: [String: Any] = ["registered": registered, "skipped": skipped, "notes": notes]
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
              let json = String(data: data, encoding: .utf8)
        else { return "{}" }
        return json
    }
}
#endif
