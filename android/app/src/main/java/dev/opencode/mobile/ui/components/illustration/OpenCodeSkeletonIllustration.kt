package dev.opencode.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import kotlin.math.min

private const val DESIGN_WIDTH = 336f
private const val DESIGN_HEIGHT = 186f

enum class OpenCodeSkeletonIllustrationTopic { Unofficial, ConnectForm, SessionsLive }

@Composable
fun OpenCodeSkeletonIllustration(
    topic: OpenCodeSkeletonIllustrationTopic,
    modifier: Modifier = Modifier,
) {
    val colors =
        SkeletonIllustrationColors(
            backgroundBase = openCodeColor("background-base"),
            surfaceRaisedBase = openCodeColor("surface-raised-base"),
            surfaceInteractiveWeak = openCodeColor("surface-interactive-weak"),
            textBase = openCodeColor("text-base"),
            textWeak = openCodeColor("text-weak"),
            textWeaker = openCodeColor("text-weaker"),
            textInteractiveBase = openCodeColor("text-interactive-base"),
            borderWeakBase = openCodeColor("border-weak-base"),
            borderWeakerBase = openCodeColor("border-weaker-base"),
            iconInvertBase = openCodeColor("icon-invert-base"),
            buttonPrimaryBase = openCodeColor("button-primary-base"),
            interactive = openCodeColor("interactive"),
            success = openCodeColor("success"),
        )
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier =
            modifier
                .clipToBounds()
                .clearAndSetSemantics {},
    ) {
        val scale = min(size.width / DESIGN_WIDTH, size.height / DESIGN_HEIGHT)
        withTransform({
            translate(
                (size.width - DESIGN_WIDTH * scale) / 2f,
                (size.height - DESIGN_HEIGHT * scale) / 2f,
            )
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            when (topic) {
                OpenCodeSkeletonIllustrationTopic.Unofficial -> drawUnofficial(colors, textMeasurer)
                OpenCodeSkeletonIllustrationTopic.ConnectForm -> drawConnectForm(colors, textMeasurer)
                OpenCodeSkeletonIllustrationTopic.SessionsLive -> drawSessionsLive(colors, textMeasurer)
            }
        }
    }
}

private data class SkeletonIllustrationColors(
    val backgroundBase: Color,
    val surfaceRaisedBase: Color,
    val surfaceInteractiveWeak: Color,
    val textBase: Color,
    val textWeak: Color,
    val textWeaker: Color,
    val textInteractiveBase: Color,
    val borderWeakBase: Color,
    val borderWeakerBase: Color,
    val iconInvertBase: Color,
    val buttonPrimaryBase: Color,
    val interactive: Color,
    val success: Color,
)

private fun DrawScope.drawUnofficial(
    colors: SkeletonIllustrationColors,
    textMeasurer: TextMeasurer,
) {
    frame(colors)
    bar(colors, x = 64f, y = 32f, w = 90f, h = 10f)
    bar(colors, x = 64f, y = 48f, w = 60f, h = 6f)
    spotlight(
        colors,
        x = 64f,
        y = 64f,
        w = 118f,
        h = 26f,
        cornerRadius = 13f,
        fillColor = colors.surfaceInteractiveWeak,
    )
    drawCenteredText(
        textMeasurer = textMeasurer,
        text = "UNOFFICIAL",
        x = 64f,
        y = 64f,
        w = 118f,
        h = 26f,
        style =
            TextStyle(
                fontSize = 11f.toSp(),
                fontWeight = FontWeight.Bold,
                color = colors.textInteractiveBase,
            ),
    )
    card(colors, x = 64f, y = 104f, w = 208f, h = 60f, lit = false)
    bar(colors, x = 76f, y = 116f, w = 120f, h = 7f)
    bar(colors, x = 76f, y = 130f, w = 160f, h = 7f)
    bar(colors, x = 76f, y = 144f, w = 90f, h = 7f)
}

private fun DrawScope.drawConnectForm(
    colors: SkeletonIllustrationColors,
    textMeasurer: TextMeasurer,
) {
    frame(colors)
    bar(colors, x = 64f, y = 32f, w = 110f, h = 10f)
    card(colors, x = 64f, y = 54f, w = 208f, h = 32f, lit = false)
    val urlStyle =
        TextStyle(
            fontSize = 11f.toSp(),
            fontWeight = FontWeight.Medium,
            color = colors.textWeak,
        )
    val urlLayout = textMeasurer.measure("https://", urlStyle)
    drawText(
        textLayoutResult = urlLayout,
        topLeft = Offset(76f, 70f - urlLayout.size.height / 2f),
    )
    bar(colors, x = 118f, y = 68f, w = 90f, h = 8f)
    card(colors, x = 64f, y = 94f, w = 208f, h = 32f, lit = false)
    lockGlyph(colors, cx = 78f, cy = 110f)
    listOf(100f, 112f, 124f, 136f).forEach { cx ->
        drawCircle(color = colors.textWeaker, radius = 3.5f, center = Offset(cx, 110f))
    }
    glow(colors, x = 64f, y = 138f, w = 208f, h = 34f)
    button(colors, textMeasurer, x = 64f, y = 138f, w = 208f, h = 34f, title = "Connect")
}

private fun DrawScope.drawSessionsLive(
    colors: SkeletonIllustrationColors,
    textMeasurer: TextMeasurer,
) {
    frame(colors)
    bar(colors, x = 64f, y = 30f, w = 70f, h = 10f)
    drawCircle(color = colors.success, radius = 3f, center = Offset(70f, 50f))
    bar(colors, x = 78f, y = 47f, w = 22f, h = 5f)
    avatar(colors, cx = 80f, cy = 80f, live = false)
    bar(colors, x = 100f, y = 72f, w = 100f, h = 7f)
    bar(colors, x = 100f, y = 85f, w = 70f, h = 5f)
    spotlight(colors, x = 60f, y = 104f, w = 216f, h = 40f)
    avatar(colors, cx = 80f, cy = 124f, live = true)
    bar(colors, x = 100f, y = 116f, w = 110f, h = 7f, color = colors.textBase)
    bar(colors, x = 100f, y = 129f, w = 80f, h = 5f)
    drawRoundRect(
        color = colors.surfaceInteractiveWeak,
        topLeft = Offset(222f, 115f),
        size = Size(44f, 18f),
        cornerRadius = CornerRadius(9f),
    )
    drawCenteredText(
        textMeasurer = textMeasurer,
        text = "Running",
        x = 222f,
        y = 115f,
        w = 44f,
        h = 18f,
        style =
            TextStyle(
                fontSize = 10f.toSp(),
                fontWeight = FontWeight.Bold,
                color = colors.textInteractiveBase,
            ),
    )
    avatar(colors, cx = 80f, cy = 168f, live = false)
    bar(colors, x = 100f, y = 160f, w = 90f, h = 7f)
    bar(colors, x = 100f, y = 173f, w = 60f, h = 5f)
}

private fun DrawScope.frame(colors: SkeletonIllustrationColors) {
    val topLeft = Offset(48f, 14f)
    val size = Size(240f, 210f)
    val corner = CornerRadius(18f)
    drawRoundRect(
        color = colors.backgroundBase,
        topLeft = topLeft,
        size = size,
        cornerRadius = corner,
    )
    drawRoundRect(
        color = colors.borderWeakBase,
        topLeft = topLeft,
        size = size,
        cornerRadius = corner,
        style = Stroke(width = 1.5f),
    )
}

private fun DrawScope.card(
    colors: SkeletonIllustrationColors,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    lit: Boolean,
    cornerRadius: Float = 10f,
    fillColor: Color = colors.surfaceRaisedBase,
) {
    val topLeft = Offset(x, y)
    val size = Size(w, h)
    val corner = CornerRadius(cornerRadius)
    drawRoundRect(
        color = fillColor,
        topLeft = topLeft,
        size = size,
        cornerRadius = corner,
    )
    drawRoundRect(
        color = if (lit) colors.interactive else colors.borderWeakerBase,
        topLeft = topLeft,
        size = size,
        cornerRadius = corner,
        style = Stroke(width = if (lit) 2f else 1f),
    )
}

private fun DrawScope.bar(
    colors: SkeletonIllustrationColors,
    x: Float,
    y: Float,
    w: Float,
    h: Float = 8f,
    color: Color = colors.borderWeakBase,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = CornerRadius(h / 2f),
    )
}

private fun DrawScope.glow(
    colors: SkeletonIllustrationColors,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    cornerRadius: Float = 8f,
) {
    // Glow is inset by -6, so its radius is the inner shape's radius + 6 to stay concentric.
    drawRoundRect(
        color = colors.interactive.copy(alpha = 0.12f),
        topLeft = Offset(x - 6f, y - 6f),
        size = Size(w + 12f, h + 12f),
        cornerRadius = CornerRadius(cornerRadius + 6f),
    )
}

private fun DrawScope.spotlight(
    colors: SkeletonIllustrationColors,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    cornerRadius: Float = 10f,
    fillColor: Color = colors.surfaceRaisedBase,
) {
    glow(colors, x, y, w, h, cornerRadius = cornerRadius)
    card(colors, x, y, w, h, lit = true, cornerRadius = cornerRadius, fillColor = fillColor)
}

private fun DrawScope.button(
    colors: SkeletonIllustrationColors,
    textMeasurer: TextMeasurer,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    title: String,
) {
    drawRoundRect(
        color = colors.buttonPrimaryBase,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = CornerRadius(8f),
    )
    drawCenteredText(
        textMeasurer = textMeasurer,
        text = title,
        x = x,
        y = y,
        w = w,
        h = h,
        style =
            TextStyle(
                fontSize = 11f.toSp(),
                fontWeight = FontWeight.Bold,
                color = colors.iconInvertBase,
            ),
    )
}

private fun DrawScope.avatar(
    colors: SkeletonIllustrationColors,
    cx: Float,
    cy: Float,
    live: Boolean,
) {
    drawCircle(
        color = if (live) colors.surfaceInteractiveWeak else colors.borderWeakBase,
        radius = 12f,
        center = Offset(cx, cy),
    )
    if (live) {
        val dot = Offset(cx + 9f, cy + 9f)
        drawCircle(color = colors.surfaceRaisedBase, radius = 6f, center = dot)
        drawCircle(color = colors.success, radius = 4f, center = dot)
    }
}

private fun DrawScope.lockGlyph(
    colors: SkeletonIllustrationColors,
    cx: Float,
    cy: Float,
) {
    val bodyWidth = 10f
    val bodyHeight = 7f
    drawRoundRect(
        color = colors.textWeak,
        topLeft = Offset(cx - bodyWidth / 2f, (cy + 2f) - bodyHeight / 2f),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = CornerRadius(1.5f),
    )
    val shackleRadius = 3f
    val shackleCenterY = cy - 1.5f
    drawArc(
        color = colors.textWeak,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(cx - shackleRadius, shackleCenterY - shackleRadius),
        size = Size(shackleRadius * 2f, shackleRadius * 2f),
        style = Stroke(width = 1.5f),
    )
}

private fun DrawScope.drawCenteredText(
    textMeasurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    style: TextStyle,
) {
    val layout = textMeasurer.measure(text, style)
    drawText(
        textLayoutResult = layout,
        topLeft =
            Offset(
                x + (w - layout.size.width) / 2f,
                y + (h - layout.size.height) / 2f,
            ),
    )
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSkeletonIllustrationPreview() {
    OpenCodeTheme {
        OpenCodeSkeletonIllustration(
            topic = OpenCodeSkeletonIllustrationTopic.Unofficial,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(176.dp),
        )
    }
}
