package dev.opencode.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.theme.LocalOpenCodeDarkTheme
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import kotlin.math.roundToInt

private val TextSizeCardShape = RoundedCornerShape(OpenCodeRadius.medium)
private val TextSizeCardPaddingHorizontal = 16.dp
private val TextSizeCardPaddingTop = 12.dp
private val TextSizeCardPaddingBottom = 4.dp
private val TextSizeSliderSpacing = 12.dp
private val TextSizeTrackHeight = 2.dp
private val TextSizeTickSize = 8.dp
private val TextSizeThumbSize = 22.dp

/**
 * A card that picks one of [stepNames] as a text size (#1044): a title, the current step's
 * name on the right, and a stepped slider between a small and a large "A", with a tick at each
 * step and [defaultIndex] marked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeTextSizeSlider(
    title: String,
    stepNames: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    defaultIndex: Int = stepNames.size / 2,
    /** What the slider sizes, for screen readers, such as "Home list text size". */
    accessibilityLabel: String = title,
    enabled: Boolean = true,
) {
    require(stepNames.size >= 2) { "Text Size Slider needs at least two steps" }
    val lastIndex = stepNames.lastIndex
    val index = selectedIndex.coerceIn(0, lastIndex)
    val textStrong = openCodeColor("text-strong")
    val textBase = openCodeColor("text-base")
    val textWeak = openCodeColor("text-weak")
    val track = openCodeColor("border-weak-base")
    val defaultTick = openCodeColor("text-weaker")
    // A raised-surface thumb disappears on a dark card, so dark mode uses the strong ink instead.
    val thumbColor = if (LocalOpenCodeDarkTheme.current) textStrong else openCodeColor("surface-raised-base")
    val thumbBorder = openCodeColor("border-weak-base")
    // Keyed so a translation can use a letter of its own script.
    val sampleLetter = stringResource(R.string.component_text_size_slider_sample_letter)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(openCodeColor("surface-base"), TextSizeCardShape)
                .border(1.dp, openCodeColor("border-weaker-base"), TextSizeCardShape)
                .padding(
                    start = TextSizeCardPaddingHorizontal,
                    end = TextSizeCardPaddingHorizontal,
                    top = TextSizeCardPaddingTop,
                    bottom = TextSizeCardPaddingBottom,
                ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = textStrong,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stepNames[index],
                style = MaterialTheme.typography.bodyMedium,
                color = textWeak,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TextSizeSliderSpacing),
        ) {
            Text(
                text = sampleLetter,
                style = MaterialTheme.typography.labelSmall,
                color = textBase,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Slider(
                value = index.toFloat(),
                onValueChange = { value ->
                    val next = value.roundToInt().coerceIn(0, lastIndex)
                    if (next != index) onSelectedIndexChange(next)
                },
                valueRange = 0f..lastIndex.toFloat(),
                steps = lastIndex - 1,
                enabled = enabled,
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = accessibilityLabel
                            stateDescription = stepNames[index]
                        },
                thumb = {
                    Box(
                        modifier =
                            Modifier
                                .size(TextSizeThumbSize)
                                .shadow(2.dp, CircleShape)
                                .background(thumbColor, CircleShape)
                                .border(1.dp, thumbBorder, CircleShape),
                    )
                },
                track = { state ->
                    val range = state.valueRange.endInclusive - state.valueRange.start
                    val fraction = if (range > 0f) (state.value - state.valueRange.start) / range else 0f
                    Canvas(modifier = Modifier.fillMaxWidth().height(TextSizeTickSize)) {
                        val lineHeight = TextSizeTrackHeight.toPx()
                        val centerY = size.height / 2f
                        val corner = CornerRadius(lineHeight / 2f)
                        drawRoundRect(
                            color = track,
                            topLeft = Offset(0f, centerY - lineHeight / 2f),
                            size = Size(size.width, lineHeight),
                            cornerRadius = corner,
                        )
                        drawRoundRect(
                            color = textStrong,
                            topLeft = Offset(0f, centerY - lineHeight / 2f),
                            size = Size(size.width * fraction, lineHeight),
                            cornerRadius = corner,
                        )
                        val tickRadius = TextSizeTickSize.toPx() / 2f
                        for (step in 0..lastIndex) {
                            drawCircle(
                                color = if (step == defaultIndex) defaultTick else track,
                                radius = tickRadius,
                                center = Offset(size.width * step / lastIndex, centerY),
                            )
                        }
                    }
                },
            )
            Text(
                text = sampleLetter,
                style = MaterialTheme.typography.titleLarge,
                color = textBase,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeTextSizeSliderPreview() {
    OpenCodeTheme(darkTheme = false) {
        var index by remember { mutableIntStateOf(2) }
        OpenCodeTextSizeSlider(
            title = "Size",
            stepNames = listOf("Extra small", "Small", "Default", "Large", "Extra large"),
            selectedIndex = index,
            onSelectedIndexChange = { index = it },
            modifier = Modifier.padding(16.dp),
        )
    }
}
