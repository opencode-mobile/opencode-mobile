package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexTheme

/** What a [HexCode] line is: plain (`text1`), a diff's added (`green`) or removed (`red`) line, or the cap (`text3`). */
enum class HexCodeLineKind { Plain, Added, Removed, More }

/** One line of a [HexCode] block. A [HexCodeLineKind.More] line is the cap's text, such as "… 3 more lines". */
data class HexCodeLine(
    val text: String,
    val kind: HexCodeLineKind = HexCodeLineKind.Plain,
)

/**
 * A command or diff block (`.pm-code`): `background` fill, `line` border, radius `m`, padding 10 × 12, type `code`.
 * Every line wraps and none is ever cut, so a long command shows whole.
 */
@Composable
fun HexCode(
    lines: List<HexCodeLine>,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.m)
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.background, shape)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        lines.forEach { line ->
            Text(
                line.text,
                style = HexTheme.type.code,
                color =
                    when (line.kind) {
                        HexCodeLineKind.Plain -> colors.text1
                        HexCodeLineKind.Added -> colors.green
                        HexCodeLineKind.Removed -> colors.red
                        HexCodeLineKind.More -> colors.text3
                    },
            )
        }
    }
}
