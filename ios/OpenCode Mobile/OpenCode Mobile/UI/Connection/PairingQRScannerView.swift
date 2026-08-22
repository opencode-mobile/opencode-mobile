import AVFoundation
import SwiftUI
import Vision
import VisionKit

struct PairingQRScannerView: View {
    var onScanned: (String) -> Void
    var onDismiss: () -> Void

    var body: some View {
        ZStack {
            scannerContent
            VStack(spacing: 0) {
                OpenCodeTopBar(
                    title: "Scan pairing QR",
                    leading: .back(action: onDismiss)
                )
                PairingQRReticle()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                instructionCard
                    .padding(.horizontal, 16)
                    .padding(.bottom, 28)
            }
        }
        .background(OpenCodeColors.backgroundBase)
        .onAppear {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.pairingQRScanner,
                presentation: ProductAnalytics.Presentation.fullscreen,
                trigger: ProductAnalytics.Trigger.present
            )
        }
    }

    @ViewBuilder
    private var scannerContent: some View {
        if DataScannerViewController.isSupported, DataScannerViewController.isAvailable {
            PairingQRDataScanner(onScanned: onScanned)
                .ignoresSafeArea()
        } else {
            Color.black.ignoresSafeArea()
        }
    }

    private var instructionCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("OpenCode 2 pairing QR")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("From opencode2 pair on a v2 server. Classic serve has no QR — enter the host instead.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
            Text("opencode2 pair")
                .font(OpenCodeTypography.code)
                .foregroundStyle(OpenCodeColors.textStrong)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 10))
                .padding(.top, 4)
        }
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct PairingQRReticle: View {
    private let holeSize: CGFloat = 220
    private let cornerRadius: CGFloat = 16

    var body: some View {
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

private struct PairingQRDataScanner: UIViewControllerRepresentable {
    var onScanned: (String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onScanned: onScanned)
    }

    func makeUIViewController(context: Context) -> DataScannerViewController {
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

    func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {
        context.coordinator.onScanned = onScanned
        if !uiViewController.isScanning {
            try? uiViewController.startScanning()
        }
    }

    static func dismantleUIViewController(_ uiViewController: DataScannerViewController, coordinator: Coordinator) {
        uiViewController.stopScanning()
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        var onScanned: (String) -> Void
        private var handled = false

        init(onScanned: @escaping (String) -> Void) {
            self.onScanned = onScanned
        }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            didAdd addedItems: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            handle(addedItems)
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didTapOn item: RecognizedItem) {
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
