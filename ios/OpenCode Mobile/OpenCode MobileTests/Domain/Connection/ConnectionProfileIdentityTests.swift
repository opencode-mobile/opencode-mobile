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

    @Test func hasSameIdentity_matchesWhenBaseURLMovesWithinServerURLs() {
        let lan = "http://192.168.1.2:49374/"
        let tailscale = "http://100.64.0.3:49374/"
        let urls = [lan, tailscale]
        let original = ServerConnection(
            profile: ConnectionProfile(
                baseURL: lan,
                serverURLs: urls,
                username: "opencode",
                password: "token",
                id: "profile-1"
            )
        )
        let moved = ServerConnection(
            profile: ConnectionProfile(
                baseURL: tailscale,
                serverURLs: urls,
                username: "opencode",
                password: "token",
                id: "profile-1"
            )
        )
        #expect(original.hasSameIdentity(as: moved))

        let otherPassword = ServerConnection(
            profile: ConnectionProfile(
                baseURL: tailscale,
                serverURLs: urls,
                username: "opencode",
                password: "other",
                id: "profile-1"
            )
        )
        #expect(!original.hasSameIdentity(as: otherPassword))
    }

    @Test func hasSameIdentity_manualProfileStillRequiresMatchingBaseURL() {
        let left = ServerConnection(
            profile: ConnectionProfile(
                baseURL: "http://10.0.0.1:4096/",
                username: "opencode",
                password: "token",
                id: "profile-1"
            )
        )
        let right = ServerConnection(
            profile: ConnectionProfile(
                baseURL: "http://10.0.0.2:4096/",
                username: "opencode",
                password: "token",
                id: "profile-1"
            )
        )
        #expect(!left.hasSameIdentity(as: right))
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
