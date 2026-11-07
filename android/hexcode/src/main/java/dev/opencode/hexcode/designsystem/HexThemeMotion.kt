@file:Suppress("ktlint:standard:filename")

package dev.opencode.hexcode.designsystem

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext

/**
 * Easing and durations, and Reduce Motion ([reduced]). One rule for the whole app (app-plan.md, Motion and Reduce
 * Motion): with it on, nothing moves or loops as decoration, pulses rest at their visible state, and state changes are
 * instant or a fade; spinners, the stop timer and the meter's levels keep moving ([FullSpeed]), since they carry
 * information.
 */
@Immutable
class HexMotion(
    /** The prototype's `--ease`. */
    val easing: Easing = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f),
    val pressMs: Int = 120,
    val fadeMs: Int = 200,
    val sheetMs: Int = 280,
    /** How long Stop must be held. */
    val stopHoldMs: Int = 3000,
) {
    fun <T> press(): TweenSpec<T> = tween(pressMs, easing = easing)

    fun <T> fade(): TweenSpec<T> = tween(fadeMs, easing = easing)

    fun <T> sheet(): TweenSpec<T> = tween(sheetMs, easing = easing)

    companion object {
        /**
         * Reduce Motion is on (`HexMotion.reduced`): "Remove animations", which sets the animator duration scale to 0.
         * Observed live, so turning it on while a screen shows applies at once.
         */
        val reduced: Boolean
            @Composable @ReadOnlyComposable
            get() = LocalReduceMotion.current
    }
}

/** Whether Reduce Motion is on, given to the UI by [HexTheme]; see [HexMotion.reduced]. */
val LocalReduceMotion = compositionLocalOf { false }

/** Reduce Motion's decisions, kept apart from Compose so they can be tested. */
object ReducedMotion {
    /** "Remove animations" (and the developer option set to off) sets the animator duration scale to 0. */
    fun fromScale(scale: Float): Boolean = scale == 0f

    /** A pulse's alpha: its animated value, or with Reduce Motion its visible end (Compose would freeze it at either). */
    fun pulse(
        reduced: Boolean,
        animated: Float,
        visible: Float = 1f,
    ): Float = if (reduced) visible else animated

    /** A press scale: none with Reduce Motion (colour only). */
    fun pressScale(
        reduced: Boolean,
        pressed: Float,
    ): Float = if (reduced) 1f else pressed
}

/** The animator duration scale is 0, watched with a [ContentObserver] for as long as the caller is on screen. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current.applicationContext
    var reduced by remember { mutableStateOf(readReduced(context)) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    reduced = readReduced(context)
                }
            }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        context.contentResolver.registerContentObserver(uri, false, observer)
        reduced = readReduced(context)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun readReduced(context: Context): Boolean =
    ReducedMotion.fromScale(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))

/**
 * Animations run in this context keep their real speed when the animator scale is 0, which otherwise ends them at
 * once: for motion that carries information, as spinners, the stop hold ring and the talk button's steadied trace.
 */
object FullSpeed : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/**
 * A screen reader that explores by touch (TalkBack) is on, watched for as long as the caller shows: the draft then
 * shows as chunk rows with their own actions (app-plan.md, Accessibility › Draft chunks).
 */
@Composable
fun rememberScreenReaderOn(): Boolean {
    val context = LocalContext.current.applicationContext
    val manager = remember(context) { context.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    var on by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener =
            android.view.accessibility.AccessibilityManager
                .TouchExplorationStateChangeListener { on = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}
