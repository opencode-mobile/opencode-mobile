import SwiftUI

/// Line and bar colour of a chart.
enum OpenCodeChartTone: Equatable, Sendable {
    case success
    case warning
    case error
    case interactive

    var color: Color {
        switch self {
        case .success: OpenCodeColors.success
        case .warning: OpenCodeColors.warning
        case .error: OpenCodeColors.error
        case .interactive: OpenCodeColors.interactive
        }
    }
}

/// Shared chart drawing helpers.
enum OpenCodeChartMetrics {
    static let lineWidth: CGFloat = 1.5
    static let unobservedTint: Double = 0.05
    static let unobservedStripe: Double = 0.10

    /// Rounds up to 1, 2, 2.5 or 5 times a power of ten.
    static func niceCeiling(_ value: Double) -> Double {
        guard value > 0, value.isFinite else { return 0 }
        let magnitude = pow(10, floor(log10(value)))
        for step in [1.0, 2.0, 2.5, 5.0, 10.0] where step * magnitude >= value - 1e-9 {
            return step * magnitude
        }
        return 10 * magnitude
    }

    /// Diagonal stripes over `rect`, for time that was not measured.
    static func drawUnobserved(in rect: CGRect, context: inout GraphicsContext) {
        context.fill(Path(rect), with: .color(OpenCodeColors.textStrong.opacity(unobservedTint)))
        var stripes = Path()
        let spacing: CGFloat = 5
        var x = rect.minX - rect.height
        while x < rect.maxX {
            stripes.move(to: CGPoint(x: x, y: rect.maxY))
            stripes.addLine(to: CGPoint(x: x + rect.height, y: rect.minY))
            x += spacing
        }
        var clipped = context
        clipped.clip(to: Path(rect))
        clipped.stroke(stripes, with: .color(OpenCodeColors.textStrong.opacity(unobservedStripe)), lineWidth: 1)
    }
}
