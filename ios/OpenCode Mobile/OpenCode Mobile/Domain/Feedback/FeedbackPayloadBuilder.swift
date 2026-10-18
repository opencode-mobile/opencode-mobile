import Foundation

/// Builds the `feedback/{reportId}` document from a submission (#721).
/// `shared/feedback/report.example.json` is the contract. `createdAt` is never
/// part of the payload: the commit sets it with a `REQUEST_TIME` transform.
nonisolated enum FeedbackPayloadBuilder {
    /// The report as plain JSON values (strings, ints, maps, lists).
    static func report(_ submission: FeedbackSubmission, uid: String) -> [String: Any] {
        [
            "uid": uid,
            "kind": submission.kind.rawValue,
            "message": submission.message,
            "app": app(submission.metadata),
            "context": context(submission.metadata),
            "attachments": submission.attachments.map { attachment -> [String: Any] in
                [
                    "name": attachment.name,
                    "contentType": attachment.contentType,
                    "size": attachment.size,
                ]
            },
        ]
    }

    static func app(_ metadata: FeedbackMetadata) -> [String: String] {
        [
            "platform": FeedbackMetadata.platform,
            "version": clipped(metadata.version),
            "build": clipped(metadata.build),
            "osVersion": clipped(metadata.osVersion),
            "device": clipped(metadata.device),
            "locale": clipped(metadata.locale),
        ]
    }

    static func context(_ metadata: FeedbackMetadata) -> [String: String] {
        var context = [
            "screen": metadata.screen.rawValue,
            "engine": FeedbackMetadata.engine,
        ]
        if let serverProtocol = metadata.serverProtocol {
            context["serverProtocol"] = serverProtocol.wireValue
        }
        if let serverVersion = metadata.serverVersion?.trimmingCharacters(in: .whitespacesAndNewlines),
           !serverVersion.isEmpty {
            context["serverVersion"] = clipped(serverVersion)
        }
        return context
    }

    /// The report in Firestore REST `Value` encoding, for `documents:commit`.
    static func firestoreFields(_ submission: FeedbackSubmission, uid: String) -> [String: Any] {
        report(submission, uid: uid).mapValues(firestoreValue)
    }

    static func firestoreValue(_ value: Any) -> Any {
        switch value {
        case let string as String:
            return ["stringValue": string]
        case let int as Int:
            // Firestore REST carries int64 as a decimal string.
            return ["integerValue": String(int)]
        case let map as [String: Any]:
            return ["mapValue": ["fields": map.mapValues(firestoreValue)]]
        case let list as [Any]:
            return ["arrayValue": ["values": list.map(firestoreValue)]]
        default:
            return ["nullValue": NSNull()]
        }
    }

    private static func clipped(_ value: String) -> String {
        String(value.prefix(FeedbackMetadata.maxFieldLength))
    }
}

/// Plain-text report for the share sheet, plus the same files the backend would get.
nonisolated enum FeedbackSharePayloadBuilder {
    static func payload(for submission: FeedbackSubmission) -> FeedbackSharePayload {
        let app = FeedbackPayloadBuilder.app(submission.metadata)
        let context = FeedbackPayloadBuilder.context(submission.metadata)
        var lines = [
            "OpenCode Mobile \(submission.kind.label.lowercased()) report · Ref \(submission.reference)",
            "",
            submission.message,
            "",
            "App: \(app["version"] ?? "") (\(app["build"] ?? "")) · \(app["osVersion"] ?? "") · \(app["device"] ?? "") · \(app["locale"] ?? "")",
        ]
        var server = ["Screen: \(context["screen"] ?? "")"]
        if let serverProtocol = context["serverProtocol"] {
            server.append("protocol \(serverProtocol)")
        }
        if let serverVersion = context["serverVersion"] {
            server.append("server \(serverVersion)")
        }
        lines.append(server.joined(separator: " · "))
        return FeedbackSharePayload(text: lines.joined(separator: "\n"), files: submission.attachments)
    }
}
