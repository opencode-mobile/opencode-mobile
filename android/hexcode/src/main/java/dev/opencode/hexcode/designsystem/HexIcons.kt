package dev.opencode.hexcode.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Hex Code's icons: the prototype's set (`const P` in index.html), under the same names as iOS. A 24 × 24 grid,
 * 1.75 stroke, round caps and joins. Draw them with `Icon(HexIcons.mic, …)`: they take the tint (the content colour).
 */
@Suppress("ktlint:standard:property-naming", "MemberVisibilityCanBePrivate")
object HexIcons {
    val mic by icon { path("M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z", "M19 11a7 7 0 0 1-14 0M12 18v3") }
    val clip by icon { path("m21 11-8.6 8.6a5.5 5.5 0 0 1-7.8-7.8l8.6-8.6a3.7 3.7 0 0 1 5.2 5.2l-8.6 8.6a1.8 1.8 0 0 1-2.6-2.6L15 6.6") }
    val stop by icon { rect(6.5f, 6.5f, 11f, 11f, 2f, filled = true) }
    val refresh by icon { path("M21 12a9 9 0 1 1-2.6-6.4L21 8", "M21 3v5h-5") }
    val check by icon { path("m5 12.5 4.5 4.5L19 7.5", width = 2.4f) }
    val x by icon { path("M6 6l12 12M18 6 6 18", width = 2.2f) }
    val out by icon { path("M7 17 17 7M8 7h9v9") }
    val undo by icon { path("M9 14 4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11") }
    val doc by icon { path("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5M9 13h6M9 17h4") }
    val chevR by icon { path("m9 6 6 6-6 6") }
    val chevD by icon { path("m6 9 6 6 6-6") }
    val chevL by icon { path("m15 6-6 6 6 6") }
    val chevU by icon { path("m6 15 6-6 6 6") }
    val lock by icon {
        rect(5f, 11f, 14f, 10f, 2f)
        path("M8 11V7a4 4 0 0 1 8 0v4")
    }
    val pause by icon {
        rect(7f, 6f, 3.2f, 12f, 1f, filled = true)
        rect(13.8f, 6f, 3.2f, 12f, 1f, filled = true)
    }
    val share by icon { path("M12 3v13M7 8l5-5 5 5", "M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6") }
    val compass by icon {
        circle(12f, 12f, 9f)
        path("m15.5 8.5-2 5-5 2 2-5z")
    }
    val book by icon { path("M4 19V5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2zm0 0a2 2 0 0 0 2 2h13") }
    val folder by icon { path("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z") }
    val cpu by icon {
        rect(6f, 6f, 12f, 12f, 2f)
        path("M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4")
    }
    val server by icon {
        rect(3f, 4f, 18f, 7f, 2f)
        rect(3f, 13f, 18f, 7f, 2f)
        path("M7 7.5h.01M7 16.5h.01")
    }
    val bars by icon { path("M6 10v4M10 7v10M14 9v6M18 11v2") }
    val card by icon {
        rect(3f, 4f, 18f, 16f, 3f)
        path("M7 9h10M7 13h6")
    }
    val steer by icon { path("M5 12h14M13 6l6 6-6 6") }
    val send by icon { path("M12 19V5M6 11l6-6 6 6") }
    val trash by icon { path("M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13") }
    val spark by icon { path("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z") }
    val target by icon {
        circle(12f, 12f, 8.5f)
        circle(12f, 12f, 4.5f)
        circle(12f, 12f, 1f, filled = true, stroked = true)
    }
    val ask by icon {
        circle(12f, 12f, 9f)
        path("M9.6 9.4a2.5 2.5 0 1 1 3.4 2.4c-.6.3-1 .8-1 1.5v.4M12 17h.01")
    }
    val play by icon { path("M8 5.5v13l10.5-6.5z", filled = true) }
    val speaker by icon { path("M4 9.5v5h3.5L12 18V6L7.5 9.5z", "M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11") }
    val copy by icon {
        rect(8.5f, 8.5f, 11f, 11f, 2.5f)
        path("M15.5 8.5V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v7.5a2 2 0 0 0 2 2h2.5")
    }
    val gear by icon {
        circle(12f, 12f, 3f)
        path(
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21" +
                "a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1" +
                "a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8" +
                "l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1" +
                "a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9" +
                "a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
        )
    }
    val search by icon {
        circle(11f, 11f, 6.5f)
        path("m20 20-4.2-4.2")
    }
    val more by icon {
        circle(5f, 12f, 1.4f, filled = true, stroked = false)
        circle(12f, 12f, 1.4f, filled = true, stroked = false)
        circle(19f, 12f, 1.4f, filled = true, stroked = false)
    }
    val pencil by icon { path("M4 20h4L19 9l-4-4L4 16z") }
    val term by icon { path("m5 8 4 4-4 4M12 16h7") }
    val eye by icon {
        path("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z")
        circle(12f, 12f, 3f)
    }
    val info by icon {
        circle(12f, 12f, 9f)
        path("M12 8h.01M11 12h1v5h1")
    }
    val img by icon {
        rect(3f, 4f, 18f, 16f, 3f)
        circle(9f, 10f, 2f)
        path("m21 16-5-5-9 9")
    }
    val scan by icon { path("M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3") }
    val alert by icon { path("M12 3 2 20h20z", "M12 10v4M12 17h.01") }
    val moon by icon { path("M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z") }
    val sun by icon {
        circle(12f, 12f, 4f)
        path("M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")
    }
    val globe by icon {
        circle(12f, 12f, 9f)
        path("M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z")
    }
    val monitor by icon {
        rect(3f, 4f, 18f, 12f, 2f)
        path("M8 20h8M12 16v4")
    }

    /** The spinner (the prototype's `SI.spin`): a faint ring and a quarter arc, on a 16 grid. `HexSpinner` turns it. */
    val spin by icon(grid = 16f) {
        circle(8f, 8f, 6f, width = 1.5f, alpha = 0.25f)
        path("M8 2a6 6 0 0 1 6 6", width = 1.5f)
    }

    /** A to-do item not started (the prototype's `SI.todo`): a ring, on a 16 grid. Drawn in `text3`. */
    val todoPending by icon(grid = 16f) { circle(8f, 8f, 6f, width = 1.5f) }

    /** A to-do item in progress (`SI.doing`): the ring with its right half filled. Drawn in `solid`. */
    val todoDoing by icon(grid = 16f) {
        circle(8f, 8f, 6f, width = 1.5f)
        path("M8 4.5a3.5 3.5 0 0 1 0 7z", filled = true, stroked = false)
    }

    /**
     * A to-do item done (`SI.done`): a filled disc with the check cut out of it, so the background shows through as the
     * prototype's check in `--bg` does. Drawn in `text2`.
     */
    val todoDone by icon(grid = 16f) {
        path(
            "M1 8a7 7 0 1 0 14 0a7 7 0 1 0 -14 0z" +
                // The check's 1.6-wide stroke (m5.2 8.2 1.9 1.9 3.8-4) as an outline with round ends.
                "M5.7657 7.6343L7.0853 8.9539L10.32 5.549A0.8 0.8 0 0 1 11.48 6.651L7.68 10.651" +
                "A0.8 0.8 0 0 1 6.5343 10.6657L4.6343 8.7657A0.8 0.8 0 0 1 5.7657 7.6343z",
            filled = true,
            stroked = false,
            evenOdd = true,
        )
    }

    /** Hex Code's mark (the prototype's `MARK`): a rounded square with three bars, 1.9 stroke. */
    val mark by icon {
        rect(2.5f, 2.5f, 19f, 19f, 6f, width = 1.9f)
        path("M8.5 10v4M12 7.5v9M15.5 9.5v5", width = 1.9f)
    }
}

private class IconScope(
    private val builder: ImageVector.Builder,
) {
    fun path(
        vararg data: String,
        width: Float = 1.75f,
        filled: Boolean = false,
        stroked: Boolean = true,
        alpha: Float = 1f,
        /** Overlapping shapes cut holes in each other, as a check cut out of a disc. */
        evenOdd: Boolean = false,
    ) {
        data.forEach { d ->
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (stroked) SolidColor(Color.Black) else null,
                strokeAlpha = alpha,
                fillAlpha = alpha,
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }

    fun rect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        r: Float,
        filled: Boolean = false,
        width: Float = 1.75f,
    ) = path(
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 -$r ${r}h${-(w - 2 * r)}" +
            "a$r $r 0 0 1 -$r -${r}v${-(h - 2 * r)}a$r $r 0 0 1 $r -${r}z",
        width = width,
        filled = filled,
        stroked = !filled,
    )

    fun circle(
        cx: Float,
        cy: Float,
        r: Float,
        filled: Boolean = false,
        stroked: Boolean = !filled,
        width: Float = 1.75f,
        alpha: Float = 1f,
    ) = path(
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z",
        width = width,
        filled = filled,
        stroked = stroked,
        alpha = alpha,
    )
}

/** Builds the icon once, on first use. */
private fun icon(
    grid: Float = 24f,
    draw: IconScope.() -> Unit,
): Lazy<ImageVector> =
    lazy {
        val builder =
            ImageVector.Builder(
                defaultWidth = grid.dp,
                defaultHeight = grid.dp,
                viewportWidth = grid,
                viewportHeight = grid,
            )
        IconScope(builder).draw()
        builder.build()
    }
