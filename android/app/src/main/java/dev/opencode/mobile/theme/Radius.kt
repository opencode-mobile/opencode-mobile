package dev.opencode.mobile.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Shared corner radii for design-system components. */
object OpenCodeRadius {
    val small = 8.dp
    val medium = 12.dp

    /** A floating pill's corner (half its 36 dp height), for cards anchored to a pill. */
    val large = 18.dp
    val pill = 999.dp
}

val OpenCodeSmallShape = RoundedCornerShape(OpenCodeRadius.small)
val OpenCodeMediumShape = RoundedCornerShape(OpenCodeRadius.medium)
val OpenCodeLargeShape = RoundedCornerShape(OpenCodeRadius.large)
val OpenCodePillShape = RoundedCornerShape(OpenCodeRadius.pill)
