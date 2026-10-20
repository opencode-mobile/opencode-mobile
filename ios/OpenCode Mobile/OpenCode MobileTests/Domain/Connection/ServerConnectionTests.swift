import Foundation
import Testing
@testable import OpenCode_Mobile

struct ServerConnectionTests {
    @Test func parseableProfile_buildsTheConnection() {
        let connection = ServerConnection(
            profile: ConnectionProfile(baseURL: "http://192.168.0.5:4096", username: "opencode", password: "x")
        )
        #expect(connection?.baseURL.absoluteString == "http://192.168.0.5:4096/")
        #expect(connection?.password == "x")
    }

    @Test(arguments: ["", "   ", "http://", "not a url at all", "http://[::1"])
    func unparseableProfile_hasNoConnectionAndNoLocalhostFallback(baseURL: String) {
        let connection = ServerConnection(
            profile: ConnectionProfile(baseURL: baseURL, username: "opencode", password: "secret")
        )
        #expect(connection == nil)
    }

    @Test func manager_withUnparseableAddress_doesNotConnect() {
        let manager = ActiveConnectionManager()
        manager.apply(ConnectionProfile(baseURL: "http://", username: "opencode", password: "secret"))
        #expect(manager.connection == nil)
        #expect(manager.currentProfile()?.password == "secret")
    }
}
