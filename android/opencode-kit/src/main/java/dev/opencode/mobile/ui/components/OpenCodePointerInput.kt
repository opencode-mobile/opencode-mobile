package dev.opencode.mobile.ui.components

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.opencode.mobile.theme.openCodeColor

/**
 * Secondary click (right-click, two-finger click) opens the same actions as a long-press.
 * [onSecondaryClick] gets the pointer position in this element's coordinates, for a menu at the
 * pointer. The whole gesture is consumed, so the tap and long-press handlers placed after this
 * modifier never see it. Touch input never triggers it.
 */
fun Modifier.openCodeSecondaryClick(
    enabled: Boolean = true,
    onSecondaryClick: (Offset) -> Unit,
): Modifier =
    if (!enabled) {
        this
    } else {
        composed {
            val currentOnSecondaryClick by rememberUpdatedState(onSecondaryClick)
            pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val press = awaitPointerEvent(PointerEventPass.Initial)
                        if (press.type != PointerEventType.Press || !press.buttons.isSecondaryPressed) continue
                        val position = press.changes.first().position
                        press.changes.forEach { it.consume() }
                        // Swallow the rest of the gesture: its release must not read as a tap.
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        currentOnSecondaryClick(position)
                    }
                }
            }
        }
    }

/** Pointer rules shared by design-system controls. */
object OpenCodePointerRules {
    /**
     * Whether a control swallows a press: a secondary button (right-click, two-finger click)
     * without the primary one. Controls act on primary clicks, taps and keys only; touch never
     * sets the secondary button.
     */
    fun controlSwallowsPress(
        primaryPressed: Boolean,
        secondaryPressed: Boolean,
    ): Boolean = secondaryPressed && !primaryPressed
}

/**
 * In a custom gesture handler, after the first down: the gesture is a secondary click (see
 * [OpenCodePointerRules.controlSwallowsPress]), so it must not act as a tap, drag or long-press.
 * For handlers that read downs whether or not they are consumed, which
 * [openCodeSwallowSecondaryPress] cannot stop.
 */
fun AwaitPointerEventScope.isSecondaryPress(): Boolean =
    OpenCodePointerRules.controlSwallowsPress(
        primaryPressed = currentEvent.buttons.isPrimaryPressed,
        secondaryPressed = currentEvent.buttons.isSecondaryPressed,
    )

/**
 * Consumes a secondary-button gesture (see [OpenCodePointerRules.controlSwallowsPress]) on the
 * initial pass, so the control's click handler after this modifier never sees it.
 * [openCodeSecondaryClick] still fires wherever it sits, since it does not skip consumed events.
 */
fun Modifier.openCodeSwallowSecondaryPress(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val press = awaitPointerEvent(PointerEventPass.Initial)
                if (press.type != PointerEventType.Press) continue
                val swallow =
                    OpenCodePointerRules.controlSwallowsPress(
                        primaryPressed = press.buttons.isPrimaryPressed,
                        secondaryPressed = press.buttons.isSecondaryPressed,
                    )
                if (!swallow) continue
                press.changes.forEach { it.consume() }
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }

/**
 * The design system's hover state layer, for controls that draw no ripple of their own. Shows
 * only under a mouse or trackpad pointer; touch never hovers.
 */
fun Modifier.openCodeHoverLayer(
    interactionSource: InteractionSource,
    shape: Shape = RectangleShape,
): Modifier =
    composed {
        val hovered by interactionSource.collectIsHoveredAsState()
        // Material 3's hover state-layer opacity, as the ripple on other controls uses.
        val hoverAlpha = (LocalRippleConfiguration.current?.rippleAlpha ?: RippleDefaults.RippleAlpha).hoveredAlpha
        val color = openCodeColor("text-strong").copy(alpha = hoverAlpha)
        drawWithContent {
            drawContent()
            if (hovered) drawOutline(shape.createOutline(size, layoutDirection, this), color)
        }
    }
