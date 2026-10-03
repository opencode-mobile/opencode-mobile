package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodePageIndicator

@Composable
fun PageIndicatorGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        listOf(3 to 0, 3 to 1, 3 to 2, 5 to 3).forEach { (pageCount, currentPage) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${currentPage + 1} of $pageCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OpenCodePageIndicator(pageCount = pageCount, currentPage = currentPage)
            }
        }
    }
}
