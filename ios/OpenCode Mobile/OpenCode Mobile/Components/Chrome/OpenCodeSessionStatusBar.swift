import SwiftUI
import UIKit

struct OpenCodeSessionStatusBar: View {
    let contextUsageFraction: Double?
    let onContextClick: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Spacer(minLength: 0)

            Button(action: onContextClick) {
                ContextUsageRing(fraction: contextUsageFraction)
                    .frame(width: Self.touchSize, height: Self.touchSize)
            }
            .buttonStyle(OpenCodeSessionStatusCircleButtonStyle())
            .accessibilityLabel(contextAccessibilityLabel)
            .openCodeKeyboardFocusable(.circle, activate: onContextClick)
        }
        .frame(height: 36)
        .padding(.horizontal, 16)
    }

    private var contextAccessibilityLabel: String {
        guard let contextUsageFraction else { return "Context usage unavailable" }
        return "Context usage \(Int(contextUsageFraction * 100)) percent"
    }

    private static let touchSize: CGFloat = 22
}

private struct ContextUsageRing: View {
    let fraction: Double?

    var body: some View {
        ZStack {
            Circle()
                .stroke(OpenCodeColors.iconBase, lineWidth: 1.5)
            if let fraction {
                Circle()
                    .trim(from: 0, to: fraction)
                    .stroke(contextUsageColor(for: fraction), style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
        }
        .frame(width: 14, height: 14)
    }

    private func contextUsageColor(for fraction: Double) -> Color {
        if fraction >= 0.85 {
            return OpenCodeColors.error
        }
        if fraction >= 0.7 {
            return OpenCodeColors.warning
        }
        return OpenCodeColors.iconStrongBase
    }
}

private struct OpenCodeSessionStatusCircleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(OpenCodeColors.iconBase)
            .background(
                Circle()
                    .fill(configuration.isPressed ? OpenCodeColors.surfaceBaseHover : Color.clear)
            )
            .contentShape(Circle())
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

#Preview {
    OpenCodeSessionStatusBar(
        contextUsageFraction: 0.42,
        onContextClick: {}
    )
    .openCodeTheme()
}
