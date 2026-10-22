package dev.opencode.mobile.ui.diagnostic

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Choreographer
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.opencode.mobile.domain.diagnostic.FrameTimeSnapshot
import dev.opencode.mobile.domain.diagnostic.FrameTimeTracker
import dev.opencode.mobile.domain.diagnostic.PerformanceOverlayGate
import dev.opencode.mobile.domain.preferences.DeveloperToolsPreferences
import dev.opencode.mobile.domain.preferences.PerformanceOverlayPreferences

private const val NORMAL_TEXT = Color.WHITE
private const val SPIKE_TEXT = 0xFFFFB847.toInt()

/**
 * Pass-through frame-time HUD. The label updates from Choreographer so Compose
 * does not rebuild on every frame.
 */
@Composable
fun BoxScope.PerformanceOverlayHost() {
    if (
        !PerformanceOverlayGate.isVisible(
            developerToolsUnlocked = DeveloperToolsPreferences.unlocked,
            enabled = PerformanceOverlayPreferences.enabled,
        )
    ) {
        return
    }
    AndroidView(
        factory = { PerformanceOverlayView(it) },
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .padding(top = 8.dp, end = 8.dp)
                .testTag("performanceOverlay"),
    )
}

/** Feature-local debug badge. Not a design-system component. */
class PerformanceOverlayView(
    context: Context,
) : FrameLayout(context) {
    private val label = TextView(context)
    private val tracker = FrameTimeTracker()
    private var running = false
    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                val snapshot =
                    tracker.record(
                        timestampSeconds = frameTimeNanos / 1_000_000_000.0,
                        expectedIntervalSeconds = expectedIntervalSeconds(),
                    )
                label.text = snapshot.label
                label.setTextColor(if (snapshot.spikeActive) SPIKE_TEXT else NORMAL_TEXT)
                invalidate()
                if (running) {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }

    init {
        val density = resources.displayMetrics.density
        background =
            GradientDrawable().apply {
                setColor(0xB8000000.toInt())
                cornerRadius = 8f * density
            }
        val pad = (8 * density).toInt()
        setPadding(pad, pad, pad, pad)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        label.typeface = android.graphics.Typeface.MONOSPACE
        label.setTextColor(NORMAL_TEXT)
        label.text = FrameTimeSnapshot.idle.label
        addView(label)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun expectedIntervalSeconds(): Double {
        val rate = display?.refreshRate ?: 0f
        return if (rate > 1f) 1.0 / rate.toDouble() else 1.0 / 60.0
    }
}
