import SwiftUI

private let settleSwipeActionWidth: CGFloat = 84
private let settleSwipeOpenSlop: CGFloat = 56
private let settleSwipeCommitExtra: CGFloat = 36

/// Reveals `gestures` from the trailing edge. A long swipe commits the primary
/// action, which is the first gesture. An empty list draws `content` unchanged
/// so OpenCode rows stay free of settle chrome.
struct SettleSwipeRow<Content: View>: View {
    let gestures: [HomeSettleGesture]
    let onGesture: (HomeSettleGesture) -> Void
    @ViewBuilder let content: () -> Content

    @State private var offset: CGFloat = 0

    var body: some View {
        if gestures.isEmpty {
            content()
        } else {
            swipeBody
        }
    }

    private var revealedWidth: CGFloat { settleSwipeActionWidth * CGFloat(gestures.count) }

    private var swipeBody: some View {
        ZStack(alignment: .trailing) {
            HStack(spacing: 0) {
                ForEach(Array(gestures.enumerated()), id: \.offset) { index, gesture in
                    let primary = index == 0
                    Button {
                        offset = 0
                        onGesture(gesture)
                    } label: {
                        VStack(spacing: 4) {
                            ZStack {
                                Circle()
                                    .fill(primary ? OpenCodeColors.buttonPrimaryBase : OpenCodeColors.textStrong.opacity(0.14))
                                    .frame(width: 36, height: 36)
                                settleSwipeMark(gesture, primary: primary)
                            }
                            Text(HomeSettleFeed.gestureLabel(gesture))
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(OpenCodeColors.textBase)
                        }
                        .frame(width: settleSwipeActionWidth)
                        .frame(maxHeight: .infinity)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(HomeSettleFeed.gestureLabel(gesture))
                }
            }
            content()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(OpenCodeColors.backgroundBase)
                .offset(x: offset)
                .simultaneousGesture(drag)
        }
        .background(OpenCodeColors.backgroundBase)
        .clipped()
    }

    @ViewBuilder
    private func settleSwipeMark(_ gesture: HomeSettleGesture, primary: Bool) -> some View {
        let tint = primary ? OpenCodeColors.iconInvertBase : OpenCodeColors.textStrong
        switch gesture {
        case .snooze:
            SettleClockMark()
                .foregroundStyle(tint)
                .frame(width: 16, height: 16)
        case .settle, .wake:
            OpenCodeIconImage(icon: .check, size: 16)
                .foregroundStyle(tint)
        case .unsettle:
            OpenCodeIconImage(icon: .arrowBack, size: 16)
                .foregroundStyle(tint)
        }
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 18, coordinateSpace: .local)
            .onChanged { value in
                guard abs(value.translation.width) > abs(value.translation.height) else { return }
                let next = min(0, value.translation.width)
                offset = max(next, -(revealedWidth + settleSwipeCommitExtra))
            }
            .onEnded { _ in
                let commitAt = -(revealedWidth + settleSwipeCommitExtra)
                if offset <= commitAt {
                    if let primary = gestures.first {
                        onGesture(primary)
                    }
                    offset = 0
                } else if offset <= -settleSwipeOpenSlop {
                    offset = -revealedWidth
                } else {
                    offset = 0
                }
            }
    }
}

/// Clock from the settle prototype: a ring and two hands, stroked in the current foreground.
private struct SettleClockMark: View {
    var body: some View {
        Canvas { context, size in
            let scale = min(size.width, size.height) / 16
            let style = StrokeStyle(lineWidth: 1.6 * scale, lineCap: .round, lineJoin: .round)
            let circle = Path(ellipseIn: CGRect(x: (8 - 5.2) * scale, y: (8 - 5.2) * scale, width: 10.4 * scale, height: 10.4 * scale))
            context.stroke(circle, with: .foreground, style: style)
            var hands = Path()
            hands.move(to: CGPoint(x: 8 * scale, y: 5.2 * scale))
            hands.addLine(to: CGPoint(x: 8 * scale, y: 8 * scale))
            hands.addLine(to: CGPoint(x: 10 * scale, y: 9.4 * scale))
            context.stroke(hands, with: .foreground, style: style)
        }
    }
}
