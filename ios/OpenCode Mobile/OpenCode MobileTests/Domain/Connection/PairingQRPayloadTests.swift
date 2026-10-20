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

    @Test func chooseAnsweredURL_prefersTailscaleWhenBothAnswered() {
        let picked = PairingQRPayload.chooseAnsweredURL(
            candidates: [
                "http://192.168.0.160:4096/",
                "http://100.84.57.15:4096/",
            ],
            answered: [
                "http://192.168.0.160:4096/",
                "http://100.84.57.15:4096/",
            ]
        )
        #expect(picked == "http://100.84.57.15:4096/")
    }

    @Test func chooseAnsweredURL_neverReplacesHttpsWithHttp() {
        let picked = PairingQRPayload.chooseAnsweredURL(
            candidates: [
                "https://box.opentunnel.xyz/",
                "http://192.168.0.160:4096/",
                "http://100.84.57.15:4096/",
            ],
            answered: [
                "http://192.168.0.160:4096/",
                "http://100.84.57.15:4096/",
            ],
            currentBaseURL: "https://box.opentunnel.xyz/"
        )
        #expect(picked == nil)
    }

    @Test func chooseAnsweredURL_httpsCurrentStillPicksAnsweringHttps() {
        let picked = PairingQRPayload.chooseAnsweredURL(
            candidates: [
                "https://box.opentunnel.xyz/",
                "http://100.84.57.15:4096/",
            ],
            answered: [
                "https://box.opentunnel.xyz/",
                "http://100.84.57.15:4096/",
            ],
            currentBaseURL: "https://box.opentunnel.xyz/"
        )
        #expect(picked == "https://box.opentunnel.xyz/")
    }

    @Test func chooseAnsweredURL_httpCurrentMayMoveToHttps() {
        let picked = PairingQRPayload.chooseAnsweredURL(
            candidates: ["http://192.168.0.160:4096/", "https://box.opentunnel.xyz/"],
            answered: ["https://box.opentunnel.xyz/"],
            currentBaseURL: "http://192.168.0.160:4096/"
        )
        #expect(picked == "https://box.opentunnel.xyz/")
    }

    @Test func chooseAnsweredURL_usesLanWhenTailscaleDidNotAnswer() {
        let picked = PairingQRPayload.chooseAnsweredURL(
            candidates: [
                "http://192.168.0.160:4096/",
                "http://100.84.57.15:4096/",
            ],
            answered: ["http://192.168.0.160:4096/"]
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

    @Test func parse_connectLink_readsOriginCodeAndEmptyPassword() {
        let result = PairingQRPayload.parse(
            " http://192.168.1.2:49374/auth/connect/abc_DEF-123 "
        )
        guard case let .success(credentials) = result else {
            Issue.record("expected success")
            return
        }
        #expect(credentials.baseURL == "http://192.168.1.2:49374/")
        #expect(credentials.urls == ["http://192.168.1.2:49374/"])
        #expect(credentials.code == "abc_DEF-123")
        #expect(credentials.username == "opencode")
        #expect(credentials.password == "")
    }

    @Test func parse_codeJSON_prefersTailscaleAndKeepsBothURLs() {
        let raw = """
        {"code":"abc_DEF-123","urls":["http://192.168.1.2:49374","http://100.64.0.3:49374"]}
        """
        let result = PairingQRPayload.parse(raw)
        guard case let .success(credentials) = result else {
            Issue.record("expected success")
            return
        }
        #expect(credentials.baseURL == "http://100.64.0.3:49374/")
        #expect(credentials.urls == [
            "http://192.168.1.2:49374/",
            "http://100.64.0.3:49374/",
        ])
        #expect(credentials.code == "abc_DEF-123")
        #expect(credentials.username == "opencode")
        #expect(credentials.password == "")
    }

    @Test func parse_codeJSON_ignoresEmbeddedPassword() {
        let raw = """
        {"code":"abc","urls":["http://10.0.0.8:4096"],"username":"other","password":"secret"}
        """
        let result = PairingQRPayload.parse(raw)
        guard case let .success(credentials) = result else {
            Issue.record("expected success")
            return
        }
        #expect(credentials.code == "abc")
        #expect(credentials.username == "opencode")
        #expect(credentials.password == "")
        #expect(credentials.baseURL == "http://10.0.0.8:4096/")
    }

    @Test func parse_codeJSON_onlyLoopback_isUnreachable() {
        let raw = """
        {"code":"abc","urls":["http://127.0.0.1:4096","http://localhost:4096"]}
        """
        let result = PairingQRPayload.parse(raw)
        guard case let .failure(error) = result else {
            Issue.record("expected failure")
            return
        }
        #expect(error == .noReachableURL)
    }

    @Test func parse_newFormat_rejectsInvalidPayloads() {
        let samples = [
            "http://192.168.1.2:49374/auth/connect/",
            "http://192.168.1.2:49374/auth/connect/abc_DEF-123/extra",
            "opencode-ios://192.168.1.2/auth/connect/abc",
            "192.168.1.2:49374",
            #"{"code":"abc_DEF-123","urls":[]}"#,
            #"{"code":"a/b","urls":["http://192.168.1.2:49374"]}"#,
            #"{"code":"abc","urls":["ftp://192.168.1.2:49374"]}"#,
            "{not json",
            "http://user:pass@192.168.1.2:49374/auth/connect/abc",
            "http://192.168.1.2:49374/auth/connect/abc?x=1",
            "http://192.168.1.2:49374/auth/connect/abc#frag",
        ]
        for sample in samples {
            guard PairingQRPayload.parse(sample) == .failure(.invalidPayload) else {
                Issue.record("expected invalidPayload for \(sample)")
                continue
            }
        }
    }

    @Test func isOpenTunnelURL_matchesOpentunnelHostOnly() {
        #expect(PairingQRPayload.isOpenTunnelURL("https://ab12.cd34.opentunnel.xyz/"))
        #expect(PairingQRPayload.isOpenTunnelURL("https://Ab.Cd.OPENTUNNEL.XYZ"))
        #expect(PairingQRPayload.isOpenTunnelURL("http://route.tunnel.opentunnel.xyz:443"))
        #expect(PairingQRPayload.isOpenTunnelURL("http://192.168.0.160:4096/") == false)
        #expect(PairingQRPayload.isOpenTunnelURL("http://mybox.tailxyz.ts.net:4096/") == false)
        #expect(PairingQRPayload.isOpenTunnelURL("https://opentunnel.xyz/") == false)
        #expect(PairingQRPayload.isTailscaleURL("https://ab12.cd34.opentunnel.xyz/") == false)
    }

    @Test func parse_legacyPasswordQR_keepsPasswordAndOmitsCode() {
        let raw = """
        {"urls":["http://127.0.0.1:4096","http://192.168.0.160:4096"],"username":"opencode","password":"secret"}
        """
        let result = PairingQRPayload.parse(raw)
        guard case let .success(credentials) = result else {
            Issue.record("expected success")
            return
        }
        #expect(credentials.password == "secret")
        #expect(credentials.code == nil)
        #expect(credentials.baseURL == "http://192.168.0.160:4096/")
    }
}
