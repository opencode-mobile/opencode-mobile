package dev.opencode.mobile.ui.playground

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeFloatingChrome
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlin.math.roundToInt

/** Where the capsule was dragged to and whether it is minimised; outlives a scenario switch. */
internal class PlaygroundCapsulePlacement {
    var dragX by mutableFloatStateOf(0f)
    var dragY by mutableFloatStateOf(0f)
    var minimized by mutableStateOf(false)
}

/**
 * Below the Session Chat top bar and the row of top pills, centred, so the capsule starts clear
 * of the composer and of every pill corner.
 */
private val CapsuleDefaultTop = 140.dp
private val MinimizedSize = 48.dp

/**
 * The playground's floating playback controls for a scripted scenario: what step it is on, and
 * play / pause, next step, restart, 1× / 4×, minimise. Drag it by its grip; minimised it is a
 * round button with a progress ring that brings the capsule back.
 */
@Composable
internal fun PlaygroundPlaybackCapsule(
    code: String,
    progress: PlaygroundPlayer.Progress,
    idleLabel: String,
    placement: PlaygroundCapsulePlacement,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRestart: () -> Unit,
    onToggleSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
    ) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val topPx = with(density) { CapsuleDefaultTop.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }

        fun drag(amount: Offset) {
            val halfSlack = ((maxWidthPx - size.width) / 2f).coerceAtLeast(0f)
            placement.dragX = (placement.dragX + amount.x).coerceIn(-halfSlack, halfSlack)
            placement.dragY = (placement.dragY + amount.y).coerceIn(-topPx, (maxHeightPx - topPx - size.height).coerceAtLeast(-topPx))
        }
        val dragModifier =
            Modifier.pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    drag(amount)
                }
            }
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = CapsuleDefaultTop)
                    .offset { IntOffset(placement.dragX.roundToInt(), placement.dragY.roundToInt()) }
                    .onSizeChanged { size = it }
                    .testTag("playgroundCapsule"),
        ) {
            if (placement.minimized) {
                MinimizedCapsule(
                    code = code,
                    fraction = progress.fraction,
                    onClick = { placement.minimized = false },
                    modifier = dragModifier,
                )
            } else {
                OpenCodeFloatingChrome(shape = RoundedCornerShape(24.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Row(
                            modifier =
                                dragModifier
                                    .widthIn(max = 320.dp)
                                    .semantics { contentDescription = "Drag to move the playback controls" },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = OpenCodeIcons.DragHandle,
                                contentDescription = null,
                                tint = openCodeColor("icon-base"),
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "$code · ${progress.label ?: idleLabel}",
                                style = MaterialTheme.typography.labelMedium,
                                color = openCodeColor("text-strong"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).testTag("playgroundStepLabel"),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            OpenCodeButton(
                                variant =
                                    OpenCodeButtonVariant.IconCircle(
                                        if (progress.isPlaying) OpenCodeIcons.Pause else OpenCodeIcons.Play,
                                        if (progress.isPlaying) "Pause" else "Play",
                                    ),
                                enabled = !progress.isFinished,
                                onClick = onPlayPause,
                                modifier = Modifier.testTag("playgroundPlayPause"),
                            )
                            Text(
                                text = "${progress.shownSteps}/${progress.stepCount}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = openCodeColor("text-base"),
                                modifier = Modifier.padding(horizontal = 4.dp).testTag("playgroundStepCount"),
                            )
                            OpenCodeButton(
                                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.SkipNext, "Next step"),
                                contrast = OpenCodeButtonContrast.Low,
                                enabled = !progress.isFinished,
                                onClick = onNext,
                                modifier = Modifier.testTag("playgroundNextStep"),
                            )
                            OpenCodeButton(
                                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Restart, "Restart scenario"),
                                contrast = OpenCodeButtonContrast.Low,
                                onClick = onRestart,
                                modifier = Modifier.testTag("playgroundRestart"),
                            )
                            OpenCodeButton(
                                variant =
                                    OpenCodeButtonVariant.IconCircleText(
                                        if (progress.isFast) "4×" else "1×",
                                        if (progress.isFast) "Speed 4 times, switch to normal" else "Normal speed, switch to 4 times",
                                    ),
                                contrast = OpenCodeButtonContrast.Low,
                                onClick = onToggleSpeed,
                                modifier = Modifier.testTag("playgroundSpeed"),
                            )
                            OpenCodeButton(
                                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Minimize, "Minimise playback controls"),
                                contrast = OpenCodeButtonContrast.Low,
                                onClick = { placement.minimized = true },
                                modifier = Modifier.testTag("playgroundMinimize"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimizedCapsule(
    code: String,
    fraction: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = openCodeColor("border-weak-base")
    val fill = openCodeColor("interactive")
    OpenCodeFloatingChrome(shape = CircleShape, modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .size(MinimizedSize)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Show playback controls", onClick = onClick)
                    .testTag("playgroundCapsuleDot"),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(MinimizedSize - 8.dp)) {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(fill, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(
                text = code,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = openCodeColor("text-strong"),
            )
        }
    }
}
