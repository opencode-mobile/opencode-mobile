import Foundation
import Testing
@testable import Hex_Code

/// Welcome and Settings › App › Privacy policy open Hex Code's own policy, not OpenCode Mobile's.
struct HexPrivacyPolicyTests {
    @Test func linksHexCodesPolicyOverHttps() {
        #expect(HexPrivacyPolicy.url.absoluteString == "https://opencode-mobile.github.io/opencode-mobile-docs/hexcode/privacy/")
    }
}
