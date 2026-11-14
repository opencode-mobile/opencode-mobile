package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme

/** Where a [HexStep] is (the prototype's `.fst` classes). */
enum class HexStepState {
    /** Not reached: an empty ring, the title in `text3`. */
    Todo,

    /** Under way: a spinner. */
    Doing,

    /** Under way but waiting on another step (the agent while an approval waits): a dimmed spinner. */
    Dimmed,

    /** Needs the person: an amber dot, the title in amber. */
    Warn,

    /** Done: a green check. */
    Done,

    /** Didn't happen: a red ✕, the title in red. */
    Failed,
}

/** One step of a [HexSteps] list: its [title], an optional [detail] ([live] for a mono status line), and a button. */
data class HexStep(
    val state: HexStepState,
    val title: String,
    val detail: String? = null,
    val live: Boolean = false,
    val action: HexSheetAction? = null,
)

/**
 * A list of steps that tick through (`.fl-steps`, the artifacts enable sheet's variant A): an 18 dp mark per step, a
 * 1 dp `line2` thread joining them, the title in 14.5 / 500 and a detail under it, and an optional small button on the
 * right (Review).
 */
@Composable
fun HexSteps(
    steps: List<HexStep>,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    Column(modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                // The mark, and the thread down to the next step's.
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(18.dp).fillMaxHeight()) {
                    Box(Modifier.padding(top = 8.dp).size(18.dp), contentAlignment = Alignment.Center) { StepMark(step.state) }
                    if (index < steps.lastIndex) {
                        Box(
                            Modifier
                                .padding(top = 2.dp)
                                .width(1.dp)
                                .weight(1f)
                                .background(colors.line2),
                        )
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(vertical = 7.dp)
                        .semantics(mergeDescendants = true) { if (step.live) liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(
                        step.title,
                        style =
                            HexTheme.type.body.copy(
                                fontWeight =
                                    if (step.state ==
                                        HexStepState.Todo
                                    ) {
                                        FontWeight.Normal
                                    } else {
                                        FontWeight.Medium
                                    },
                            ),
                        color =
                            when (step.state) {
                                HexStepState.Todo -> colors.text3
                                HexStepState.Warn -> colors.amber
                                HexStepState.Failed -> colors.red
                                HexStepState.Dimmed -> colors.text2
                                else -> colors.text1
                            },
                    )
                    step.detail?.let {
                        Text(
                            it,
                            style = if (step.live) HexTheme.type.monoSmall else HexTheme.type.caption,
                            color =
                                if (step.state == HexStepState.Todo) {
                                    colors.text4
                                } else if (step.live) {
                                    colors.text3
                                } else {
                                    colors.text2
                                },
                            maxLines = if (step.live) 1 else Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                step.action?.let { HexButton(onClick = it.onClick, text = it.label, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

@Composable
private fun StepMark(state: HexStepState) {
    val colors = HexTheme.colors
    when (state) {
        HexStepState.Todo -> Box(Modifier.size(14.dp).border(1.5.dp, colors.text4, CircleShape))
        HexStepState.Doing -> HexSpinner(size = 18.dp)
        HexStepState.Dimmed -> HexSpinner(size = 18.dp, modifier = Modifier.alpha(0.45f))
        HexStepState.Warn ->
            Box(
                Modifier
                    .size(18.dp)
                    .background(colors.amberSoft, CircleShape)
                    .padding(4.dp)
                    .background(colors.amber, CircleShape),
            )
        HexStepState.Done ->
            Box(Modifier.size(18.dp).background(colors.green, CircleShape), contentAlignment = Alignment.Center) {
                Icon(HexIcons.check, null, tint = colors.background, modifier = Modifier.size(11.dp))
            }
        HexStepState.Failed ->
            Box(Modifier.size(18.dp).background(colors.red, CircleShape), contentAlignment = Alignment.Center) {
                Icon(HexIcons.x, null, tint = colors.background, modifier = Modifier.size(9.dp))
            }
    }
}
