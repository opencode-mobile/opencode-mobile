import SwiftUI
import UIKit

/// Pass-through frame-time HUD. The label updates from the display link so
/// SwiftUI does not rebuild on every frame.
struct PerformanceOverlayHost: View {
    @State private var preferences = PerformanceOverlayPreferences.shared
    @State private var developerTools = DeveloperToolsPreferences.shared

    var body: some View {
        if PerformanceOverlayGate.isVisible(
            developerToolsUnlocked: developerTools.unlocked,
            enabled: preferences.enabled
        ) {
            PerformanceOverlayRepresentable()
                .fixedSize()
                .allowsHitTesting(false)
                .accessibilityHidden(true)
                .accessibilityIdentifier("performanceOverlay")
        }
    }
}

private struct PerformanceOverlayRepresentable: UIViewRepresentable {
    func makeUIView(context: Context) -> PerformanceOverlayUIView {
        PerformanceOverlayUIView()
    }

    func updateUIView(_ uiView: PerformanceOverlayUIView, context: Context) {}

    static func dismantleUIView(_ uiView: PerformanceOverlayUIView, coordinator: ()) {
        uiView.stop()
    }
}

final class PerformanceOverlayUIView: UIView {
    private let label = UILabel()
    private let proxy = DisplayLinkProxy()
    private var displayLink: CADisplayLink?
    private var tracker = FrameTimeTracker()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        accessibilityElementsHidden = true
        backgroundColor = UIColor.black.withAlphaComponent(0.72)
        layer.cornerRadius = 8
        layer.cornerCurve = .continuous
        clipsToBounds = true

        label.font = .monospacedDigitSystemFont(ofSize: 12, weight: .medium)
        label.textColor = .white
        label.numberOfLines = 0
        label.text = FrameTimeSnapshot.idle.label
        addSubview(label)

        proxy.owner = self
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applicationDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applicationWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        displayLink?.invalidate()
    }

    override var intrinsicContentSize: CGSize {
        let labelSize = label.intrinsicContentSize
        return CGSize(width: max(88, labelSize.width + 16), height: labelSize.height + 16)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        label.frame = bounds.insetBy(dx: 8, dy: 8)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil {
            stop()
        } else {
            start()
        }
    }

    func stop() {
        displayLink?.invalidate()
        displayLink = nil
    }

    private func start() {
        guard displayLink == nil, window != nil else { return }
        let link = CADisplayLink(target: proxy, selector: #selector(DisplayLinkProxy.tick(_:)))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    fileprivate func tick(_ link: CADisplayLink) {
        let interval: CFTimeInterval
        if link.duration > 0 {
            interval = link.duration
        } else {
            let fallback = link.targetTimestamp - link.timestamp
            interval = fallback > 0 ? fallback : 1.0 / 60.0
        }
        apply(tracker.record(timestamp: link.timestamp, expectedInterval: interval))
    }

    private func apply(_ snapshot: FrameTimeSnapshot) {
        label.text = snapshot.label
        label.textColor = snapshot.spikeActive
            ? UIColor(red: 1, green: 0.72, blue: 0.28, alpha: 1)
            : .white
        invalidateIntrinsicContentSize()
    }

    @objc private func applicationDidEnterBackground() {
        stop()
    }

    @objc private func applicationWillEnterForeground() {
        start()
    }
}

private final class DisplayLinkProxy: NSObject {
    weak var owner: PerformanceOverlayUIView?

    @objc func tick(_ link: CADisplayLink) {
        owner?.tick(link)
    }
}
