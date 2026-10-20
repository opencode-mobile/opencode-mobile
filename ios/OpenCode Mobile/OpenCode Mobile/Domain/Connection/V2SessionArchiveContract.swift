import Foundation

/// Reads v2 session archive support from the server's OpenAPI document (`/openapi.json`).
///
/// opencode 2.1.0 added `archived` to the `PATCH /api/session/{sessionID}` body. Older 2.x
/// servers answer that PATCH with 204 and ignore the field, `time.archived` predates 2.1,
/// and `/api/info` reports `local` or `0.0.0-<channel>-<n>` for source and preview builds,
/// so the contract is the reliable signal.
nonisolated enum V2SessionArchiveContract {
    static let openAPIPath = "openapi.json"

    private static let sessionPath = "/api/session/{sessionID}"

    static func isDeclared(data: Data) throws -> Bool {
        guard let document = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return false
        }
        return isDeclared(document)
    }

    static func isDeclared(_ document: [String: Any]) -> Bool {
        let body = object(document["paths"])
            .flatMap { object($0[sessionPath]) }
            .flatMap { object($0["patch"]) }
            .flatMap { object($0["requestBody"]) }
            .flatMap { object($0["content"]) }
            .flatMap { object($0["application/json"]) }
            .flatMap { object($0["schema"]) }
        guard let body, let schema = resolve(body, in: document) else { return false }
        return object(schema["properties"])?["archived"] != nil
    }

    /// Follows a local `#/components/...` reference; other schemas are returned as is.
    private static func resolve(_ schema: [String: Any], in document: [String: Any]) -> [String: Any]? {
        guard let ref = schema["$ref"] as? String else { return schema }
        guard ref.hasPrefix("#/") else { return nil }
        var node: [String: Any]? = document
        for key in ref.dropFirst(2).split(separator: "/") {
            node = node.flatMap { object($0[String(key)]) }
        }
        return node
    }

    private static func object(_ value: Any?) -> [String: Any]? {
        value as? [String: Any]
    }
}
