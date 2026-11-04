import SwiftUI
import Vision
import VisionKit

public struct PairingQRReticle: View {
    public init() {}

    private let holeSize: CGFloat = 220
    private let cornerRadius: CGFloat = 16

    public var body: some View {
        Canvas { context, size in
            let hole = CGRect(
                x: (size.width - holeSize) / 2,
                y: (size.height - holeSize) / 2,
                width: holeSize,
                height: holeSize
            )
            var overlay = Path(CGRect(origin: .zero, size: size))
            overlay.addRoundedRect(in: hole, cornerSize: CGSize(width: cornerRadius, height: cornerRadius))
            context.fill(
                overlay,
                with: .color(.black.opacity(0.45)),
                style: FillStyle(eoFill: true)
            )
            context.stroke(
                Path(roundedRect: hole, cornerRadius: cornerRadius),
                with: .color(.white.opacity(0.85)),
                lineWidth: 2
            )
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

public struct PairingQRDataScanner: UIViewControllerRepresentable {
    public var onScanned: (String) -> Void

    public init(onScanned: @escaping (String) -> Void) {
        self.onScanned = onScanned
    }

    public func makeCoordinator() -> Coordinator {
        Coordinator(onScanned: onScanned)
    }

    public func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: false,
            isHighlightingEnabled: true
        )
        controller.delegate = context.coordinator
        DispatchQueue.main.async {
            guard !controller.isScanning else { return }
            try? controller.startScanning()
        }
        return controller
    }

    public func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {
        context.coordinator.onScanned = onScanned
        if !uiViewController.isScanning {
            try? uiViewController.startScanning()
        }
    }

    public static func dismantleUIViewController(_ uiViewController: DataScannerViewController, coordinator: Coordinator) {
        uiViewController.stopScanning()
    }

    public final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        public var onScanned: (String) -> Void
        private var handled = false

        public init(onScanned: @escaping (String) -> Void) {
            self.onScanned = onScanned
        }

        public func dataScanner(
            _ dataScanner: DataScannerViewController,
            didAdd addedItems: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            handle(addedItems)
        }

        public func dataScanner(_ dataScanner: DataScannerViewController, didTapOn item: RecognizedItem) {
            handle([item])
        }

        private func handle(_ items: [RecognizedItem]) {
            guard !handled else { return }
            for item in items {
                if case let .barcode(barcode) = item, let value = barcode.payloadStringValue, !value.isEmpty {
                    handled = true
                    onScanned(value)
                    return
                }
            }
        }
    }
}
