import Foundation

nonisolated struct TerminalConnectRequest: Sendable, Equatable {
    let url: String
    let authorization: String?

    init(url: String, authorization: String? = nil) {
        self.url = url
        self.authorization = authorization
    }

    static func build(
        id: String,
        directory: String,
        serverProtocol: ServerProtocol,
        baseURL: String,
        cursor: String,
        ticket: String? = nil,
        username: String,
        password: String
    ) -> TerminalConnectRequest {
        let isV1 = serverProtocol == .v1
        let path = isV1 ? "/pty/\(id)/connect" : "/api/pty/\(id)/connect"
        var trimmed = baseURL
        while trimmed.hasSuffix("/") {
            trimmed.removeLast()
        }
        guard var components = URLComponents(string: trimmed) else {
            return TerminalConnectRequest(url: "")
        }
        let basePath = components.path.hasSuffix("/") ? String(components.path.dropLast()) : components.path
        components.path = basePath + path
        if components.scheme?.lowercased() == "https" || components.scheme?.lowercased() == "wss" {
            components.scheme = "wss"
        } else {
            components.scheme = "ws"
        }
        var pairs: [(String, String)] = []
        if isV1 {
            pairs.append(("directory", directory))
        } else {
            pairs.append(("location[directory]", directory))
        }
        pairs.append(("cursor", cursor))
        // Credentials travel only in the `Authorization` header, which both protocols accept on
        // the upgrade request. Query strings end up in tunnel, proxy, and server access logs, so
        // the server's `auth_token` query fallback (meant for browsers) is never used here.
        let token: String?
        if let ticket, !ticket.isEmpty {
            pairs.append(("ticket", ticket))
            token = nil
        } else if !password.isEmpty {
            let user = username.isEmpty ? "opencode" : username
            token = basicToken(username: user, password: password)
        } else {
            token = nil
        }
        components.percentEncodedQuery = pairs
            .map { "\(queryEncode($0.0))=\(queryEncode($0.1))" }
            .joined(separator: "&")
        return TerminalConnectRequest(
            url: components.url?.absoluteString ?? "",
            authorization: token.map { "Basic \($0)" }
        )
    }
}

nonisolated func connectRequest(
    id: String,
    directory: String,
    serverProtocol: ServerProtocol,
    baseURL: String,
    cursor: String,
    ticket: String? = nil,
    username: String,
    password: String
) -> TerminalConnectRequest {
    TerminalConnectRequest.build(
        id: id,
        directory: directory,
        serverProtocol: serverProtocol,
        baseURL: baseURL,
        cursor: cursor,
        ticket: ticket,
        username: username,
        password: password
    )
}

private nonisolated func basicToken(username: String, password: String) -> String {
    Data("\(username):\(password)".utf8).base64EncodedString()
}

private nonisolated func queryEncode(_ value: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
}
