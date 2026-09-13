import Foundation
import Testing
@testable import OpenCode_Mobile

struct ConnectionProfileIdentityTests {
    @Test func credentialFingerprint_isStableHexPrefix() {
        let first = ConnectionProfileIdentity.credentialFingerprint(username: "opencode", password: "secret")
        let second = ConnectionProfileIdentity.credentialFingerprint(username: "opencode", password: "secret")
        #expect(first == second)
        #expect(first.count == 16)
        #expect(first.allSatisfy { $0.isHexDigit })
        #expect(!first.contains("secret"))
    }

    @Test func credentialFingerprint_changesWithPassword() {
        let original = ConnectionProfileIdentity.credentialFingerprint(username: "opencode", password: "old")
        let updated = ConnectionProfileIdentity.credentialFingerprint(username: "opencode", password: "new")
        #expect(original != updated)
    }

    @Test func sameLiveIdentity_requiresMatchingFingerprint() {
        #expect(
            ConnectionProfileIdentity.sameLiveIdentity(
                leftURL: "http://127.0.0.1:4096/",
                leftUsername: "opencode",
                leftPassword: "a",
                rightURL: "http://127.0.0.1:4096",
                rightUsername: "opencode",
                rightPassword: "a"
            )
        )
        #expect(
            !ConnectionProfileIdentity.sameLiveIdentity(
                leftURL: "http://127.0.0.1:4096/",
                leftUsername: "opencode",
                leftPassword: "a",
                rightURL: "http://127.0.0.1:4096/",
                rightUsername: "opencode",
                rightPassword: "b"
            )
        )
    }

    @Test func sameConnectionIdentity_ignoresPassword() {
        #expect(
            ConnectionProfileIdentity.sameConnectionIdentity(
                leftURL: "http://127.0.0.1:4096/",
                leftUsername: "opencode",
                rightURL: "http://127.0.0.1:4096",
                rightUsername: "opencode"
            )
        )
    }
}
