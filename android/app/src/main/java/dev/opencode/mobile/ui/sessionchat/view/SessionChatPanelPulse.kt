package dev.opencode.mobile.ui.sessionchat.view

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/** How far the permission or question panel grows at the top of its pulse. */
internal const val SESSION_CHAT_PANEL_PULSE_SCALE = 1.03f

/**
 * Briefly pulses the permission or question panel each time [trigger] changes, so a
 * tap on a pill shows where the request opened. Skipped with Remove animations on.
 */
fun Modifier.sessionChatPanelPulse(trigger: Int): Modifier =
    composed {
        val context = LocalContext.current
        val animationsOn =
            remember(context) {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
            }
        val scale = remember { Animatable(1f) }
        val initialTrigger = remember { trigger }
        LaunchedEffect(trigger) {
            if (trigger == initialTrigger || !animationsOn) return@LaunchedEffect
            scale.animateTo(SESSION_CHAT_PANEL_PULSE_SCALE, tween(durationMillis = 140))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
        }
        graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
    }
