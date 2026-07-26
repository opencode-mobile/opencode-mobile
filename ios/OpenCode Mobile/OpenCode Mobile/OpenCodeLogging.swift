import Foundation
import OSLog

nonisolated enum OpenCodeLog {
    static let api = Logger(subsystem: subsystem, category: "api")
    static let sse = Logger(subsystem: subsystem, category: "sse")
    static let sessionChat = Logger(subsystem: subsystem, category: "session-chat")
    static let changes = Logger(subsystem: subsystem, category: "changes")
    static let messageContent = Logger(subsystem: subsystem, category: "MessageContentPreparer")

    private static let subsystem = "dev.opencode.mobile"
}

enum OpenCodeAPITrace {
    static func start(_ request: URLRequest) -> Date {
        OpenCodeLog.api.info("API request \(method(request), privacy: .public) \(path(request), privacy: .public)")
        return Date()
    }

    static func finish(_ request: URLRequest, statusCode: Int, startedAt: Date) {
        OpenCodeLog.api.info(
            "API response \(method(request), privacy: .public) \(path(request), privacy: .public) status=\(statusCode) durationMs=\(durationMs(since: startedAt))"
        )
    }

    static func invalidResponse(_ request: URLRequest, startedAt: Date) {
        OpenCodeLog.api.warning(
            "API invalid-response \(method(request), privacy: .public) \(path(request), privacy: .public) durationMs=\(durationMs(since: startedAt))"
        )
    }

    static func failure(_ request: URLRequest, error: Error, startedAt: Date) {
        OpenCodeLog.api.warning(
            "API failure \(method(request), privacy: .public) \(path(request), privacy: .public) durationMs=\(durationMs(since: startedAt)) error=\(String(describing: error), privacy: .public)"
        )
    }

    private static func method(_ request: URLRequest) -> String {
        request.httpMethod ?? "GET"
    }

    private static func path(_ request: URLRequest) -> String {
        guard let url = request.url else { return "-" }
        return url.path.isEmpty ? "/" : url.path
    }

    private static func durationMs(since start: Date) -> Int {
        max(0, Int(Date().timeIntervalSince(start) * 1_000))
    }
}
