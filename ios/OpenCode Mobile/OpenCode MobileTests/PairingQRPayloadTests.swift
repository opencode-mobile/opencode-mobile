import Foundation
import Testing
@testable import OpenCode_Mobile

struct PairingQRPayloadTests {
    @Test func parse_selectsFirstNonLoopbackURL() {
        let raw = """
        {"urls":["http://127.0.0.1:4096","http://192.168.0.160:4096"],"username":"opencode","password":"secret"}
        """
        let result = PairingQRPayload.parse(raw)
        guard case let .success(credentials) = result else {
            Issue.record("expected success")
            return
        }
        #expect(credentials.baseURL == "http://192.168.0.160:4096/")
        #expect(credentials.urls == ["http://192.168.0.160:4096/"])
        #expect(credentials.username == "opencode")
        #expect(credentials.password == "secret")
    }

    @Test func parse_keepsLanAndPrefersTailscaleForDisplay() {
        let raw = """
        {"urls":["http://192.168.0.160:4096","http://100.84.57.15:4096"],"username":"opencode","password":"secret"}
        """
        let result = PairingQRPayload.parse(raw)
        guard case let .success(credentials) = result else {
            Issue.record("expected success")
            return
        }
        #expect(credentials.baseURL == "http://100.84.57.15:4096/")
        #expect(credentials.urls == [
            "http://192.168.0.160:4096/",
            "http://100.84.57.15:4096/",
        ])
    }

    @Test func parse_prefersMagicDNSTailscaleHost() {
        let raw = """
        {"urls":["http://192.168.0.160:4096","http://mybox.tailxyz.ts.net:4096"],"username":"opencode","password":"secret"}
        """
        let result = PairingQRPayload.parse(raw)
        guard case let .success(credentials) = result else {
            Issue.record("expected success")
            return
        }
        #expect(credentials.baseURL == "http://mybox.tailxyz.ts.net:4096/")
    }

    @Test func pickReachableURL_prefersFieldWhenReachable() {
        let picked = PairingQRPayload.pickReachableURL(
            fieldURL: "http://192.168.0.160:4096/",
            reachable: [
                "http://192.168.0.160:4096/",
                "http://100.84.57.15:4096/",
            ]
        )
        #expect(picked == "http://192.168.0.160:4096/")
    }

    @Test func pickReachableURL_fallsBackToLanWhenTailscaleMissing() {
        let picked = PairingQRPayload.pickReachableURL(
            fieldURL: "http://100.84.57.15:4096/",
            reachable: ["http://192.168.0.160:4096/"]
        )
        #expect(picked == "http://192.168.0.160:4096/")
    }

    @Test func parse_stripsPathFromPairingURL() {
        let raw = """
        {"urls":["http://192.168.0.160:4096/session"],"username":"opencode","password":"secret"}
        """
        let result = PairingQRPayload.parse(raw)
        guard case let .success(credentials) = result else {
            Issue.record("expected success")
            return
        }
        #expect(credentials.baseURL == "http://192.168.0.160:4096/")
    }

    @Test func parse_onlyLoopback_isUnreachable() {
        let raw = """
        {"urls":["http://localhost:4096","http://127.0.0.1:4096"],"username":"opencode","password":"x"}
        """
        let result = PairingQRPayload.parse(raw)
        guard case let .failure(error) = result else {
            Issue.record("expected failure")
            return
        }
        #expect(error == .noReachableURL)
    }

    @Test func parse_garbage_isInvalid() {
        let result = PairingQRPayload.parse("not-json")
        guard case let .failure(error) = result else {
            Issue.record("expected failure")
            return
        }
        #expect(error == .invalidPayload)
    }

    @Test func parse_missingUsername_defaultsToOpencode() {
        let raw = """
        {"urls":["http://10.0.0.9:4096"],"password":"pw"}
        """
        let result = PairingQRPayload.parse(raw)
        guard case let .success(credentials) = result else {
            Issue.record("expected success")
            return
        }
        #expect(credentials.username == "opencode")
        #expect(credentials.password == "pw")
    }
}
