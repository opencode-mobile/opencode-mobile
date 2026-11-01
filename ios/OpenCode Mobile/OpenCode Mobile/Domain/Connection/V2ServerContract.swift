import Foundation

/// v2 features that depend on the server release, read from its API contract.
nonisolated struct V2ServerFeatures: Equatable, Sendable {
    var sessionArchive = false
    var sessionTurnDiff = false
    var sessionGenerate = false
    /// `GET /api/shell`: agent-run shells can be listed, followed and stopped (#907).
    var agentShells = false
    /// `POST /api/experimental/fs/write` matches the shape the upload sends (#644). The flag only
    /// turns on together with `uploadDirectory`, the server's `paths.tmp` from `/api/info`.
    var streamedUploadRoute = false
    /// `paths.tmp` from `/api/info`; uploads go under `<tmp>/uploads/<uuid>/`.
    var uploadDirectory: String?
    /// The route is declared but `/api/info` could not be read, so `uploadDirectory` is unknown
    /// rather than absent. Such a result is not cached.
    var uploadDirectoryUnread = false

    var streamedUpload: Bool { streamedUploadRoute && uploadDirectory?.isEmpty == false }
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
    private static let fsWritePath = "/api/experimental/fs/write"
    private static let shellListPath = "/api/shell"

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
            sessionGenerate: declaresSessionGenerate(document),
            agentShells: declaresShellList(document),
            streamedUploadRoute: declaresStreamedUpload(document)
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

    static func declaresShellList(_ document: [String: Any]) -> Bool {
        object(document["paths"])
            .flatMap { object($0[shellListPath]) }
            .flatMap { object($0["get"]) } != nil
    }

    /// The upload route still has the shape we send: a required `path` query parameter, an
    /// `application/octet-stream` body, and a 200 response whose `data.path` resolves.
    static func declaresStreamedUpload(_ document: [String: Any]) -> Bool {
        guard let post = object(document["paths"]).flatMap({ object($0[fsWritePath]) }).flatMap({ object($0["post"]) })
        else { return false }
        let parameters = (post["parameters"] as? [Any] ?? []).compactMap { entry -> [String: Any]? in
            guard let parameter = object(entry) else { return nil }
            if parameter["$ref"] != nil { return resolve(parameter, in: document) }
            return parameter
        }
        let requiresPath = parameters.contains {
            ($0["name"] as? String) == "path" && ($0["in"] as? String) == "query" && ($0["required"] as? Bool) == true
        }
        guard requiresPath else { return false }
        let hasOctetBody = object(post["requestBody"])
            .flatMap { resolve($0, in: document) }
            .flatMap { object($0["content"]) }
            .flatMap { object($0["application/octet-stream"]) } != nil
        guard hasOctetBody else { return false }
        let responseSchema = object(post["responses"])
            .flatMap { object($0["200"]) }
            .flatMap { resolve($0, in: document) }
            .flatMap { object($0["content"]) }
            .flatMap { object($0["application/json"]) }
            .flatMap { object($0["schema"]) }
            .flatMap { resolve($0, in: document) }
        guard let responseSchema, let data = property("data", of: responseSchema, in: document) else { return false }
        return property("path", of: data, in: document) != nil
    }

    private static func property(_ name: String, of schema: [String: Any], in document: [String: Any]) -> [String: Any]? {
        if let properties = object(schema["properties"]), let value = object(properties[name]) {
            return resolve(value, in: document)
        }
        // `allOf` / `anyOf` wrappers around the object schema.
        for key in ["allOf", "anyOf", "oneOf"] {
            for entry in schema[key] as? [Any] ?? [] {
                if let entry = object(entry).flatMap({ resolve($0, in: document) }),
                   let found = property(name, of: entry, in: document)
                {
                    return found
                }
            }
        }
        return nil
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
