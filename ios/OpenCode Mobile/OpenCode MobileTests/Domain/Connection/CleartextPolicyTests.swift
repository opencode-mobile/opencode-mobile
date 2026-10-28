import Foundation
import Testing
@testable import OpenCode_Mobile

struct CleartextPolicyTests {
    @Test(arguments: [
        "http://127.0.0.1:4096/",
        "http://localhost:4096",
        "http://[::1]:4096/",
        "http://192.168.1.60:4096/",
        "http://10.0.2.2:4096/",
        "http://172.16.4.9:4096/",
        "http://172.31.255.1:4096/",
        "http://169.254.10.1:4096/",
        "http://100.64.0.3:49374/",
        "http://100.127.255.254:4096/",
        "http://[fd7a:115c:a1e0::a033:5957]:4096/",
        "http://[fe80::1%25en0]:4096/",
        "http://mybox.tail1234.ts.net:4096/",
        "http://mybox.local:4096/",
        "http://mybox:4096/",
        "http://mybox.lan:4096/",
        "http://nas.home.arpa:4096/",
    ])
    func trustedCleartext_connectsSilently(url: String) {
        #expect(CleartextPolicy.verdict(for: url) == .trustedCleartext)
    }

    @Test(arguments: [
        "http://203.0.113.5:4096/",
        "http://8.8.8.8/",
        "http://172.32.0.1:4096/",
        "http://100.128.0.1:4096/",
        "http://devbox.example.com:4096/",
        "http://box.opentunnel.xyz/",
        "http://[2001:db8::1]:4096/",
    ])
    func untrustedCleartext_needsAWarning(url: String) {
        #expect(CleartextPolicy.verdict(for: url) == .untrustedCleartext)
    }

    @Test(arguments: ["https://box.opentunnel.xyz/", "https://203.0.113.5:4096/", "wss://devbox.example.com/"])
    func encrypted_isAlwaysFine(url: String) {
        #expect(CleartextPolicy.verdict(for: url) == .encrypted)
    }

    @Test func untrustedCleartextHosts_listsOnlyTheRiskyOnesWithPorts() {
        let hosts = CleartextPolicy.untrustedCleartextHosts(in: [
            "http://192.168.1.60:4096/",
            "http://203.0.113.5:4096/",
            "https://box.opentunnel.xyz/",
            "http://devbox.example.com/",
            "http://203.0.113.5:4096",
        ])
        #expect(hosts == ["203.0.113.5:4096", "devbox.example.com"])
    }

    @Test func warningMessage_namesTheHosts() {
        let message = CleartextPolicy.warningMessage(hosts: ["203.0.113.5:4096"])
        #expect(message.hasPrefix("203.0.113.5:4096 uses plain http"))
        #expect(CleartextPolicy.warningMessage(hosts: ["a", "b"]).hasPrefix("a, b use plain http"))
    }
}
