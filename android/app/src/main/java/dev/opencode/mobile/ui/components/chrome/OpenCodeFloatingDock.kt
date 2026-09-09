package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

val OpenCodeFloatingDockCornerRadius = OpenCodeFloatingChromeCornerRadius

@Composable
fun OpenCodeFloatingDock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    OpenCodeFloatingChrome(
        modifier =
            modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 4.dp),
        shape = RoundedCornerShape(OpenCodeFloatingDockCornerRadius),
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
