package dev.opencode.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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

enum class OpenCodeSkeletonIllustrationTopic {
    Unofficial,
    ConnectForm,
    SessionsLive,
    ChipTimelineGlance,
    ChipTimelineLongRuns,
    ChipTimelineDetails,

    /** Timeline Style picker (#805): tool cards and groups between the agent's messages. */
    TimelineStandard,

    /** Timeline Style picker: tools as chips under the narration (same drawing as [ChipTimelineGlance]). */
    TimelineChips,

    /** Timeline Style picker: one line per step on a rail, the running step lit. */
    TimelineStepRail,

    /** Timeline Style picker: one live activity card with a footer band. */
    TimelineActivityCard,
}

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
            diffAdd = openCodeColor("diff-add"),
            diffDelete = openCodeColor("diff-delete"),
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
                OpenCodeSkeletonIllustrationTopic.ChipTimelineGlance -> drawChipTimelineGlance(colors)
                OpenCodeSkeletonIllustrationTopic.ChipTimelineLongRuns -> drawChipTimelineLongRuns(colors)
                OpenCodeSkeletonIllustrationTopic.ChipTimelineDetails -> drawChipTimelineDetails(colors)
                OpenCodeSkeletonIllustrationTopic.TimelineStandard -> drawTimelineStandard(colors)
                OpenCodeSkeletonIllustrationTopic.TimelineChips -> drawChipTimelineGlance(colors)
                OpenCodeSkeletonIllustrationTopic.TimelineStepRail -> drawTimelineStepRail(colors)
                OpenCodeSkeletonIllustrationTopic.TimelineActivityCard -> drawTimelineActivityCard(colors)
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
    val diffAdd: Color,
    val diffDelete: Color,
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

/** Narration with its tools as chips underneath; the first chip row is lit. */
private fun DrawScope.drawChipTimelineGlance(colors: SkeletonIllustrationColors) {
    frame(colors)
    userBubble(colors)
    bar(colors, x = 64f, y = 62f, w = 196f, h = 7f)
    bar(colors, x = 64f, y = 75f, w = 138f, h = 7f)
    glow(colors, x = 64f, y = 90f, w = 206f, h = 18f, cornerRadius = 6f)
    chip(colors, x = 64f, y = 90f, w = 62f, lit = true)
    chip(colors, x = 132f, y = 90f, w = 78f, lit = true, diff = true)
    chip(colors, x = 216f, y = 90f, w = 54f, lit = true)
    bar(colors, x = 64f, y = 124f, w = 176f, h = 7f)
    bar(colors, x = 64f, y = 137f, w = 110f, h = 7f)
    chip(colors, x = 64f, y = 152f, w = 70f)
    chip(colors, x = 140f, y = 152f, w = 58f)
}

/** A long list of tool rows on the left collapses into a few lit chips on the right. */
private fun DrawScope.drawChipTimelineLongRuns(colors: SkeletonIllustrationColors) {
    frame(colors)
    listOf(62f, 48f, 70f, 54f, 66f, 44f, 58f, 50f).forEachIndexed { index, width ->
        val y = 30f + index * 18f
        drawRoundRect(
            color = colors.borderWeakerBase,
            topLeft = Offset(64f, y),
            size = Size(8f, 8f),
            cornerRadius = CornerRadius(2f),
        )
        bar(colors, x = 78f, y = y + 1f, w = width, h = 6f, color = colors.borderWeakerBase)
    }
    val arrowStroke = 2f
    drawLine(colors.textWeaker, Offset(156f, 92f), Offset(172f, 92f), arrowStroke, StrokeCap.Round)
    drawLine(colors.textWeaker, Offset(166f, 86f), Offset(172f, 92f), arrowStroke, StrokeCap.Round)
    drawLine(colors.textWeaker, Offset(166f, 98f), Offset(172f, 92f), arrowStroke, StrokeCap.Round)
    bar(colors, x = 184f, y = 62f, w = 86f, h = 7f)
    bar(colors, x = 184f, y = 75f, w = 60f, h = 7f)
    glow(colors, x = 184f, y = 90f, w = 86f, h = 40f, cornerRadius = 6f)
    chip(colors, x = 184f, y = 90f, w = 46f, lit = true)
    chip(colors, x = 234f, y = 90f, w = 36f, lit = true)
    chip(colors, x = 184f, y = 112f, w = 64f, lit = true, diff = true)
}

/** One lit chip opens its detail: a file header with diff lines. */
private fun DrawScope.drawChipTimelineDetails(colors: SkeletonIllustrationColors) {
    frame(colors)
    bar(colors, x = 64f, y = 30f, w = 186f, h = 7f)
    bar(colors, x = 64f, y = 43f, w = 124f, h = 7f)
    chip(colors, x = 64f, y = 58f, w = 62f)
    chip(colors, x = 132f, y = 58f, w = 84f, lit = true, diff = true)
    card(colors, x = 64f, y = 92f, w = 208f, h = 86f, lit = false)
    bar(colors, x = 76f, y = 102f, w = 96f, h = 7f)
    listOf(
        150f to null,
        128f to colors.diffDelete,
        160f to colors.diffAdd,
        112f to colors.diffAdd,
        140f to null,
    ).forEachIndexed { index, (width, marker) ->
        val y = 118f + index * 11f
        if (marker != null) {
            drawRoundRect(
                color = marker,
                topLeft = Offset(76f, y),
                size = Size(3f, 6f),
                cornerRadius = CornerRadius(1f),
            )
        }
        bar(colors, x = 84f, y = y, w = width, h = 6f, color = marker?.copy(alpha = 0.45f) ?: colors.borderWeakBase)
    }
}

/** Tool cards and groups between the agent's messages: one lit group card with three rows. */
private fun DrawScope.drawTimelineStandard(colors: SkeletonIllustrationColors) {
    frame(colors)
    userBubble(colors)
    bar(colors, x = 64f, y = 62f, w = 196f, h = 7f)
    bar(colors, x = 64f, y = 75f, w = 120f, h = 7f)
    spotlight(colors, x = 64f, y = 90f, w = 208f, h = 76f, cornerRadius = 10f)
    bar(colors, x = 76f, y = 99f, w = 54f, h = 6f, color = colors.textWeak)
    chevron(colors, Offset(256f, 99f), Offset(260f, 103f), Offset(264f, 99f))
    drawRect(color = colors.borderWeakerBase, topLeft = Offset(64f, 113f), size = Size(208f, 1f))
    listOf(Triple(121f, 92f, false), Triple(137f, 70f, true), Triple(153f, 104f, false)).forEach { (y, w, diff) ->
        toolIcon(colors, x = 76f, y = y)
        bar(colors, x = 90f, y = y + 1f, w = 30f, h = 6f, color = colors.textWeak)
        bar(colors, x = 126f, y = y + 1f, w = w, h = 6f)
        if (diff) diffBars(colors, y = y + 1f)
    }
    bar(colors, x = 64f, y = 180f, w = 150f, h = 7f)
}

/** One line per step on a rail; the running step is lit with a ringed dot and elapsed time. */
private fun DrawScope.drawTimelineStepRail(colors: SkeletonIllustrationColors) {
    frame(colors)
    userBubble(colors)
    bar(colors, x = 64f, y = 62f, w = 170f, h = 7f)
    val railX = 70.75f
    drawRect(color = colors.borderWeakBase, topLeft = Offset(70f, 82f), size = Size(1.5f, 104f))
    listOf(
        StepRailRow(y = 88f, label = 40f, detail = 70f),
        StepRailRow(y = 106f, label = 52f, detail = 56f),
        StepRailRow(y = 124f, label = 34f, detail = 84f, diff = true),
    ).forEach { row ->
        drawCircle(color = colors.success, radius = 4f, center = Offset(railX, row.y + 3f))
        bar(colors, x = 82f, y = row.y, w = row.label, h = 6f, color = colors.textWeak)
        bar(colors, x = 88f + row.label, y = row.y, w = row.detail, h = 6f)
        if (row.diff) diffBars(colors, y = row.y)
    }
    glow(colors, x = 80f, y = 140f, w = 192f, h = 18f, cornerRadius = 6f)
    card(colors, x = 80f, y = 140f, w = 192f, h = 18f, lit = true, cornerRadius = 6f)
    drawCircle(color = colors.interactive, radius = 4f, center = Offset(railX, 149f))
    drawCircle(
        color = colors.interactive.copy(alpha = 0.35f),
        radius = 7f,
        center = Offset(railX, 149f),
        style = Stroke(width = 2f),
    )
    bar(colors, x = 88f, y = 146f, w = 34f, h = 6f, color = colors.textBase)
    bar(colors, x = 128f, y = 146f, w = 80f, h = 6f)
    bar(colors, x = 240f, y = 146f, w = 24f, h = 6f, color = colors.textWeaker)
    drawCircle(color = colors.textWeaker, radius = 4f, center = Offset(railX, 175f))
    bar(colors, x = 82f, y = 172f, w = 60f, h = 6f, color = colors.borderWeakerBase)
}

private data class StepRailRow(
    val y: Float,
    val label: Float,
    val detail: Float,
    val diff: Boolean = false,
)

/** One live card per segment: Working header, summary line, footer band with the running tool. */
private fun DrawScope.drawTimelineActivityCard(colors: SkeletonIllustrationColors) {
    frame(colors)
    userBubble(colors)
    bar(colors, x = 64f, y = 62f, w = 196f, h = 7f)
    bar(colors, x = 64f, y = 75f, w = 120f, h = 7f)
    glow(colors, x = 64f, y = 90f, w = 208f, h = 80f, cornerRadius = 12f)
    val cardShape =
        Path().apply {
            addRoundRect(RoundRect(left = 64f, top = 90f, right = 272f, bottom = 170f, cornerRadius = CornerRadius(12f)))
        }
    clipPath(cardShape) {
        drawRect(color = colors.surfaceRaisedBase, topLeft = Offset(64f, 90f), size = Size(208f, 80f))
        drawRect(color = colors.borderWeakerBase, topLeft = Offset(64f, 146f), size = Size(208f, 24f))
    }
    drawRoundRect(
        color = colors.interactive,
        topLeft = Offset(64f, 90f),
        size = Size(208f, 80f),
        cornerRadius = CornerRadius(12f),
        style = Stroke(width = 2f),
    )
    bar(colors, x = 76f, y = 102f, w = 52f, h = 8f, color = colors.textBase)
    bar(colors, x = 236f, y = 103f, w = 26f, h = 6f, color = colors.textWeaker)
    bar(colors, x = 76f, y = 120f, w = 70f, h = 6f, color = colors.textWeak)
    bar(colors, x = 152f, y = 120f, w = 46f, h = 6f)
    drawRect(color = colors.borderWeakBase, topLeft = Offset(64f, 146f), size = Size(208f, 1f))
    drawCircle(color = colors.interactive, radius = 4f, center = Offset(80f, 158f))
    bar(colors, x = 90f, y = 155f, w = 30f, h = 6f, color = colors.textBase)
    bar(colors, x = 126f, y = 155f, w = 84f, h = 6f)
    chevron(colors, Offset(256f, 154f), Offset(260f, 158f), Offset(256f, 162f))
}

/** The user's message bubble at the top of a Session Chat drawing. */
private fun DrawScope.userBubble(colors: SkeletonIllustrationColors) {
    drawRoundRect(
        color = colors.surfaceInteractiveWeak,
        topLeft = Offset(160f, 28f),
        size = Size(112f, 22f),
        cornerRadius = CornerRadius(11f),
    )
    bar(colors, x = 172f, y = 36f, w = 84f, h = 6f)
}

/** Small tool icon square on a timeline row. */
private fun DrawScope.toolIcon(
    colors: SkeletonIllustrationColors,
    x: Float,
    y: Float,
) {
    drawRoundRect(
        color = colors.borderWeakBase,
        topLeft = Offset(x, y),
        size = Size(8f, 8f),
        cornerRadius = CornerRadius(2f),
    )
}

/** Green/red diff count bars at the trailing edge of a 208-wide row. */
private fun DrawScope.diffBars(
    colors: SkeletonIllustrationColors,
    y: Float,
) {
    bar(colors, x = 244f, y = y, w = 8f, h = 6f, color = colors.diffAdd)
    bar(colors, x = 255f, y = y, w = 8f, h = 6f, color = colors.diffDelete)
}

/** Two-segment chevron stroke through [start], [corner], and [end]. */
private fun DrawScope.chevron(
    colors: SkeletonIllustrationColors,
    start: Offset,
    corner: Offset,
    end: Offset,
) {
    val path =
        Path().apply {
            moveTo(start.x, start.y)
            lineTo(corner.x, corner.y)
            lineTo(end.x, end.y)
        }
    drawPath(
        path = path,
        color = colors.textWeaker,
        style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** A tool chip: icon square plus label bar; [diff] adds green/red count bars. */
private fun DrawScope.chip(
    colors: SkeletonIllustrationColors,
    x: Float,
    y: Float,
    w: Float,
    lit: Boolean = false,
    diff: Boolean = false,
) {
    val h = 18f
    if (lit) {
        spotlight(colors, x, y, w, h, cornerRadius = 6f)
    } else {
        card(colors, x, y, w, h, lit = false, cornerRadius = 6f)
    }
    drawRoundRect(
        color = colors.borderWeakBase,
        topLeft = Offset(x + 6f, y + 6f),
        size = Size(6f, 6f),
        cornerRadius = CornerRadius(1.5f),
    )
    bar(colors, x = x + 16f, y = y + 6f, w = w - 22f - (if (diff) 20f else 0f), h = 6f)
    if (diff) {
        bar(colors, x = x + w - 24f, y = y + 6f, w = 8f, h = 6f, color = colors.diffAdd)
        bar(colors, x = x + w - 14f, y = y + 6f, w = 8f, h = 6f, color = colors.diffDelete)
    }
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
