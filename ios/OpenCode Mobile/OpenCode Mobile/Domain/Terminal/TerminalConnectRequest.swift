import Foundation

nonisolated struct TerminalConnectRequest: Sendable, Equatable {
    let url: String

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
        if let ticket, !ticket.isEmpty {
            pairs.append(("ticket", ticket))
        } else if isV1, !password.isEmpty {
            let user = username.isEmpty ? "opencode" : username
            pairs.append(("auth_token", basicToken(username: user, password: password)))
        }
        components.percentEncodedQuery = pairs
            .map { "\(queryEncode($0.0))=\(queryEncode($0.1))" }
            .joined(separator: "&")
        return TerminalConnectRequest(url: components.url?.absoluteString ?? "")
    }
}

func connectRequest(
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

private func basicToken(username: String, password: String) -> String {
    Data("\(username):\(password)".utf8).base64EncodedString()
}

private func queryEncode(_ value: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
}
