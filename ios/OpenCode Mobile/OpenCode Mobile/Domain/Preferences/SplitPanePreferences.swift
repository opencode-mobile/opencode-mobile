import CoreGraphics
import Foundation
import Observation

/// Persists the tablet split-view leading pane width and collapsed state per screen.
@Observable
final class SplitPanePreferences {
    static let shared = SplitPanePreferences()

    private enum StorageKeys {
        static func leadingWidth(_ screen: OpenCodeSplitPaneScreen) -> String {
            "splitPane.\(screen.rawValue).leadingWidth"
        }

        static func isCollapsed(_ screen: OpenCodeSplitPaneScreen) -> String {
            "splitPane.\(screen.rawValue).isCollapsed"
        }
    }

    private let defaults: UserDefaults
    private(set) var states: [OpenCodeSplitPaneScreen: OpenCodeSplitPaneState]

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        var loaded: [OpenCodeSplitPaneScreen: OpenCodeSplitPaneState] = [:]
        for screen in OpenCodeSplitPaneScreen.allCases {
            loaded[screen] = Self.load(screen, from: defaults)
        }
        states = loaded
    }

    func state(for screen: OpenCodeSplitPaneScreen) -> OpenCodeSplitPaneState {
        states[screen] ?? .initial
    }

    func setState(_ state: OpenCodeSplitPaneState, for screen: OpenCodeSplitPaneScreen) {
        let sanitized = OpenCodeSplitPaneState(
            leadingWidth: Self.storedWidth(state.leadingWidth),
            isCollapsed: state.isCollapsed
        )
        states[screen] = sanitized
        defaults.set(Double(sanitized.leadingWidth), forKey: StorageKeys.leadingWidth(screen))
        defaults.set(sanitized.isCollapsed, forKey: StorageKeys.isCollapsed(screen))
    }

    func clear() {
        for screen in OpenCodeSplitPaneScreen.allCases {
            defaults.removeObject(forKey: StorageKeys.leadingWidth(screen))
            defaults.removeObject(forKey: StorageKeys.isCollapsed(screen))
            states[screen] = .initial
        }
    }

    private static func load(_ screen: OpenCodeSplitPaneScreen, from defaults: UserDefaults) -> OpenCodeSplitPaneState {
        let width: CGFloat
        if defaults.object(forKey: StorageKeys.leadingWidth(screen)) == nil {
            width = OpenCodeSplitPaneLayout.defaultLeadingWidth
        } else {
            width = storedWidth(CGFloat(defaults.double(forKey: StorageKeys.leadingWidth(screen))))
        }
        return OpenCodeSplitPaneState(
            leadingWidth: width,
            isCollapsed: defaults.bool(forKey: StorageKeys.isCollapsed(screen))
        )
    }

    /// Every screen keeps its leading pane inside the Home sidebar range.
    private static func storedWidth(_ width: CGFloat) -> CGFloat {
        OpenCodeSplitPaneLayout.clamp(width: width)
    }
}
