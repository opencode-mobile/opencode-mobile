package dev.opencode.hexcode

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/** Three rounded bars, the same shape as the launcher icon. */
@Composable
fun HexCodeMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = size.width / 24f
        listOf(Triple(8f, 10f, 14f), Triple(12f, 7f, 17f), Triple(16f, 9f, 15f)).forEach { (x, top, bottom) ->
            drawLine(
                color = Color.White,
                start = Offset(x * unit, top * unit),
                end = Offset(x * unit, bottom * unit),
                strokeWidth = 2.4f * unit,
                cap = StrokeCap.Round,
            )
        }
    }
}
