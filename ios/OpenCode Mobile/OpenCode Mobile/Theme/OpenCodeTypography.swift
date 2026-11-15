import SwiftUI
import UIKit

enum OpenCodeTypography {
    static let display = Style.display.font
    static let title = Style.title.font
    static let heading = Style.heading.font
    static let body = Style.body.font
    static let bodySecondary = Style.bodySecondary.font
    static let caption = Style.caption.font
    static let code = Style.code.font
    static let button = Style.button.font

    /// The type ramp. Each style draws at its base size, or at that size times a region's
    /// text scale (`openCodeTextScale`) inside a region that carries one.
    enum Style: CaseIterable, Sendable {
        case display, title, heading, body, bodySecondary, caption, code, button

        var size: CGFloat {
            switch self {
            case .display: 28
            case .title: 20
            case .heading: 16
            case .body: 16
            case .bodySecondary: 14
            case .caption: 13
            case .code: 14
            case .button: 14
            }
        }

        var weight: Font.Weight {
            switch self {
            case .display, .title, .heading, .button: .medium
            case .body, .bodySecondary, .caption, .code: .regular
            }
        }

        var design: Font.Design {
            self == .code ? .monospaced : .default
        }

        var font: Font { font(scale: 1) }

        func font(scale: CGFloat) -> Font {
            Font.system(size: size * scale, weight: weight, design: design)
        }

        /// The UIKit font for text drawn outside SwiftUI (text views, measurement).
        func uiFont(scale: CGFloat, weight: UIFont.Weight? = nil) -> UIFont {
            let size = size * scale
            let resolved = weight ?? uiWeight
            return design == .monospaced
                ? UIFont.monospacedSystemFont(ofSize: size, weight: resolved)
                : UIFont.systemFont(ofSize: size, weight: resolved)
        }

        private var uiWeight: UIFont.Weight {
            switch weight {
            case .medium: .medium
            case .semibold: .semibold
            case .bold: .bold
            default: .regular
            }
        }
    }

    /// The scale a region draws text at: its Appearance text size times the phone's own text size.
    /// Outside a region the app keeps its fixed sizes, so the factor is exactly 1.
    static func textScale(size: AppTextSize, dynamicTypeSize: DynamicTypeSize) -> CGFloat {
        CGFloat(size.factor) * dynamicTypeMultiplier(dynamicTypeSize)
    }

    /// How much the phone's text size enlarges body text, relative to the default Large size.
    /// Capped at the first accessibility size so a dense list stays usable.
    static func dynamicTypeMultiplier(_ dynamicTypeSize: DynamicTypeSize) -> CGFloat {
        let capped = min(dynamicTypeSize, .accessibility1)
        let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(capped))
        let base: CGFloat = 100
        return UIFontMetrics(forTextStyle: .body).scaledValue(for: base, compatibleWith: traits) / base
    }
}

private struct OpenCodeTextScaleKey: EnvironmentKey {
    static let defaultValue: CGFloat = 1
}

extension EnvironmentValues {
    /// Text scale for the region this view is in (#1044); 1 outside any region.
    /// Scale-aware components multiply their type and text-bound metrics by it.
    var openCodeTextScale: CGFloat {
        get { self[OpenCodeTextScaleKey.self] }
        set { self[OpenCodeTextScaleKey.self] = newValue }
    }
}

private struct OpenCodeTextScaleRegion: ViewModifier {
    let region: AppTextRegion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var preferences = AppearancePreferences.shared

    func body(content: Content) -> some View {
        content.environment(
            \.openCodeTextScale,
            OpenCodeTypography.textScale(size: preferences.textSize(for: region), dynamicTypeSize: dynamicTypeSize)
        )
    }
}

private struct OpenCodeScaledFont: ViewModifier {
    let style: OpenCodeTypography.Style
    let weight: Font.Weight?
    @Environment(\.openCodeTextScale) private var scale

    func body(content: Content) -> some View {
        let font = style.font(scale: scale)
        content.font(weight.map { font.weight($0) } ?? font)
    }
}

extension View {
    /// Draws this subtree at `region`'s Appearance text size (#1044).
    func openCodeTextScale(_ region: AppTextRegion) -> some View {
        modifier(OpenCodeTextScaleRegion(region: region))
    }

    /// Draws this subtree at an explicit text size, for samples that preview a size.
    func openCodeTextScale(_ size: AppTextSize) -> some View {
        modifier(OpenCodeFixedTextScale(size: size))
    }

    /// A type-ramp font that follows the region's text scale.
    func openCodeFont(_ style: OpenCodeTypography.Style, weight: Font.Weight? = nil) -> some View {
        modifier(OpenCodeScaledFont(style: style, weight: weight))
    }
}

private struct OpenCodeFixedTextScale: ViewModifier {
    let size: AppTextSize
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func body(content: Content) -> some View {
        content.environment(
            \.openCodeTextScale,
            OpenCodeTypography.textScale(size: size, dynamicTypeSize: dynamicTypeSize)
        )
    }
}

private extension UIContentSizeCategory {
    init(_ size: DynamicTypeSize) {
        switch size {
        case .xSmall: self = .extraSmall
        case .small: self = .small
        case .medium: self = .medium
        case .large: self = .large
        case .xLarge: self = .extraLarge
        case .xxLarge: self = .extraExtraLarge
        case .xxxLarge: self = .extraExtraExtraLarge
        case .accessibility1: self = .accessibilityMedium
        case .accessibility2: self = .accessibilityLarge
        case .accessibility3: self = .accessibilityExtraLarge
        case .accessibility4: self = .accessibilityExtraExtraLarge
        case .accessibility5: self = .accessibilityExtraExtraExtraLarge
        @unknown default: self = .large
        }
    }
}
