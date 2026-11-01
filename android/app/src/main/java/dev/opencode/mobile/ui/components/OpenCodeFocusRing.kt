package dev.opencode.mobile.ui.components

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor

/**
 * The one keyboard focus indication of the design system: a 2 dp ring in the `interactive`
 * token at the control's edge, with a 1 dp `surface-base` hairline just inside it. The hairline
 * keeps the ring readable on filled and dark controls (High contrast, Accent, Destructive), where
 * the ring alone would sit on a similar colour. Both are drawn inside the control's shape so
 * scrolling parents never clip them.
 *
 * Clickable design-system controls are focusable only in keyboard input mode, so the ring shows
 * for Tab and arrow navigation and never after a touch. Components apply it themselves; screens
 * do not.
 */
object OpenCodeFocusRing {
    val width = 2.dp
    val hairlineWidth = 1.dp
}

/**
 * Draws the focus ring while the focus target that follows this modifier in the chain (for
 * example `clickable` or `focusable`) holds focus. Place it right before that modifier. The
 * control is also remembered as the keyboard-focused control, so a bottom sheet it opens can
 * hand focus back to it (see [openCodeFocusMemory]).
 */
fun Modifier.openCodeFocusRing(shape: Shape = RectangleShape): Modifier =
    composed {
        var focused by remember { mutableStateOf(false) }
        this
            .onFocusChanged { focused = it.isFocused }
            .openCodeFocusRing(focused = focused, shape = shape)
            .openCodeFocusMemory()
    }

/** Draws the focus ring while [focused]; for components that track focus themselves. */
fun Modifier.openCodeFocusRing(
    focused: Boolean,
    shape: Shape = RectangleShape,
): Modifier =
    composed {
        val ringColor = openCodeColor("interactive")
        val hairlineColor = openCodeColor("surface-base")
        drawWithContent {
            drawContent()
            if (!focused) return@drawWithContent
            val ring = OpenCodeFocusRing.width.toPx()
            val hairline = OpenCodeFocusRing.hairlineWidth.toPx()
            drawInsetOutline(shape, inset = ring / 2f, strokeWidth = ring, color = ringColor)
            drawInsetOutline(shape, inset = ring + hairline / 2f, strokeWidth = hairline, color = hairlineColor)
        }
    }

private fun DrawScope.drawInsetOutline(
    shape: Shape,
    inset: Float,
    strokeWidth: Float,
    color: Color,
) {
    val outlineSize = Size((size.width - inset * 2f).coerceAtLeast(0f), (size.height - inset * 2f).coerceAtLeast(0f))
    val outline = shape.createOutline(outlineSize, layoutDirection, this)
    translate(left = inset, top = inset) {
        drawOutline(outline = outline, color = color, style = Stroke(width = strokeWidth))
    }
}

/**
 * Remembers this control while it holds keyboard focus, so a bottom sheet it opens can return
 * focus to it when the sheet closes. Place it right before the control's focus target
 * (`clickable`); [openCodeFocusRing] with a shape already includes it.
 */
fun Modifier.openCodeFocusMemory(): Modifier =
    composed {
        val registry = LocalOpenCodeInputFocusRegistry.current
        val requester = remember { FocusRequester() }
        DisposableEffect(registry, requester) {
            onDispose { registry?.updateFocusedControl(requester, focused = false) }
        }
        this
            .onFocusChanged { registry?.updateFocusedControl(requester, it.isFocused) }
            .focusRequester(requester)
    }
