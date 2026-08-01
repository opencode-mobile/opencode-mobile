import Testing
@testable import OpenCode_Mobile

struct ServerAddressInputParsingTests {
    @Test
    func parseDefaultsToHttpAndStripsPath() {
        let parts = ServerAddressInputParsing.parse(value: "http://127.0.0.1:4096/extra")
        #expect(parts.scheme == .http)
        #expect(parts.hostPort == "127.0.0.1:4096")
        #expect(parts.toBaseURL() == "http://127.0.0.1:4096/")
    }

    @Test
    func parseDetectsHttps() {
        let parts = ServerAddressInputParsing.parse(value: "HTTPS://192.168.1.10:8080/")
        #expect(parts.scheme == .https)
        #expect(parts.hostPort == "192.168.1.10:8080")
    }

    @Test
    func filterStripsDisallowedCharacters() {
        #expect(ServerAddressInputParsing.filterHostPort("10.0.2.2:4096 host!") == "10.0.2.2:4096host")
        #expect(ServerAddressInputParsing.filterHostPort("[::1]:4096") == "[::1]:4096")
        #expect(ServerAddressInputParsing.filterHostPort("macbook.local:4096") == "macbook.local:4096")
        #expect(ServerAddressInputParsing.filterHostPort("my-host_01.lan!") == "my-host_01.lan")
    }

    @Test
    func parseKeepsHostname() {
        let parts = ServerAddressInputParsing.parse(value: "https://macbook.local:8080/")
        #expect(parts.scheme == .https)
        #expect(parts.hostPort == "macbook.local:8080")
        #expect(parts.toBaseURL() == "https://macbook.local:8080/")
    }

    @Test
    func hostEditPasteFullUrlUpdatesScheme() {
        let parts = ServerAddressInputParsing.applyHostEdit(
            "https://10.0.0.5:443/",
            currentScheme: .http
        )
        #expect(parts.scheme == .https)
        #expect(parts.hostPort == "10.0.0.5:443")
        #expect(parts.toBaseURL() == "https://10.0.0.5:443/")
    }

    @Test
    func hostEditWithoutSchemeKeepsCurrentScheme() {
        let parts = ServerAddressInputParsing.applyHostEdit(
            "127.0.0.1:4096",
            currentScheme: .https
        )
        #expect(parts.scheme == .https)
        #expect(parts.hostPort == "127.0.0.1:4096")
    }

    @Test
    func emptyHostEmitsSchemePrefixOnly() {
        #expect(ServerAddressParts(scheme: .http, hostPort: "").toBaseURL() == "http://")
    }
}
