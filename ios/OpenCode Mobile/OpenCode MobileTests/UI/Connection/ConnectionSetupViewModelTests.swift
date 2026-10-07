import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct ConnectionSetupViewModelTests {
    @Test func newForm_startsWithEmptyHostAndCollapsedCredentials() {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        #expect(viewModel.baseURL.isEmpty)
        #expect(viewModel.primaryActionLabel == "Connect")
        #expect(viewModel.credentialSegment == .password)
        #expect(ConnectionSetupViewModel.hostIsBlank(viewModel.baseURL))
    }

    @Test func submit_emptyHost_showsSnackbar() async {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        let saved = await viewModel.submit()
        #expect(saved == false)
        #expect(viewModel.snackbar?.text == "Enter a host")
    }

    @Test func submit_create_probesThenSaves() async {
        let store = RecordingConnectionProfileStore()
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v2)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: store
            ),
            mode: .add
        )
        viewModel.baseURL = "http://10.0.0.1:4096/"
        viewModel.password = "secret"
        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(store.saved.count == 1)
        #expect(store.saved.last?.serverProtocol == .v2)
        #expect(store.saved.last?.serverURLs.isEmpty == true)
        #expect(viewModel.usesQRLayout == false)
    }

    @Test func submit_authFailure_expandsCredentialsAndSnackbars() async {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(
                result: .failure(ServerProbeError.http(statusCode: 401, message: "Unauthorized"))
            ),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        viewModel.baseURL = "http://10.0.0.1:4096/"
        viewModel.password = "wrong"
        let saved = await viewModel.submit()
        #expect(saved == false)
        #expect(viewModel.credentialSegment == .password)
        #expect(viewModel.requestPasswordFocus == true)
        #expect(viewModel.snackbar?.text == "Authentication failed")
    }

    @Test func submit_transportFailure_staysCollapsed() async {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(
                result: .failure(ServerProbeError.transport("down"))
            ),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        viewModel.baseURL = "http://10.0.0.1:4096/"
        let saved = await viewModel.submit()
        #expect(saved == false)
        #expect(viewModel.credentialSegment == .password)
        #expect(viewModel.snackbar?.text == "Unable to reach the server")
    }

    @Test func fieldChange_clearsDetectedProtocolUntilReprobe() async {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        viewModel.baseURL = "http://10.0.0.1:4096/"
        _ = await viewModel.submit()
        // After successful submit we dismiss in UI; here stay and dirty host
        viewModel.configure(
            initialProfile: ConnectionProfile(
                baseURL: "http://10.0.0.1:4096/",
                username: "opencode",
                password: "secret",
                serverProtocol: .v1,
                id: "p1",
                name: "P"
            ),
            mode: .edit
        )
        #expect(viewModel.canSubmitWithoutProbe == true)
        viewModel.baseURL = "http://10.0.0.9:4096/"
        viewModel.onFieldChanged()
        #expect(viewModel.canSubmitWithoutProbe == false)
    }

    @Test func revertedEdit_keepsSavedProtocol() async {
        let store = RecordingConnectionProfileStore()
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: store
            )
        )
        viewModel.configure(
            initialProfile: ConnectionProfile(
                baseURL: "http://10.0.0.1:4096/",
                username: "opencode",
                password: "secret",
                serverProtocol: .v2,
                id: "home",
                name: "Home"
            ),
            mode: .edit
        )
        #expect(viewModel.primaryActionLabel == "Save")
        #expect(viewModel.credentialSegment == .password)
        #expect(viewModel.canSubmitWithoutProbe == true)

        viewModel.baseURL = "http://10.0.0.9:4096/"
        viewModel.onFieldChanged()
        #expect(viewModel.canSubmitWithoutProbe == false)

        viewModel.baseURL = "http://10.0.0.1:4096/"
        viewModel.onFieldChanged()
        #expect(viewModel.canSubmitWithoutProbe == true)

        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(store.saved.last?.serverProtocol == .v2)
    }

    @Test func hostIsBlank_detectsSchemeOnly() {
        #expect(ConnectionSetupViewModel.hostIsBlank(""))
        #expect(ConnectionSetupViewModel.hostIsBlank("http://"))
        #expect(ConnectionSetupViewModel.hostIsBlank("https://"))
        #expect(ConnectionSetupViewModel.hostIsBlank("http://10.0.0.1:4096/") == false)
    }

    @Test func applyPairingQR_fillsHostAndExpandsCredentials() async {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        await viewModel.applyPairingQR(
            """
            {"urls":["http://192.168.0.160:4096"],"username":"opencode","password":"secret"}
            """
        )
        #expect(viewModel.baseURL == "http://192.168.0.160:4096/")
        #expect(viewModel.username == "opencode")
        #expect(viewModel.password == "secret")
        #expect(viewModel.usesQRLayout)
        #expect(viewModel.qrPairingReady)
        #expect(viewModel.primaryActionLabel == "Save")
        #expect(viewModel.showsPrimaryAction)
        #expect(viewModel.snackbar == nil)
    }

    @Test func applyPairingQR_fillsTailscaleHostWhenPresent() async {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        await viewModel.applyPairingQR(
            """
            {"urls":["http://192.168.0.160:4096","http://100.84.57.15:4096"],"username":"opencode","password":"secret"}
            """
        )
        #expect(viewModel.baseURL == "http://100.84.57.15:4096/")
    }

    @Test func submit_pairingQR_bothReachable_savesTailscale() async {
        let store = RecordingConnectionProfileStore()
        let probe = RecordingURLProbeService(successProtocol: .v2)
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: probe,
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: store
            ),
            mode: .add
        )
        await viewModel.applyPairingQR(
            """
            {"urls":["http://192.168.0.160:4096","http://100.84.57.15:4096"],"username":"opencode","password":"secret"}
            """
        )
        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(Set(probe.probed) == Set([
            "http://192.168.0.160:4096/",
            "http://100.84.57.15:4096/",
        ]))
        #expect(store.saved.last?.baseURL == "http://100.84.57.15:4096/")
        #expect(viewModel.baseURL == "http://100.84.57.15:4096/")
        #expect(Set(store.saved.last?.serverURLs ?? []) == Set([
            "http://192.168.0.160:4096/",
            "http://100.84.57.15:4096/",
        ]))
        #expect(viewModel.usesQRLayout)
    }

    @Test func submit_pairingQR_tailscaleDown_savesLan() async {
        let store = RecordingConnectionProfileStore()
        let probe = RecordingURLProbeService(
            failingHosts: ["100.84.57.15"],
            successProtocol: .v2
        )
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: probe,
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: store
            ),
            mode: .add
        )
        await viewModel.applyPairingQR(
            """
            {"urls":["http://192.168.0.160:4096","http://100.84.57.15:4096"],"username":"opencode","password":"secret"}
            """
        )
        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(store.saved.last?.baseURL == "http://192.168.0.160:4096/")
        #expect(viewModel.baseURL == "http://192.168.0.160:4096/")
        #expect(Set(store.saved.last?.serverURLs ?? []) == Set([
            "http://192.168.0.160:4096/",
            "http://100.84.57.15:4096/",
        ]))
    }

    @Test func editingHost_whileServerURLsSet_clearsThemAndLeavesQRLayout() async {
        let probe = RecordingURLProbeService(successProtocol: .v2)
        let store = RecordingConnectionProfileStore()
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: probe,
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: store
            ),
            mode: .add
        )
        await viewModel.applyPairingQR(
            """
            {"urls":["http://192.168.0.160:4096","http://100.84.57.15:4096"],"username":"opencode","password":"secret"}
            """
        )
        viewModel.name = "Studio"
        #expect(viewModel.serverURLs.count == 2)
        #expect(viewModel.usesQRLayout)

        viewModel.baseURL = "http://10.0.0.9:4096/"
        viewModel.onBaseURLChanged()
        #expect(viewModel.serverURLs.isEmpty)
        #expect(viewModel.usesQRLayout == false)
        #expect(viewModel.password.isEmpty)

        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(probe.probed.contains("http://10.0.0.9:4096/"))
        #expect(probe.probed.last == "http://10.0.0.9:4096/")
        #expect(store.saved.last?.baseURL == "http://10.0.0.9:4096/")
        #expect(store.saved.last?.serverURLs.isEmpty == true)
    }

    @Test func submit_pairingQR_authFailure_doesNotSave() async {
        let store = RecordingConnectionProfileStore()
        let probe = RecordingURLProbeService(authFailingHosts: ["192.168.0.160", "100.84.57.15"])
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: probe,
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: store
            ),
            mode: .add
        )
        await viewModel.applyPairingQR(
            """
            {"urls":["http://192.168.0.160:4096","http://100.84.57.15:4096"],"username":"opencode","password":"wrong"}
            """
        )
        #expect(viewModel.usesQRLayout == false)
        #expect(viewModel.serverURLs.isEmpty)
        #expect(viewModel.baseURL == "http://100.84.57.15:4096/")
        #expect(viewModel.password == "wrong")
        #expect(viewModel.credentialSegment == .password)
        #expect(viewModel.showsPrimaryAction)
        #expect(viewModel.primaryEnabled)
        #expect(viewModel.primaryActionLabel == "Connect")
        #expect(viewModel.snackbar?.text == qrScanFailureMessage)
        #expect(viewModel.snackbar?.variant == .error)
        #expect(store.saved.isEmpty)
    }

    @Test func submit_pairingQR_customHost_probesOnlyEditedURL() async {
        let probe = RecordingURLProbeService()
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: probe,
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        await viewModel.applyPairingQR(
            """
            {"urls":["http://192.168.0.160:4096","http://100.84.57.15:4096"],"username":"opencode","password":"secret"}
            """
        )
        viewModel.baseURL = "http://10.0.0.1:4096/"
        viewModel.onFieldChanged()
        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(probe.probed.contains("http://10.0.0.1:4096/"))
        #expect(probe.probed.last == "http://10.0.0.1:4096/")
    }

    @Test func applyPairingQR_invalid_showsSnackbar() async {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        await viewModel.applyPairingQR("nope")
        #expect(viewModel.snackbar?.text == "This isn't an OpenCode 2 pairing QR")
        #expect(viewModel.baseURL.isEmpty)
    }

    @Test func pairingQRCameraDenied_showsSnackbar() {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        viewModel.pairingQRCameraDenied()
        #expect(viewModel.snackbar?.text == "Allow camera access to scan the pairing QR")
    }

    @Test func applyPairingQR_codeJSON_pairsAndOffersSave() async {
        let store = RecordingConnectionProfileStore()
        let redeemer = ScriptedPairingRedeemer(token: "paired-token")
        let viewModel = makePairingSetupViewModel(
            probe: ScriptedHostProbe(),
            redeemer: redeemer,
            store: store
        )
        await viewModel.applyPairingQR(pairingCodeJSON)
        #expect(viewModel.baseURL == "http://100.64.0.3:49374/")
        #expect(viewModel.username == "opencode")
        #expect(viewModel.password == "paired-token")
        #expect(viewModel.qrPairingReady)
        #expect(viewModel.primaryActionLabel == "Save")
        #expect(viewModel.showsPrimaryAction)
        #expect(store.saved.isEmpty)
        #expect(viewModel.snackbar == nil)
        #expect(viewModel.usesQRLayout)
        #expect(viewModel.savedAddressRows.first { $0.url.contains("100.64") }?.badge == "Using now")
    }

    @Test func submit_pairingCode_bothAuthFailed_redeemsTailscaleOnce() async {
        let store = RecordingConnectionProfileStore()
        let probe = ScriptedHostProbe()
        let redeemer = ScriptedPairingRedeemer(token: "paired-token")
        let viewModel = makePairingSetupViewModel(probe: probe, redeemer: redeemer, store: store)
        await viewModel.applyPairingQR(pairingCodeJSON)
        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(redeemer.calls.count == 1)
        #expect(redeemer.calls.first?.baseURL == "http://100.64.0.3:49374/")
        #expect(redeemer.calls.first?.code == "abc_DEF-123")
        #expect(store.saved.last?.password == "paired-token")
        #expect(store.saved.last?.baseURL == "http://100.64.0.3:49374/")
        #expect(Set(store.saved.last?.serverURLs ?? []) == Set([
            "http://192.168.1.2:49374/",
            "http://100.64.0.3:49374/",
        ]))
        #expect(viewModel.usesQRLayout)
        #expect(viewModel.savedAddressRows.first { $0.url.contains("100.64") }?.badge == "Using now")
        #expect(viewModel.savedAddressRows.first { $0.url.contains("192.168") }?.badge == "Saved")
        #expect(viewModel.savedAddressRows.allSatisfy { !$0.dimmed })
        #expect(viewModel.snackbar == nil)
    }

    @Test func submit_pairingCode_tailscaleTransport_redeemsLan() async {
        let store = RecordingConnectionProfileStore()
        let probe = ScriptedHostProbe(transportHosts: ["100.64.0.3"])
        let redeemer = ScriptedPairingRedeemer(token: "paired-token")
        let viewModel = makePairingSetupViewModel(probe: probe, redeemer: redeemer, store: store)
        await viewModel.applyPairingQR(pairingCodeJSON)
        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(redeemer.calls.count == 1)
        #expect(redeemer.calls.first?.baseURL == "http://192.168.1.2:49374/")
        #expect(redeemer.calls.first?.code == "abc_DEF-123")
        #expect(store.saved.last?.baseURL == "http://192.168.1.2:49374/")
        #expect(store.saved.last?.password == "paired-token")
        #expect(Set(store.saved.last?.serverURLs ?? []) == Set([
            "http://192.168.1.2:49374/",
            "http://100.64.0.3:49374/",
        ]))
        let tailscale = viewModel.savedAddressRows.first { $0.url.contains("100.64") }
        let lan = viewModel.savedAddressRows.first { $0.url.contains("192.168") }
        #expect(tailscale?.dimmed == true)
        #expect(tailscale?.badge == "Saved")
        #expect(tailscale?.subtitle.contains("not reachable") == true)
        #expect(lan?.badge == "Using now")
        #expect(lan?.dimmed == false)
    }

    @Test func submit_pairingCode_allTransport_doesNotRedeem() async {
        let store = RecordingConnectionProfileStore()
        let probe = ScriptedHostProbe(
            defaultUnauthenticated: .failure(ServerProbeError.transport("down"))
        )
        let redeemer = ScriptedPairingRedeemer()
        let viewModel = makePairingSetupViewModel(probe: probe, redeemer: redeemer, store: store)
        await viewModel.applyPairingQR(pairingCodeJSON)
        #expect(redeemer.calls.isEmpty)
        #expect(store.saved.isEmpty)
        #expect(viewModel.usesQRLayout == false)
        #expect(viewModel.baseURL == "http://100.64.0.3:49374/")
        #expect(viewModel.password.isEmpty)
        #expect(viewModel.pairingCode.isEmpty)
        #expect(viewModel.credentialSegment == .password)
        #expect(viewModel.showsPrimaryAction)
        #expect(viewModel.primaryEnabled)
        #expect(viewModel.snackbar?.text == qrScanFailureMessage)
        #expect(viewModel.snackbar?.variant == .error)
    }

    @Test func submit_pairingCode_redeemExpired_showsSnackbarOnce() async {
        let store = RecordingConnectionProfileStore()
        let redeemer = ScriptedPairingRedeemer(error: PairingCodeRedeemError.expired)
        let viewModel = makePairingSetupViewModel(
            probe: ScriptedHostProbe(),
            redeemer: redeemer,
            store: store
        )
        await viewModel.applyPairingQR(pairingCodeJSON)
        #expect(redeemer.calls.count == 1)
        #expect(store.saved.isEmpty)
        #expect(viewModel.usesQRLayout == false)
        #expect(viewModel.baseURL == "http://100.64.0.3:49374/")
        #expect(viewModel.password.isEmpty)
        #expect(viewModel.credentialSegment == .password)
        #expect(viewModel.showsPrimaryAction)
        #expect(viewModel.snackbar?.text == qrScanFailureMessage)
        #expect(viewModel.snackbar?.variant == .error)
    }

    @Test func submit_pastedConnectLink_redeemsAndSavesOrigin() async {
        let store = RecordingConnectionProfileStore()
        let redeemer = ScriptedPairingRedeemer(token: "paired-token")
        let viewModel = makePairingSetupViewModel(
            probe: ScriptedHostProbe(),
            redeemer: redeemer,
            store: store
        )
        viewModel.baseURL = "http://192.168.0.183:4096/auth/connect/XJdLmg9llko9nmn3jN2XLw"
        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(redeemer.calls.count == 1)
        #expect(redeemer.calls.first?.baseURL == "http://192.168.0.183:4096/")
        #expect(redeemer.calls.first?.code == "XJdLmg9llko9nmn3jN2XLw")
        #expect(store.saved.last?.password == "paired-token")
        #expect(store.saved.last?.baseURL == "http://192.168.0.183:4096/")
        #expect(store.saved.last?.serverURLs == ["http://192.168.0.183:4096/"])
        #expect(viewModel.usesQRLayout)
        #expect(viewModel.savedAddressRows.count == 1)
        #expect(viewModel.username == "opencode")
    }

    @Test func typedPassword_afterScan_savesReplacementWithoutRedeemingAgain() async {
        let store = RecordingConnectionProfileStore()
        let probe = ScriptedHostProbe(
            defaultUnauthenticated: .failure(ServerProbeError.http(statusCode: 401, message: "Unauthorized")),
            authenticated: .success(.v2)
        )
        let redeemer = ScriptedPairingRedeemer(token: "paired-token")
        let viewModel = makePairingSetupViewModel(probe: probe, redeemer: redeemer, store: store)
        await viewModel.applyPairingQR(pairingCodeJSON)
        #expect(redeemer.calls.count == 1)
        #expect(viewModel.qrPairingReady)
        viewModel.password = "user-secret"
        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(redeemer.calls.count == 1)
        #expect(store.saved.last?.password == "user-secret")
    }

    @Test func pairingQRCameraUnavailable_showsSnackbar() {
        let viewModel = ConnectionSetupViewModel(
            connectionProbeService: FakeConnectionProbeService(result: .success(.v1)),
            connectionSessionCoordinator: ConnectionSessionCoordinator(
                activeConnectionManager: ActiveConnectionManager(),
                connectionProfileStore: FakeSetupConnectionProfileStore()
            ),
            mode: .add
        )
        viewModel.pairingQRCameraUnavailable()
        #expect(viewModel.snackbar?.text == "Camera isn't available on this device")
    }

    @Test func submit_passwordSegment_emptyPassword_probesAndSavesWithoutRedeem() async {
        let store = RecordingConnectionProfileStore()
        let redeemer = ScriptedPairingRedeemer()
        let probe = RecordingURLProbeService(successProtocol: .v1)
        let viewModel = makePairingSetupViewModel(probe: probe, redeemer: redeemer, store: store)
        viewModel.baseURL = "http://10.0.0.1:4096/"
        viewModel.username = ""
        viewModel.password = ""
        viewModel.selectCredentialSegment(.password)
        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(redeemer.calls.isEmpty)
        #expect(probe.probed == ["http://10.0.0.1:4096/"])
        #expect(store.saved.last?.username == "opencode")
        #expect(store.saved.last?.password.isEmpty == true)
        #expect(store.saved.last?.serverURLs.isEmpty == true)
    }

    @Test func submit_pairingCodeSegment_redeemsOnceAndSavesToken() async {
        let store = RecordingConnectionProfileStore()
        let redeemer = ScriptedPairingRedeemer(token: "typed-token")
        let viewModel = makePairingSetupViewModel(
            probe: ScriptedHostProbe(),
            redeemer: redeemer,
            store: store
        )
        viewModel.baseURL = "http://10.0.0.8:4096/"
        viewModel.selectCredentialSegment(.pairingCode)
        viewModel.pairingCode = "once-code"
        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(redeemer.calls.count == 1)
        #expect(redeemer.calls.first?.baseURL == "http://10.0.0.8:4096/")
        #expect(redeemer.calls.first?.code == "once-code")
        #expect(store.saved.last?.password == "typed-token")
        #expect(store.saved.last?.baseURL == "http://10.0.0.8:4096/")
        #expect(store.saved.last?.serverURLs.isEmpty == true)
    }

    @Test func submit_pairingCodeSegment_blankCode_showsSnackbarWithoutRedeem() async {
        let store = RecordingConnectionProfileStore()
        let redeemer = ScriptedPairingRedeemer()
        let viewModel = makePairingSetupViewModel(
            probe: FakeConnectionProbeService(result: .success(.v1)),
            redeemer: redeemer,
            store: store
        )
        viewModel.baseURL = "http://10.0.0.8:4096/"
        viewModel.selectCredentialSegment(.pairingCode)
        viewModel.pairingCode = "   "
        let saved = await viewModel.submit()
        #expect(saved == false)
        #expect(redeemer.calls.isEmpty)
        #expect(viewModel.snackbar?.text == "Enter a pairing code")
        #expect(store.saved.isEmpty)
    }

    @Test func submit_pairingCodeSegment_blankHost_showsEnterHost() async {
        let store = RecordingConnectionProfileStore()
        let redeemer = ScriptedPairingRedeemer()
        let viewModel = makePairingSetupViewModel(
            probe: FakeConnectionProbeService(result: .success(.v1)),
            redeemer: redeemer,
            store: store
        )
        viewModel.selectCredentialSegment(.pairingCode)
        viewModel.pairingCode = "once-code"
        let saved = await viewModel.submit()
        #expect(saved == false)
        #expect(redeemer.calls.isEmpty)
        #expect(viewModel.snackbar?.text == "Enter a host")
    }

    @Test func submit_passwordSegment_afterPairingCode_doesNotRedeem() async {
        let store = RecordingConnectionProfileStore()
        let redeemer = ScriptedPairingRedeemer()
        let viewModel = makePairingSetupViewModel(
            probe: RecordingURLProbeService(successProtocol: .v2),
            redeemer: redeemer,
            store: store
        )
        viewModel.baseURL = "http://10.0.0.8:4096/"
        viewModel.password = "secret"
        viewModel.selectCredentialSegment(.pairingCode)
        viewModel.pairingCode = "leftover-code"
        viewModel.selectCredentialSegment(.password)
        #expect(viewModel.password == "secret")
        #expect(viewModel.pairingCode == "leftover-code")
        let saved = await viewModel.submit()
        #expect(saved == true)
        #expect(redeemer.calls.isEmpty)
        #expect(store.saved.last?.password == "secret")
        #expect(viewModel.pairingCode == "leftover-code")
    }
}

private let qrScanFailureMessage = "QR didn't connect. OpenCode 2's pairing API is still changing, so a scan can fail while this app is working. Enter the host and connect. Password is optional. A pairing code works too."

private let pairingCodeJSON = """
{"code":"abc_DEF-123","urls":["http://192.168.1.2:49374","http://100.64.0.3:49374"]}
"""

@MainActor
private func makePairingSetupViewModel(
    probe: ConnectionProbing,
    redeemer: PairingCodeRedeeming,
    store: RecordingConnectionProfileStore
) -> ConnectionSetupViewModel {
    ConnectionSetupViewModel(
        connectionProbeService: probe,
        connectionSessionCoordinator: ConnectionSessionCoordinator(
            activeConnectionManager: ActiveConnectionManager(),
            connectionProfileStore: store
        ),
        pairingCodeRedeemer: redeemer,
        mode: .add
    )
}

private struct FakeConnectionProbeService: ConnectionProbing {
    let result: Result<ServerProtocol, Error>

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        try result.get()
    }
}

private final class RecordingURLProbeService: ConnectionProbing, @unchecked Sendable {
    private let lock = NSLock()
    private var probedURLs: [String] = []
    var probed: [String] {
        lock.withLock { probedURLs }
    }

    private let failingHosts: Set<String>
    private let authFailingHosts: Set<String>
    private let successProtocol: ServerProtocol

    init(
        failingHosts: Set<String> = [],
        authFailingHosts: Set<String> = [],
        successProtocol: ServerProtocol = .v1
    ) {
        self.failingHosts = failingHosts
        self.authFailingHosts = authFailingHosts
        self.successProtocol = successProtocol
    }

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        let url = connection.baseURL.absoluteString
        record(url)
        if authFailingHosts.contains(where: { url.contains($0) }) {
            throw ServerProbeError.http(statusCode: 401, message: "Unauthorized")
        }
        if failingHosts.contains(where: { url.contains($0) }) {
            throw ServerProbeError.transport("down")
        }
        return successProtocol
    }

    private func record(_ url: String) {
        lock.withLock { probedURLs.append(url) }
    }
}

private final class ScriptedHostProbe: ConnectionProbing, @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [String] = []
    var probed: [String] {
        lock.withLock { recorded }
    }

    private let transportHosts: Set<String>
    private let defaultUnauthenticated: Result<ServerProtocol, Error>
    private let authenticated: Result<ServerProtocol, Error>

    init(
        transportHosts: Set<String> = [],
        defaultUnauthenticated: Result<ServerProtocol, Error> = .failure(
            ServerProbeError.http(statusCode: 401, message: "Unauthorized")
        ),
        authenticated: Result<ServerProtocol, Error> = .success(.v2)
    ) {
        self.transportHosts = transportHosts
        self.defaultUnauthenticated = defaultUnauthenticated
        self.authenticated = authenticated
    }

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        let url = connection.baseURL.absoluteString
        lock.withLock { recorded.append(url) }
        if !(connection.password ?? "").isEmpty {
            return try authenticated.get()
        }
        if transportHosts.contains(where: { url.contains($0) }) {
            throw ServerProbeError.transport("down")
        }
        return try defaultUnauthenticated.get()
    }
}

private final class ScriptedPairingRedeemer: PairingCodeRedeeming, @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [(baseURL: String, code: String)] = []
    var calls: [(baseURL: String, code: String)] {
        lock.withLock { recorded }
    }

    private let token: String
    private let error: Error?

    init(token: String = "paired-token", error: Error? = nil) {
        self.token = token
        self.error = error
    }

    func redeem(baseURL: String, code: String) async throws -> String {
        lock.withLock { recorded.append((baseURL, code)) }
        if let error {
            throw error
        }
        return token
    }
}

private final class RecordingConnectionProfileStore: ConnectionProfileStore, @unchecked Sendable {
    var saved: [ConnectionProfile] = []

    func activeProfile() async -> ConnectionProfile? { saved.last }
    func saveProfile(_ profile: ConnectionProfile) async throws { saved.append(profile) }
    func addedWorktrees(serverBaseURL: String) async -> [String] { [] }
    func addWorktree(serverBaseURL: String, worktree: String) async {}
    func removeWorktree(serverBaseURL: String, worktree: String) async {}
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}
    func clearAll() async { saved.removeAll() }
}

private struct FakeSetupConnectionProfileStore: ConnectionProfileStore {
    func activeProfile() async -> ConnectionProfile? { nil }
    func saveProfile(_ profile: ConnectionProfile) async {}
    func addedWorktrees(serverBaseURL: String) async -> [String] { [] }
    func addWorktree(serverBaseURL: String, worktree: String) async {}
    func removeWorktree(serverBaseURL: String, worktree: String) async {}
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}
    func clearAll() async {}
}
