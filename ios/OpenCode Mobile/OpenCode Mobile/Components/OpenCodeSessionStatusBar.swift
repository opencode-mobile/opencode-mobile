import SwiftUI
import UIKit

enum OpenCodeSessionConnectionStatus: String, Equatable, Sendable, CaseIterable {
    case live
    case connecting
    case offline
}

struct OpenCodeSessionStatusBar: View {
    let sessionID: String
    let connectionStatus: OpenCodeSessionConnectionStatus
    let contextUsageFraction: Double?
    let onCopySessionID: () -> Void
    let onConnectionClick: () -> Void
    let onContextClick: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            HStack(spacing: 4) {
                Text("Session: \(sessionID.suffix(Self.visibleSessionIDCharacters))")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .lineLimit(1)
                    .truncationMode(.middle)

                Button(action: onCopySessionID) {
                    Image(OpenCodeIcon.contentCopy.rawValue)
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 13, height: 13)
                        .frame(width: Self.touchSize, height: Self.touchSize)
                }
                .buttonStyle(OpenCodeSessionStatusCircleButtonStyle())
                .accessibilityLabel("Copy session ID")
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button(action: onConnectionClick) {
                HStack(alignment: .center, spacing: 4) {
                    Text(connectionStatus.rawValue)
                        .font(OpenCodeTypography.caption)
                        .lineLimit(1)
                    Circle()
                        .fill(connectionStatus.dotColor)
                        .frame(width: 6, height: 6)
                }
                .frame(height: Self.touchSize)
                .padding(.horizontal, 6)
            }
            .buttonStyle(OpenCodeSessionStatusCapsuleButtonStyle())
            .accessibilityLabel("Live updates \(connectionStatus.rawValue)")
            .padding(.leading, 10)

            Button(action: onContextClick) {
                ContextUsageRing(fraction: contextUsageFraction)
                    .frame(width: Self.touchSize, height: Self.touchSize)
            }
            .buttonStyle(OpenCodeSessionStatusCircleButtonStyle())
            .accessibilityLabel(contextAccessibilityLabel)
            .padding(.leading, 6)
        }
        .frame(height: 36)
        .padding(.horizontal, 16)
    }

    private var contextAccessibilityLabel: String {
        guard let contextUsageFraction else { return "Context usage unavailable" }
        return "Context usage \(Int(contextUsageFraction * 100)) percent"
    }

    private static let visibleSessionIDCharacters = 9
    private static let touchSize: CGFloat = 22
}

private struct ContextUsageRing: View {
    let fraction: Double?

    var body: some View {
        ZStack {
            Circle()
                .stroke(OpenCodeColors.borderWeakBase, lineWidth: 1.5)
            if let fraction {
                Circle()
                    .trim(from: 0, to: fraction)
                    .stroke(OpenCodeColors.textInteractiveBase, style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
        }
        .frame(width: 14, height: 14)
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

private struct OpenCodeSessionStatusCapsuleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(OpenCodeColors.textBase)
            .background(
                Capsule()
                    .fill(configuration.isPressed ? OpenCodeColors.surfaceBaseHover : Color.clear)
            )
            .contentShape(Capsule())
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

private extension OpenCodeSessionConnectionStatus {
    var dotColor: Color {
        switch self {
        case .live:
            OpenCodeColors.success
        case .connecting:
            OpenCodeColors.warning
        case .offline:
            OpenCodeColors.iconWeakBase
        }
    }
}

#Preview {
    OpenCodeSessionStatusBar(
        sessionID: "ses_01HZX9TvS3EfE4",
        connectionStatus: .live,
        contextUsageFraction: 0.42,
        onCopySessionID: {},
        onConnectionClick: {},
        onContextClick: {}
    )
    .openCodeTheme()
}
