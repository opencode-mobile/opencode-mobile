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
                    title: "connection.scanner.title",
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
            Text("connection.scanner.card_title")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("connection.scanner.card_body")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
            OpenCodeCodeSnippet("opencode pair --remote")
                .padding(.top, 4)
        }
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }
}
