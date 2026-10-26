import Foundation

/// v2 features that depend on the server release, read from its API contract.
nonisolated struct V2ServerFeatures: Equatable, Sendable {
    var sessionArchive = false
    var sessionTurnDiff = false
    var sessionGenerate = false
}

/// Reads release-dependent v2 support from the server's OpenAPI document (`/openapi.json`).
///
/// opencode 2.1.0 added `archived` to the `PATCH /api/session/{sessionID}` body and the
/// per-turn `GET /api/session/{sessionID}/diff` route. Older 2.x servers answer that PATCH
/// with 204 and ignore the field, `time.archived` predates 2.1, and `/api/info` reports
/// `local` or `0.0.0-<channel>-<n>` for source and preview builds, so the contract is the
/// reliable signal. `POST /api/session/{sessionID}/generate` (`/btw`) shipped in 2.0.x, so
/// earlier 2.x servers lack it too.
nonisolated enum V2ServerContract {
    static let openAPIPath = "openapi.json"

    private static let sessionPath = "/api/session/{sessionID}"
    private static let sessionDiffPath = "/api/session/{sessionID}/diff"
    private static let sessionGeneratePath = "/api/session/{sessionID}/generate"

    static func features(data: Data) throws -> V2ServerFeatures {
        guard let document = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return V2ServerFeatures()
        }
        return features(document)
    }

    static func features(_ document: [String: Any]) -> V2ServerFeatures {
        V2ServerFeatures(
            sessionArchive: declaresSessionArchive(document),
            sessionTurnDiff: declaresSessionTurnDiff(document),
            sessionGenerate: declaresSessionGenerate(document)
        )
    }

    static func declaresSessionArchive(_ document: [String: Any]) -> Bool {
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

    static func declaresSessionTurnDiff(_ document: [String: Any]) -> Bool {
        object(document["paths"])
            .flatMap { object($0[sessionDiffPath]) }
            .flatMap { object($0["get"]) } != nil
    }

    static func declaresSessionGenerate(_ document: [String: Any]) -> Bool {
        object(document["paths"])
            .flatMap { object($0[sessionGeneratePath]) }
            .flatMap { object($0["post"]) } != nil
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
