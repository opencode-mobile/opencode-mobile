import CoreGraphics
import SwiftUI

private let scrollToEndScrollSettleMilliseconds = 200

enum ScrollToEndVisibility {
    private static let minRevealThreshold: CGFloat = 120
    private static let viewportRevealFraction: CGFloat = 0.2

    static func revealThreshold(viewportHeight: CGFloat) -> CGFloat {
        max(minRevealThreshold, viewportHeight * viewportRevealFraction)
    }

    static func shouldShow(
        scrollDistanceFromBottom: CGFloat,
        viewportHeight: CGFloat,
        isUserScrolling: Bool
    ) -> Bool {
        guard !isUserScrolling else { return false }

        return scrollDistanceFromBottom > revealThreshold(viewportHeight: viewportHeight)
    }
}

enum ScrollToEndUserScrollClassifier {
    static func isUserDrivenOffsetChange(
        contentOffsetChanged: Bool,
        contentSizeChanged: Bool,
        containerSizeChanged: Bool,
        isProgrammaticScrolling: Bool
    ) -> Bool {
        guard contentOffsetChanged, !isProgrammaticScrolling else { return false }
        return !contentSizeChanged && !containerSizeChanged
    }
}

private struct ScrollToEndScrollSnapshot: Equatable {
    let distanceFromBottom: CGFloat
    let contentOffsetY: CGFloat
    let contentHeight: CGFloat
    let containerHeight: CGFloat
}

struct ScrollToEndScrollObserver: ViewModifier {
    @Binding var distanceFromBottom: CGFloat
    @Binding var isUserScrolling: Bool
    let scrollPositionID: String?
    let isProgrammaticScrolling: () -> Bool

    @State private var scrollEndTask: Task<Void, Never>?

    func body(content: Content) -> some View {
        if #available(iOS 18, *) {
            content
                .onScrollGeometryChange(for: ScrollToEndScrollSnapshot.self) { geometry in
                    ScrollToEndScrollSnapshot(
                        distanceFromBottom: max(
                            0,
                            geometry.contentSize.height - geometry.contentOffset.y - geometry.containerSize.height
                        ),
                        contentOffsetY: geometry.contentOffset.y,
                        contentHeight: geometry.contentSize.height,
                        containerHeight: geometry.containerSize.height
                    )
                } action: { previous, current in
                    distanceFromBottom = current.distanceFromBottom
                    if ScrollToEndUserScrollClassifier.isUserDrivenOffsetChange(
                        contentOffsetChanged: previous.contentOffsetY != current.contentOffsetY,
                        contentSizeChanged: previous.contentHeight != current.contentHeight,
                        containerSizeChanged: previous.containerHeight != current.containerHeight,
                        isProgrammaticScrolling: isProgrammaticScrolling()
                    ) {
                        markUserScrolling()
                    }
                }
        } else {
            content
                .onChange(of: scrollPositionID) { oldValue, newValue in
                    guard oldValue != newValue else { return }
                    markUserScrollingIfNeeded()
                }
        }
    }

    private func markUserScrollingIfNeeded() {
        guard !isProgrammaticScrolling() else { return }
        markUserScrolling()
    }

    private func markUserScrolling() {
        scrollEndTask?.cancel()
        isUserScrolling = true
        scrollEndTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(scrollToEndScrollSettleMilliseconds))
            guard !Task.isCancelled else { return }
            isUserScrolling = false
        }
    }
}

extension View {
    func scrollToEndScrollObserver(
        distanceFromBottom: Binding<CGFloat>,
        isUserScrolling: Binding<Bool>,
        scrollPositionID: String?,
        isProgrammaticScrolling: @escaping () -> Bool = { false }
    ) -> some View {
        modifier(
            ScrollToEndScrollObserver(
                distanceFromBottom: distanceFromBottom,
                isUserScrolling: isUserScrolling,
                scrollPositionID: scrollPositionID,
                isProgrammaticScrolling: isProgrammaticScrolling
            )
        )
    }
}
