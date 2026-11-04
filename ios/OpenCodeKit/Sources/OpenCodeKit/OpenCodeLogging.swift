import Foundation

public nonisolated enum OpenCodeLog {
    public enum api {
        public static func info(_ message: String) { DiagnosticLog.info(.connection, message) }
        public static func warning(_ message: String) { DiagnosticLog.warning(.connection, message) }
        public static func error(_ message: String) { DiagnosticLog.error(.connection, message) }
        public static func debug(_ message: String) { DiagnosticLog.debug(.connection, message) }
    }

    public enum sse {
        public static func info(_ message: String) { DiagnosticLog.info(.sse, message) }
        public static func warning(_ message: String) { DiagnosticLog.warning(.sse, message) }
        public static func error(_ message: String) { DiagnosticLog.error(.sse, message) }
        public static func debug(_ message: String) { DiagnosticLog.debug(.sse, message) }
    }

    public enum sessionChat {
        public static func info(_ message: String) { DiagnosticLog.info(.chat, message) }
        public static func warning(_ message: String) { DiagnosticLog.warning(.chat, message) }
        public static func error(_ message: String) { DiagnosticLog.error(.chat, message) }
        public static func debug(_ message: String) { DiagnosticLog.debug(.chat, message) }
    }

    public enum messageStore {
        public static func info(_ message: String) { DiagnosticLog.info(.chat, message) }
        public static func warning(_ message: String) { DiagnosticLog.warning(.chat, message) }
        public static func error(_ message: String) { DiagnosticLog.error(.chat, message) }
        public static func debug(_ message: String) { DiagnosticLog.debug(.chat, message) }
    }

    public enum changes {
        public static func info(_ message: String) { DiagnosticLog.info(.chat, message) }
        public static func warning(_ message: String) { DiagnosticLog.warning(.chat, message) }
        public static func error(_ message: String) { DiagnosticLog.error(.chat, message) }
        public static func debug(_ message: String) { DiagnosticLog.debug(.chat, message) }
    }

    public enum messageContent {
        public static func info(_ message: String) { DiagnosticLog.info(.chat, message) }
        public static func warning(_ message: String) { DiagnosticLog.warning(.chat, message) }
        public static func error(_ message: String) { DiagnosticLog.error(.chat, message) }
        public static func debug(_ message: String) { DiagnosticLog.debug(.chat, message) }
    }
}

public nonisolated enum OpenCodeAPITrace {
    public static func start(_ request: URLRequest) -> Date {
        OpenCodeLog.api.info("API request \(method(request)) \(path(request))")
        return Date()
    }

    public static func finish(_ request: URLRequest, statusCode: Int, startedAt: Date) {
        OpenCodeLog.api.info(
            "API response \(method(request)) \(path(request)) status=\(statusCode) durationMs=\(durationMs(since: startedAt))"
        )
    }

    public static func invalidResponse(_ request: URLRequest, startedAt: Date) {
        OpenCodeLog.api.warning(
            "API invalid-response \(method(request)) \(path(request)) durationMs=\(durationMs(since: startedAt))"
        )
    }

    public static func failure(_ request: URLRequest, error: Error, startedAt: Date) {
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
