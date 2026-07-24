package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor

val OpenCodeFloatingDockCornerRadius = 26.dp

@Composable
fun OpenCodeFloatingDock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val surfaceRaised = openCodeColor("surface-raised-base")
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(OpenCodeFloatingDockCornerRadius),
        color = surfaceRaised.copy(alpha = 0.95f),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            content = content,
        )
    }
}
