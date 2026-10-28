import Foundation
import Testing
@testable import OpenCode_Mobile

struct ExternalLinkPolicyTests {
    @Test(arguments: ["https://opencode.ai/docs", "http://192.168.0.1:4096/status", "HTTPS://Example.com"])
    func webLinks_open(raw: String) {
        #expect(ExternalLinkPolicy.openableURL(raw) != nil)
    }

    @Test(arguments: [
        "tel:+15555550100",
        "file:///etc/passwd",
        "shortcuts://run-shortcut?name=x",
        "javascript:alert(1)",
        "mailto:someone@example.com",
        "opencode-mobile://connect",
        "",
        "   ",
        "not a url",
    ])
    func otherSchemes_areDropped(raw: String) {
        #expect(ExternalLinkPolicy.openableURL(raw) == nil)
    }

    @Test func isOpenable_checksTheSchemeOnly() {
        #expect(ExternalLinkPolicy.isOpenable(URL(string: "http://example.com")!))
        #expect(ExternalLinkPolicy.isOpenable(URL(string: "sms:5551234")!) == false)
    }
}
