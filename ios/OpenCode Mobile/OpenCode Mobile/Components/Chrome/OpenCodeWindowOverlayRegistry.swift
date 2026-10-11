import Observation
import UIKit

/// Window-level overlays (bottom sheets, dialogs, overflow menus) that sit on top
/// of the app's root view. They are added straight to the window instead of being
/// presented, so screens cannot see them through UIKit presentation state.
@Observable
final class OpenCodeWindowOverlayRegistry {
    static let shared = OpenCodeWindowOverlayRegistry()

    @ObservationIgnored private let overlays = NSHashTable<UIView>.weakObjects()
    @ObservationIgnored private var refreshScheduled = false

    /// True while any registered overlay is attached to a window.
    private(set) var hasOverlay = false

    /// Call when an overlay view is attached or shown again (idempotent).
    func attach(_ view: UIView) {
        overlays.add(view)
        scheduleRefresh()
    }

    /// Call when an overlay starts dismissing or is removed.
    func detach(_ view: UIView) {
        overlays.remove(view)
        scheduleRefresh()
    }

    /// Current state, read straight from the views.
    var hasOverlayNow: Bool {
        overlays.allObjects.contains { $0.window != nil }
    }

    // Hosts call attach/detach from `updateUIView`; publish on the next turn so the
    // change does not land in the middle of a SwiftUI update.
    private func scheduleRefresh() {
        guard !refreshScheduled else { return }
        refreshScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            refreshScheduled = false
            let next = hasOverlayNow
            if next != hasOverlay {
                hasOverlay = next
            }
        }
    }
}
