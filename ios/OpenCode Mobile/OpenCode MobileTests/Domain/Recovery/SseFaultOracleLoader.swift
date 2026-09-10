import Foundation

enum SseFaultOracleLoader {
    static let traceIDs = [
        "android-brief-background-same-epoch-stale-latch",
        "cold-resume-double-epoch",
        "ios-profile-a-to-b-old-stream",
        "v1-one-target-silent-while-other-heartbeats",
        "add-project-while-v1-live",
        "v1-to-v2-probe-while-occupying",
        "remove-project-while-v1-live",
        "gap-during-initial-rest",
        "profile-a-to-b-to-a",
        "rest-slow-sse-wins",
        "rest-fail-retries-same-gap",
        "late-subscribe-open-gap",
        "clear-cache-drops-old-observation",
        "sse-parse-failed-opens-gap",
        "v1-instance-lease-lost-then-recovered",
        "v1-growth-then-incumbent-lease-lost",
        "old-generation-stays-writable",
        "path-lost-then-path-ready",
        "background-stop-then-foreground",
    ]

    static func repoRoot(from filePath: String = #filePath) -> URL {
        var url = URL(fileURLWithPath: filePath)
        while url.pathComponents.count > 1 {
            let candidate = url.appendingPathComponent("shared/sse-recovery", isDirectory: true)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return url
            }
            url.deleteLastPathComponent()
        }
        fatalError("Could not find repo root containing shared/sse-recovery from \(filePath)")
    }

    static func recoveryRoot(from filePath: String = #filePath) -> URL {
        repoRoot(from: filePath).appendingPathComponent("shared/sse-recovery", isDirectory: true)
    }

    static func loadTrace(_ id: String, from filePath: String = #filePath) throws -> SseFaultTrace {
        let url = recoveryRoot(from: filePath)
            .appendingPathComponent("traces", isDirectory: true)
            .appendingPathComponent("\(id).json")
        let data = try Data(contentsOf: url)
        return try JSONDecoder().decode(SseFaultTrace.self, from: data)
    }

    static func loadAllTraces(from filePath: String = #filePath) throws -> [SseFaultTrace] {
        try traceIDs.map { try loadTrace($0, from: filePath) }
    }

    static func loadSchemaObject(_ name: String, from filePath: String = #filePath) throws -> [String: Any] {
        let url = recoveryRoot(from: filePath)
            .appendingPathComponent("schema", isDirectory: true)
            .appendingPathComponent(name)
        let data = try Data(contentsOf: url)
        let object = try JSONSerialization.jsonObject(with: data)
        guard let schema = object as? [String: Any] else {
            throw SseFaultOracleError.invalidSchema(name)
        }
        return schema
    }

    static func schemaRequiredFields(_ schema: [String: Any]) -> [String] {
        schema["required"] as? [String] ?? []
    }
}
