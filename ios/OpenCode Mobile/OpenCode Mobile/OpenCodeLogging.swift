import Foundation

nonisolated enum OpenCodeLog {
    enum api {
        static func info(_ message: String) { DiagnosticLog.info(.connection, message) }
        static func warning(_ message: String) { DiagnosticLog.warning(.connection, message) }
        static func error(_ message: String) { DiagnosticLog.error(.connection, message) }
        static func debug(_ message: String) { DiagnosticLog.debug(.connection, message) }
    }

    enum sse {
        static func info(_ message: String) { DiagnosticLog.info(.sse, message) }
        static func warning(_ message: String) { DiagnosticLog.warning(.sse, message) }
        static func error(_ message: String) { DiagnosticLog.error(.sse, message) }
        static func debug(_ message: String) { DiagnosticLog.debug(.sse, message) }
    }

    enum sessionChat {
        static func info(_ message: String) { DiagnosticLog.info(.chat, message) }
        static func warning(_ message: String) { DiagnosticLog.warning(.chat, message) }
        static func error(_ message: String) { DiagnosticLog.error(.chat, message) }
        static func debug(_ message: String) { DiagnosticLog.debug(.chat, message) }
    }

    enum messageStore {
        static func info(_ message: String) { DiagnosticLog.info(.chat, message) }
        static func warning(_ message: String) { DiagnosticLog.warning(.chat, message) }
        static func error(_ message: String) { DiagnosticLog.error(.chat, message) }
        static func debug(_ message: String) { DiagnosticLog.debug(.chat, message) }
    }

    enum changes {
        static func info(_ message: String) { DiagnosticLog.info(.chat, message) }
        static func warning(_ message: String) { DiagnosticLog.warning(.chat, message) }
        static func error(_ message: String) { DiagnosticLog.error(.chat, message) }
        static func debug(_ message: String) { DiagnosticLog.debug(.chat, message) }
    }

    enum messageContent {
        static func info(_ message: String) { DiagnosticLog.info(.chat, message) }
        static func warning(_ message: String) { DiagnosticLog.warning(.chat, message) }
        static func error(_ message: String) { DiagnosticLog.error(.chat, message) }
        static func debug(_ message: String) { DiagnosticLog.debug(.chat, message) }
    }
}

nonisolated enum OpenCodeAPITrace {
    static func start(_ request: URLRequest) -> Date {
        OpenCodeLog.api.info("API request \(method(request)) \(path(request))")
        return Date()
    }

    static func finish(_ request: URLRequest, statusCode: Int, startedAt: Date) {
        OpenCodeLog.api.info(
            "API response \(method(request)) \(path(request)) status=\(statusCode) durationMs=\(durationMs(since: startedAt))"
        )
    }

    static func invalidResponse(_ request: URLRequest, startedAt: Date) {
        OpenCodeLog.api.warning(
            "API invalid-response \(method(request)) \(path(request)) durationMs=\(durationMs(since: startedAt))"
        )
    }

    static func failure(_ request: URLRequest, error: Error, startedAt: Date) {
        OpenCodeLog.api.warning(
            "API failure \(method(request)) \(path(request)) durationMs=\(durationMs(since: startedAt)) error=\(String(describing: error))"
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
