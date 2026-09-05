import Foundation
import Testing
@testable import OpenCode_Mobile

struct TerminalConnectRequestTests {
    @Test func v1HttpsUsesWssDirectoryAndCursor() throws {
        let request = connectRequest(
            id: "pty_1",
            directory: "/repo",
            serverProtocol: .v1,
            baseURL: "https://example.test/",
            cursor: "0",
            username: "opencode",
            password: ""
        )

        let url = try #require(URL(string: request.url))
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(components.scheme == "wss")
        #expect(components.path == "/pty/pty_1/connect")
        #expect(queryValue(components, "directory") == "/repo")
        #expect(queryValue(components, "cursor") == "0")
        #expect(queryValue(components, "location[directory]") == nil)
        #expect(queryValue(components, "auth_token") == nil)
        #expect(request.authorization == nil)
        #expect(queryValue(components, "ticket") == nil)
    }

    @Test func v2HttpsUsesWssLocationDirectoryCursorAndAuthToken() throws {
        let request = connectRequest(
            id: "pty_2",
            directory: "/repo",
            serverProtocol: .v2,
            baseURL: "https://example.test",
            cursor: "12",
            username: "opencode",
            password: "secret"
        )

        let url = try #require(URL(string: request.url))
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(components.scheme == "wss")
        #expect(components.path == "/api/pty/pty_2/connect")
        #expect(queryValue(components, "location[directory]") == "/repo")
        #expect(queryValue(components, "cursor") == "12")
        #expect(queryValue(components, "directory") == nil)
        let expected = Data("opencode:secret".utf8).base64EncodedString()
        #expect(queryValue(components, "auth_token") == expected)
        #expect(request.authorization == "Basic \(expected)")
        #expect(queryValue(components, "ticket") == nil)
    }

    @Test func v1HttpUsesWsAndAuthToken() throws {
        let request = connectRequest(
            id: "pty_1",
            directory: "/repo",
            serverProtocol: .v1,
            baseURL: "http://127.0.0.1:4096",
            cursor: "3",
            username: "opencode",
            password: "secret"
        )

        let url = try #require(URL(string: request.url))
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(components.scheme == "ws")
        #expect(components.path == "/pty/pty_1/connect")
        let expected = Data("opencode:secret".utf8).base64EncodedString()
        #expect(queryValue(components, "auth_token") == expected)
        #expect(request.authorization == "Basic \(expected)")
        #expect(queryValue(components, "ticket") == nil)
    }

    @Test func ticketOverridesAuthToken() throws {
        let request = connectRequest(
            id: "pty_1",
            directory: "/repo",
            serverProtocol: .v1,
            baseURL: "https://example.test",
            cursor: "0",
            ticket: "tick-1",
            username: "opencode",
            password: "secret"
        )

        let url = try #require(URL(string: request.url))
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(queryValue(components, "ticket") == "tick-1")
        #expect(queryValue(components, "auth_token") == nil)
        #expect(request.authorization == nil)
    }

    @Test func v2TicketSetsTicketWithoutAuthToken() throws {
        let request = connectRequest(
            id: "pty_9",
            directory: "/work",
            serverProtocol: .v2,
            baseURL: "https://example.test",
            cursor: "1",
            ticket: "tick-2",
            username: "opencode",
            password: "secret"
        )

        let url = try #require(URL(string: request.url))
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(components.path == "/api/pty/pty_9/connect")
        #expect(queryValue(components, "location[directory]") == "/work")
        #expect(queryValue(components, "ticket") == "tick-2")
        #expect(queryValue(components, "auth_token") == nil)
        #expect(request.authorization == nil)
    }

    private func queryValue(_ components: URLComponents, _ name: String) -> String? {
        components.queryItems?.first { $0.name == name }?.value
    }
}
