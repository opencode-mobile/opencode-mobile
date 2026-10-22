package dev.opencode.mobile.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * Lets a row's touch and ripple area extend [amount] past both horizontal
 * edges of the space it is given, without moving its content: the node is
 * measured wider and placed shifted left, so a `clip` + `clickable` applied
 * after it, followed by `padding(horizontal = amount)`, draws a highlight
 * with breathing room around the content while the layout stays put.
 */
internal fun Modifier.bleedHorizontally(amount: Dp): Modifier =
    layout { measurable, constraints ->
        val extra = (amount * 2).roundToPx()
        val placeable =
            measurable.measure(
                constraints.copy(
                    minWidth = (constraints.minWidth + extra).coerceAtMost(Constraints.Infinity),
                    maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
                ),
            )
        layout(width = (placeable.width - extra).coerceAtLeast(0), height = placeable.height) {
            placeable.place(x = -extra / 2, y = 0)
        }
    }
