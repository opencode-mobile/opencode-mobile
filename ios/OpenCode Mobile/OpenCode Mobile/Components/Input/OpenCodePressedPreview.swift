import SwiftUI

/// Gallery and captures: draws controls in their pressed look without a touch. Press styles read
/// it next to `configuration.isPressed`; in the app it is always `.none`.
enum OpenCodePressedPreview: Equatable {
    case none
    /// Every control inside looks pressed.
    case all
    /// Only the named part of a multi-part control, such as a split button's `menu` half or a
    /// hunk navigation pill's `next` button.
    case part(String)

    func matches(_ part: String? = nil) -> Bool {
        switch self {
        case .none: false
        case .all: true
        case let .part(name): name == part
        }
    }
}

extension View {
    func openCodePressedPreview(_ preview: OpenCodePressedPreview = .all) -> some View {
        environment(\.openCodePressedPreview, preview)
    }
}

private struct OpenCodePressedPreviewKey: EnvironmentKey {
    static let defaultValue = OpenCodePressedPreview.none
}

extension EnvironmentValues {
    var openCodePressedPreview: OpenCodePressedPreview {
        get { self[OpenCodePressedPreviewKey.self] }
        set { self[OpenCodePressedPreviewKey.self] = newValue }
    }
}
