package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.hexcode.designsystem.HexTheme

/**
 * Stop's countdown card under the top bar (`.stopwarn`), while the stop button is held: `stopBackground`, a 1 pt `red`
 * 50 % border, radius `xl`, padding 12 × 14, a shadow. A 46 pt ring (track `red` 18 %, a 2.5 pt `red` arc draining
 * with [progress]) round the seconds left ([count]: 3, 2, 1; `code` at 21, semibold, `red`, popping from 1.25× each
 * second), the [title] (`bodyStrong`) and [detail] (`caption`, `text2`), and a 2 pt `red` bar filling along the bottom.
 * With [reduceMotion] the number doesn't pop; the ring, the bar and the count still run, since they carry the time.
 */
@Composable
fun HexStopWarning(
    progress: Float,
    count: Int,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.xl)
    val red = colors.red
    val left = (1f - progress).coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, ambientColor = colors.shadow, spotColor = colors.shadow)
            .clip(shape)
            .background(colors.stopBackground)
            .border(1.dp, red.copy(alpha = 0.5f), shape)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(46.dp)) {
                    val stroke = 2.5.dp.toPx()
                    val inset = 3.dp.toPx() + stroke / 2f
                    val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                    drawArc(red.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    drawArc(red, -90f, 360f * left, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                val pop = remember { Animatable(1f) }
                LaunchedEffect(count, reduceMotion) {
                    if (reduceMotion) {
                        pop.snapTo(1f)
                    } else {
                        pop.snapTo(1.25f)
                        pop.animateTo(1f, tween(300))
                    }
                }
                Text(
                    count.toString(),
                    style = HexTheme.type.code.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
                    color = red,
                    modifier =
                        Modifier.graphicsLayer {
                            scaleX = pop.value
                            scaleY = pop.value
                        },
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = HexTheme.type.bodyStrong, color = colors.text1)
                Text(detail, style = HexTheme.type.caption, color = colors.text2)
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(2.dp)
                .background(red),
        )
    }
}

/**
 * The red edge round the whole screen while Stop is held (`.stopedge`): a 2 pt `red` 60 % stroke inside the screen's
 * [corner] radius, and a `red` 20 % glow about 90 pt deep, pulsing over 1 s. Still with [reduceMotion]. It takes no
 * touches; put it over everything, filling the screen.
 */
@Composable
fun HexStopEdge(
    modifier: Modifier = Modifier,
    corner: Dp = 0.dp,
    reduceMotion: Boolean = false,
) {
    val red = HexTheme.colors.red
    val pulse =
        if (reduceMotion) {
            1f
        } else {
            val transition = rememberInfiniteTransition(label = "stop-edge")
            val value by transition.animateFloat(
                1f,
                0.35f,
                infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
                label = "stop-edge-pulse",
            )
            value
        }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = pulse }
            .drawBehind {
                val depth = 90.dp.toPx()
                val glow = red.copy(alpha = 0.2f)
                val clear = Color.Transparent
                drawRect(Brush.verticalGradient(listOf(glow, clear), 0f, depth), size = Size(size.width, depth))
                drawRect(
                    Brush.verticalGradient(listOf(clear, glow), size.height - depth, size.height),
                    topLeft = Offset(0f, size.height - depth),
                    size = Size(size.width, depth),
                )
                drawRect(Brush.horizontalGradient(listOf(glow, clear), 0f, depth), size = Size(depth, size.height))
                drawRect(
                    Brush.horizontalGradient(listOf(clear, glow), size.width - depth, size.width),
                    topLeft = Offset(size.width - depth, 0f),
                    size = Size(depth, size.height),
                )
                val stroke = 2.dp.toPx()
                val r = corner.toPx()
                drawRoundRect(
                    red.copy(alpha = 0.6f),
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius((r - stroke / 2f).coerceAtLeast(0f)),
                    style = Stroke(stroke),
                )
            },
    )
}
