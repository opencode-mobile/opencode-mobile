package dev.opencode.mobile.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Shared corner radii for design-system components. */
object OpenCodeRadius {
    val small = 8.dp
    val medium = 12.dp
    val pill = 999.dp
}

val OpenCodeSmallShape = RoundedCornerShape(OpenCodeRadius.small)
val OpenCodeMediumShape = RoundedCornerShape(OpenCodeRadius.medium)
val OpenCodePillShape = RoundedCornerShape(OpenCodeRadius.pill)
