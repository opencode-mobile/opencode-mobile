import Foundation

/// Content-free pipeline timing markers. Never attach message text or IDs that
/// contain user content — only phase names and optional opaque counters.
nonisolated enum MessagePipelineProbe {
    enum Marker: String, Sendable {
        case eventAccepted = "event_accepted"
        case reducerCommitted = "reducer_committed"
        case displayFramePublished = "display_frame_published"
        case styledPaintCompleted = "styled_paint_completed"
        case recoveryStarted = "recovery_started"
        case recoveryHealthy = "recovery_healthy"
        case recoveryRetry = "recovery_retry"
        case authorityWindowOpened = "authority_window_opened"
        case authorityWindowClosed = "authority_window_closed"
    }

    static func emit(_ marker: Marker) {
        SessionPerformanceProbe.emit(marker.rawValue)
    }
}
