import Foundation
import Testing
@testable import OpenCode_Mobile

struct ServerProtocolDisplayTests {
    @Test func v1Labels() {
        #expect(ServerProtocol.v1.shortLabel == "v1")
        #expect(ServerProtocol.v1.apiCaption == "API v1")
        #expect(ServerProtocol.v1.reachableMessage == "Server reachable · API v1")
    }

    @Test func v2Labels() {
        #expect(ServerProtocol.v2.shortLabel == "v2")
        #expect(ServerProtocol.v2.apiCaption == "API v2")
        #expect(ServerProtocol.v2.reachableMessage == "Server reachable · API v2")
    }
}

struct ConnectionProfileListMetaTests {
    @Test func activeWithProtocol_captionAndActivePill() {
        let profile = profile(api: .v2)
        #expect(ConnectionProfileListMeta.caption(profile, isActive: true) == "API v2")
        #expect(ConnectionProfileListMeta.status(profile, isActive: true)?.text == "Active")
    }

    @Test func otherWithProtocol_compactInfoPill() {
        let profile = profile(api: .v1)
        #expect(ConnectionProfileListMeta.caption(profile, isActive: false) == nil)
        #expect(ConnectionProfileListMeta.status(profile, isActive: false)?.text == "v1")
    }

    @Test func unknownProtocol_omitsApiIndicator() {
        let profile = profile(api: nil)
        #expect(ConnectionProfileListMeta.caption(profile, isActive: true) == nil)
        #expect(ConnectionProfileListMeta.caption(profile, isActive: false) == nil)
        #expect(ConnectionProfileListMeta.status(profile, isActive: true)?.text == "Active")
        #expect(ConnectionProfileListMeta.status(profile, isActive: false) == nil)
    }

    private func profile(api: ServerProtocol?) -> ConnectionProfile {
        ConnectionProfile(
            baseURL: "http://192.168.1.42:4096/",
            username: "opencode",
            password: "",
            serverProtocol: api,
            id: "mac-mini",
            name: "Mac Mini"
        )
    }
}
