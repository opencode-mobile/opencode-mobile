import Observation
import SwiftUI

/// Counts content splits currently inside a sidebar split. The count is transient:
/// collapsing the sidebar for a nested split must not be written to preferences.
@Observable
final class OpenCodeSplitNesting {
    private(set) var contentSplitCount = 0

    var isContentSplitVisible: Bool {
        contentSplitCount > 0
    }

    func enter() {
        contentSplitCount += 1
    }

    func leave() {
        contentSplitCount = max(0, contentSplitCount - 1)
    }
}

private struct OpenCodeSplitNestingKey: EnvironmentKey {
    static let defaultValue: OpenCodeSplitNesting? = nil
}

extension EnvironmentValues {
    var openCodeSplitNesting: OpenCodeSplitNesting? {
        get { self[OpenCodeSplitNestingKey.self] }
        set { self[OpenCodeSplitNestingKey.self] = newValue }
    }
}

/// Registers this view as a visible nested content split for the duration it is mounted.
struct OpenCodeNestedContentSplitRegistration: View {
    @Environment(\.openCodeSplitNesting) private var nesting

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .accessibilityHidden(true)
            .onAppear { nesting?.enter() }
            .onDisappear { nesting?.leave() }
    }
}
