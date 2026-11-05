package dev.opencode.hexcode.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Corner radii (hex-design-system.md, Shape, space and motion). */
@Immutable
class HexRadii(
    val xs: Dp = 5.dp,
    val s: Dp = 8.dp,
    val m: Dp = 10.dp,
    val l: Dp = 12.dp,
    val xl: Dp = 14.dp,
    val sheet: Dp = 20.dp,
)

/** Page edges. */
@Immutable
class HexSpacing(
    /** First run, pickers, settings. */
    val screen: Dp = 18.dp,
    /** Home. */
    val screenHome: Dp = 22.dp,
)

/** Fixed sizes: rows, touch targets and icons. */
@Immutable
class HexSizes(
    /** A row's minimum height. */
    val row: Dp = 48.dp,
    /** A project or folder row. */
    val pickerRow: Dp = 62.dp,
    /** A model row. */
    val modelRow: Dp = 46.dp,
    /** The smallest touch target. */
    val touch: Dp = 44.dp,
    /** An icon in a small button. */
    val iconSmall: Dp = 13.dp,
    /** An icon in a row or the talk button. */
    val icon: Dp = 16.dp,
    /** An icon in an icon button. */
    val iconButton: Dp = 19.dp,
)
