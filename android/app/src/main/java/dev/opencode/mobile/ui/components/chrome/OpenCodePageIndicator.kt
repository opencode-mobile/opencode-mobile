package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

/** Pager position dots: the current page is a wide capsule, the others short dots. */
@Composable
fun OpenCodePageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    require(pageCount > 0) { "Page Indicator needs at least one page" }
    Row(
        modifier =
            modifier.clearAndSetSemantics {
                contentDescription = "Page ${currentPage + 1} of $pageCount"
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            val width by animateDpAsState(if (selected) 18.dp else 7.dp, label = "PageIndicatorWidth")
            Box(
                modifier =
                    Modifier
                        .height(7.dp)
                        .width(width)
                        .clip(RoundedCornerShape(4.dp))
                        .background(openCodeColor(if (selected) "text-strong" else "text-weaker")),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodePageIndicatorPreview() {
    OpenCodeTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            repeat(3) { page -> OpenCodePageIndicator(pageCount = 3, currentPage = page) }
        }
    }
}
