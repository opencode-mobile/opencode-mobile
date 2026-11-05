@file:Suppress("ktlint:standard:filename")

package dev.opencode.hexcode.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable

/** Easing and durations. Reduce Motion comes with M6. */
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
}
