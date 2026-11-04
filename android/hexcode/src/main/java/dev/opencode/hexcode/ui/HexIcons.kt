package dev.opencode.hexcode.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The prototype's line icons (24-unit SVG paths, round caps, 1.75 stroke), drawn in the tint of the `Icon` that shows
 * them.
 */
object HexIcons {
    val ChevronLeft = icon("m15 6-6 6 6 6")
    val ChevronRight = icon("m9 6 6 6-6 6")
    val Check = icon("m5 12.5 4.5 4.5L19 7.5", stroke = 2.4f)
    val Close = icon("M6 6l12 12M18 6 6 18", stroke = 2.2f)
    val Folder = icon("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Search = icon("M4.5 11a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0", "m20 20-4.2-4.2")
    val Alert = icon("M12 3 2 20h20z", "M12 10v4M12 17h.01")
    val Scan = icon("M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3")
    val ArrowUp = icon("M12 19V5M6 11l6-6 6 6")

    /** Hex Code's mark: a rounded square with three bars. */
    val Mark = icon("M8.5 2.5h7a6 6 0 0 1 6 6v7a6 6 0 0 1-6 6h-7a6 6 0 0 1-6-6v-7a6 6 0 0 1 6-6z", "M8.5 10v4M12 7.5v9M15.5 9.5v5")

    private fun icon(
        vararg paths: String,
        stroke: Float = 1.75f,
    ): ImageVector =
        ImageVector
            .Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach { d ->
                    addPath(
                        pathData = PathParser().parsePathString(d).toNodes(),
                        fill = null,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = stroke,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }.build()
}
