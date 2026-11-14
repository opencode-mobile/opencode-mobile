import OpenCodeKit
import SwiftUI

/// First run, step 2: the camera (always dark) and one panel. The server checks run behind
/// "Connecting" and only show when one fails.
struct ScanView: View {
    let phase: FirstRunModel.ScanPhase
    let attempt: Int
    let onScanned: (String) -> Void
    let onScanAgain: () -> Void
    let onClose: () -> Void
    /// Pair again shows "Cancel" in place of ✕.
    var cancelTitle: LocalizedStringResource?

    var body: some View {
        ZStack {
            camera
            ScanReticle(found: isConnected)
                .frame(width: 220, height: 220)
                .allowsHitTesting(false)
            VStack(spacing: 0) {
                HStack {
                    if let cancelTitle {
                        HexButton(cancelTitle, size: .regular, action: onClose)
                    } else {
                        HexButton(icon: .x, size: .regular, action: onClose)
                            .accessibilityLabel(Text(LocalizedStringResource("common.close", defaultValue: "Close")))
                    }
                    Spacer()
                }
                .padding(.horizontal, HexSpacing.screen)
                .padding(.top, 8)
                Spacer()
                panel
                    .padding(.horizontal, 12)
                    .padding(.bottom, 8)
            }
        }
        .hexScreenBackground()
        .environment(\.colorScheme, .dark)
        // The two-finger scrub closes it, as ✕ or Cancel does.
        .accessibilityAction(.escape, onClose)
    }

    private var isConnected: Bool {
        if case .connected = phase { return true }
        return false
    }

    @ViewBuilder
    private var camera: some View {
        if QRCodeScanner.isAvailable {
            QRCodeScanner(onScanned: onScanned)
                .id(attempt)
                .ignoresSafeArea()
        } else {
            HexColor.background.ignoresSafeArea()
        }
    }

    private var panel: some View {
        HexPanel(translucent: true) {
            VStack(alignment: .leading, spacing: 6) {
                switch phase {
                case .scanning:
                    title(LocalizedStringResource("scan.point.title", defaultValue: "Point at the code")) {
                        HexIconView(.scan).foregroundStyle(HexColor.text2)
                    }
                    panelBody(LocalizedStringResource(
                        "scan.point.body",
                        defaultValue: "It’s on your computer after `opencode pair`. It works once."
                    ))
                    if !QRCodeScanner.isAvailable {
                        panelBody(LocalizedStringResource(
                            "scan.no_camera",
                            defaultValue: "The camera isn’t available. Allow it in Settings › Hex Code."
                        ))
                    }
                case .connecting:
                    title(LocalizedStringResource("scan.connecting", defaultValue: "Connecting to your computer")) {
                        HexSpinner()
                    }
                    panelBody(LocalizedStringResource("scan.checking", defaultValue: "Checking the server can run this app."))
                case let .connected(server):
                    title(LocalizedStringResource("scan.connected", defaultValue: "Connected to \(server.displayName)")) {
                        HexIconView(.check).foregroundStyle(HexColor.green)
                    }
                    panelBody(LocalizedStringResource("scan.connected.detail", defaultValue: "OpenCode \(server.version ?? "2")"))
                case let .failed(failure):
                    title(PairingFailureCopy.title(failure)) {
                        HexIconView(.alert).foregroundStyle(HexColor.amber)
                    }
                    panelBody(PairingFailureCopy.body(failure))
                    HexPrimaryButton(LocalizedStringResource("scan.again", defaultValue: "Scan again"), action: onScanAgain)
                        .padding(.top, 8)
                }
            }
        }
        .animation(HexMotion.ease(HexMotion.fade), value: phase)
    }

    private func title<Icon: View>(_ text: LocalizedStringResource, @ViewBuilder icon: () -> Icon) -> some View {
        HStack(spacing: 8) {
            icon()
            Text(text)
                .hexFont(.bodyStrong)
                .foregroundStyle(HexColor.text1)
        }
    }

    private func panelBody(_ text: LocalizedStringResource) -> some View {
        Text(text)
            .hexFont(.row)
            .foregroundStyle(HexColor.text2)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Four corner brackets round the code; green once connected.
private struct ScanReticle: View {
    let found: Bool

    var body: some View {
        ZStack {
            let corners: [Alignment] = [.topLeading, .topTrailing, .bottomTrailing, .bottomLeading]
            ForEach(0 ..< 4, id: \.self) { index in
                ScanCorner()
                    .stroke(found ? HexColor.green : HexColor.text1, style: StrokeStyle(lineWidth: 3, lineCap: .square))
                    .frame(width: 30, height: 30)
                    .rotationEffect(.degrees(Double(index) * 90))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: corners[index])
            }
        }
        .animation(HexMotion.ease(HexMotion.fade), value: found)
        .accessibilityHidden(true)
    }
}

/// The top-left corner of the reticle, rounded at radius `m`.
private struct ScanCorner: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let radius = HexRadius.m
        path.move(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + radius))
        path.addArc(
            center: CGPoint(x: rect.minX + radius, y: rect.minY + radius),
            radius: radius,
            startAngle: .degrees(180),
            endAngle: .degrees(270),
            clockwise: false
        )
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        return path
    }
}

/// What the scan panel says when pairing stops.
enum PairingFailureCopy {
    static func title(_ failure: PairingFailure) -> LocalizedStringResource {
        switch failure {
        case .notAPairingCode:
            LocalizedStringResource("pairing.not_a_code.title", defaultValue: "That isn’t a pairing code")
        case .onlyLocalAddresses:
            LocalizedStringResource("pairing.local_only.title", defaultValue: "Your computer isn’t reachable yet")
        case .unreachable:
            LocalizedStringResource("pairing.unreachable.title", defaultValue: "Can’t reach your computer")
        case .codeExpired:
            LocalizedStringResource("pairing.expired.title", defaultValue: "This code was used or expired")
        case .notOpenCode2:
            LocalizedStringResource("pairing.not_v2.title", defaultValue: "This server isn’t OpenCode 2")
        case .tooOld:
            LocalizedStringResource("pairing.too_old.title", defaultValue: "OpenCode needs an update")
        case .missingRoutes:
            LocalizedStringResource("pairing.missing_routes.title", defaultValue: "This OpenCode can’t run Hex Code")
        }
    }

    static func body(_ failure: PairingFailure) -> LocalizedStringResource {
        switch failure {
        case .notAPairingCode:
            LocalizedStringResource("pairing.not_a_code.body", defaultValue: "Scan the code `opencode pair` prints on your computer.")
        case .onlyLocalAddresses:
            LocalizedStringResource(
                "pairing.local_only.body",
                defaultValue: "Run `opencode service set hostname 0.0.0.0` on your computer, then `opencode pair` again."
            )
        case .unreachable:
            LocalizedStringResource(
                "pairing.unreachable.body",
                defaultValue: "Check the phone is on the same network or Tailscale as your computer, or use `opencode pair --remote`."
            )
        case .codeExpired:
            LocalizedStringResource("pairing.expired.body", defaultValue: "Codes work once, for 5 minutes. Run `opencode pair` again.")
        case .notOpenCode2:
            LocalizedStringResource("pairing.not_v2.body", defaultValue: "Hex Code needs OpenCode 2.2.5 or newer. Run `opencode upgrade`.")
        case let .tooOld(version):
            LocalizedStringResource(
                "pairing.too_old.body",
                defaultValue: "It runs OpenCode \(version); Hex Code needs 2.2.5 or newer. Run `opencode upgrade`."
            )
        case .missingRoutes:
            LocalizedStringResource(
                "pairing.missing_routes.body",
                defaultValue: "Its API has no session generate route. Run `opencode upgrade`."
            )
        }
    }
}
