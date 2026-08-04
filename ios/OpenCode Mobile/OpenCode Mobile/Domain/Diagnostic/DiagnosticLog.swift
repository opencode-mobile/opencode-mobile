import Foundation
import OSLog

/// Dual-writes redacted diagnostic lines to `DiagnosticLogBuffer` and `OSLog`.
nonisolated enum DiagnosticLog {
    private static let subsystem = "dev.opencode.mobile"

    static func debug(_ area: DiagnosticLogArea, _ message: String) {
        write(area: area, level: .debug, message: message)
    }

    static func info(_ area: DiagnosticLogArea, _ message: String) {
        write(area: area, level: .info, message: message)
    }

    static func warning(_ area: DiagnosticLogArea, _ message: String) {
        write(area: area, level: .warning, message: message)
    }

    static func error(_ area: DiagnosticLogArea, _ message: String) {
        write(area: area, level: .error, message: message)
    }

    private static func write(area: DiagnosticLogArea, level: DiagnosticLogLevel, message: String) {
        let redacted = DiagnosticLogRedactor.redact(message)
        DiagnosticLogBuffer.shared.append(area: area, level: level, message: redacted)
        let logger = Logger(subsystem: subsystem, category: area.rawValue)
        switch level {
        case .debug:
            logger.debug("\(redacted, privacy: .public)")
        case .info:
            logger.info("\(redacted, privacy: .public)")
        case .warning:
            logger.warning("\(redacted, privacy: .public)")
        case .error:
            logger.error("\(redacted, privacy: .public)")
        }
    }
}
