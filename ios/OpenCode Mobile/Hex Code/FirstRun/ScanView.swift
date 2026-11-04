import OpenCodeKit
import SwiftUI
import VisionKit

/// First run, step 2: the camera (always dark) and one panel. The server checks run behind
/// "Connecting" and only show when one fails.
struct ScanView: View {
    let phase: FirstRunModel.ScanPhase
    let attempt: Int
    let onScanned: (String) -> Void
    let onScanAgain: () -> Void
    let onClose: () -> Void

    var body: some View {
        ZStack {
            camera
            PairingQRReticle()
                .ignoresSafeArea()
            VStack(spacing: 0) {
                HStack {
                    Button(action: onClose) {
                        OpenCodeIconImage(icon: .close, size: 18)
                            .foregroundStyle(.white)
                            .frame(width: 40, height: 40)
                            .background(.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(HexPressStyle())
                    .accessibilityLabel(Text("common.close", comment: "Close the camera"))
                    Spacer()
                }
                .padding(.horizontal, HexLayout.edge)
                .padding(.top, 8)
                Spacer()
                panel
                    .padding(.horizontal, 12)
                    .padding(.bottom, 8)
            }
        }
        .background(Color.black)
        .environment(\.colorScheme, .dark)
    }

    @ViewBuilder
    private var camera: some View {
        if DataScannerViewController.isSupported, DataScannerViewController.isAvailable {
            PairingQRDataScanner(onScanned: onScanned)
                .id(attempt)
                .ignoresSafeArea()
        } else {
            Color.black.ignoresSafeArea()
        }
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: 6) {
            switch phase {
            case .scanning:
                title(LocalizedStringResource("scan.point.title", defaultValue: "Point at the code"), icon: .search)
                Text(LocalizedStringResource(
                    "scan.point.body",
                    defaultValue: "It’s on your computer after `opencode pair`. It works once."
                ))
                .modifier(PanelBody())
                if !(DataScannerViewController.isSupported && DataScannerViewController.isAvailable) {
                    Text(LocalizedStringResource(
                        "scan.no_camera",
                        defaultValue: "The camera isn’t available. Allow it in Settings › Hex Code."
                    ))
                    .modifier(PanelBody())
                }
            case let .connecting(host):
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small).tint(.white)
                    Text(host.isEmpty
                        ? LocalizedStringResource("scan.connecting", defaultValue: "Connecting")
                        : LocalizedStringResource("scan.connecting_to", defaultValue: "Connecting to \(host)"))
                        .font(.system(size: 16, weight: .semibold))
                }
                Text(LocalizedStringResource("scan.checking", defaultValue: "Checking the server can run this app."))
                    .modifier(PanelBody())
            case let .connected(server):
                title(LocalizedStringResource("scan.connected", defaultValue: "Connected"), icon: .check, tint: OpenCodeColors.success)
                Text(LocalizedStringResource(
                    "scan.connected.detail",
                    defaultValue: "OpenCode \(server.version ?? "2") on \(server.hostLabel)"
                ))
                .modifier(PanelBody())
            case let .failed(failure):
                title(PairingFailureCopy.title(failure), icon: .close, tint: OpenCodeColors.error)
                Text(PairingFailureCopy.body(failure))
                    .modifier(PanelBody())
                Button(action: onScanAgain) {
                    Text(LocalizedStringResource("scan.again", defaultValue: "Scan again"))
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.black)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(HexPressStyle())
                .padding(.top, 8)
            }
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .animation(.easeOut(duration: 0.2), value: phase)
    }

    private func title(_ text: LocalizedStringResource, icon: OpenCodeIcon, tint: Color = .white.opacity(0.7)) -> some View {
        HStack(spacing: 8) {
            OpenCodeIconImage(icon: icon, size: 16).foregroundStyle(tint)
            Text(text).font(.system(size: 16, weight: .semibold))
        }
    }

    private struct PanelBody: ViewModifier {
        func body(content: Content) -> some View {
            content
                .font(.system(size: 14))
                .lineSpacing(2)
                .foregroundStyle(.white.opacity(0.7))
        }
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
        case let .unreachable(host):
            LocalizedStringResource("pairing.unreachable.title", defaultValue: "Can’t reach \(host)")
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
