package dev.opencode.hexcode.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor

/** Text with its `backticked` commands set in mono, as the strings write them (and iOS renders them). */
fun codeText(text: String): AnnotatedString =
    buildAnnotatedString {
        text.split('`').forEachIndexed { index, part ->
            if (index % 2 == 1) withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(part) } else append(part)
        }
    }

/** The page edge every Hex Code screen sits on (the prototype's 18 pt). */
val HexEdge: Dp = 18.dp

/** A small busy ring in the theme's strong text colour. */
@Composable
fun HexSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color = openCodeColor("text-strong"),
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = color,
        trackColor = openCodeColor("border-weak-base"),
        strokeWidth = 1.5.dp,
        strokeCap = StrokeCap.Round,
    )
}

/** Hex Code's mark: a rounded square with three bars, drawn in the tint of the `Icon` that shows it. */
val HexMark: ImageVector =
    ImageVector
        .Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            listOf("M8.5 2.5h7a6 6 0 0 1 6 6v7a6 6 0 0 1-6 6h-7a6 6 0 0 1-6-6v-7a6 6 0 0 1 6-6z", "M8.5 10v4M12 7.5v9M15.5 9.5v5")
                .forEach { d ->
                    addPath(
                        pathData = PathParser().parsePathString(d).toNodes(),
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 1.9f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
        }.build()
