import SwiftUI

enum OpenCodeStatusStripSegment: Equatable, Sendable {
    case success
    case warning
    case error
    /// Not measured.
    case unobserved
}

/// A row of equal-width rounded bars, one per time slot, coloured by how that slot went.
struct OpenCodeStatusStrip: View {
    static let height: CGFloat = 14

    let segments: [OpenCodeStatusStripSegment]

    init(segments: [OpenCodeStatusStripSegment]) {
        self.segments = segments
    }

    var body: some View {
        Canvas { context, size in
            let count = segments.count
            guard count > 0, size.width > 0 else { return }
            let gap: CGFloat = count > 70 ? 0.5 : 1
            let width = max(0.5, (size.width - gap * CGFloat(count - 1)) / CGFloat(count))
            let radius = min(2, width / 2)
            for (index, segment) in segments.enumerated() {
                let rect = CGRect(x: CGFloat(index) * (width + gap), y: 0, width: width, height: size.height)
                let path = Path(roundedRect: rect, cornerRadius: radius)
                switch segment {
                case .success:
                    context.fill(path, with: .color(OpenCodeColors.success))
                case .warning:
                    context.fill(path, with: .color(OpenCodeColors.warning))
                case .error:
                    context.fill(path, with: .color(OpenCodeColors.error))
                case .unobserved:
                    var clipped = context
                    clipped.clip(to: path)
                    OpenCodeChartMetrics.drawUnobserved(in: rect, context: &clipped)
                }
            }
        }
        .frame(height: Self.height)
        .accessibilityHidden(true)
    }
}

#Preview("Status strip") {
    OpenCodeStatusStrip(segments: (0 ..< 96).map { index in
        switch index % 23 {
        case 0: .error
        case 1, 2: .warning
        case 10 ..< 14: .unobserved
        default: .success
        }
    })
    .padding()
    .openCodeTheme()
}
