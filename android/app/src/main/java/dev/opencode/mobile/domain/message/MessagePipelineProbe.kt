package dev.opencode.mobile.domain.message

import android.util.Log

/**
 * Content-free pipeline timing markers. Never attach message text or IDs that
 * contain user content — only phase names and optional opaque counters.
 */
object MessagePipelineProbe {
    enum class Marker(
        val wireName: String,
    ) {
        EventAccepted("event_accepted"),
        ReducerCommitted("reducer_committed"),
        DisplayFramePublished("display_frame_published"),
        StyledPaintCompleted("styled_paint_completed"),
        RecoveryStarted("recovery_started"),
        RecoveryHealthy("recovery_healthy"),
        RecoveryRetry("recovery_retry"),
        AuthorityWindowOpened("authority_window_opened"),
        AuthorityWindowClosed("authority_window_closed"),
    }

    @Volatile
    private var listener: ((Marker) -> Unit)? = null

    fun setListener(listener: ((Marker) -> Unit)?) {
        this.listener = listener
    }

    fun emit(marker: Marker) {
        listener?.invoke(marker)
        runCatching { Log.d(TAG, marker.wireName) }
    }

    private const val TAG = "MessagePipelineProbe"
}
