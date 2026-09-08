package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun OpenCodeListGroup(
    itemCount: Int,
    modifier: Modifier = Modifier,
    rowContent: @Composable (Int) -> Unit,
) {
    if (itemCount <= 0) return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = openCodeColor("surface-base"),
        border = BorderStroke(1.dp, openCodeColor("border-weak-base")),
    ) {
        Column {
            repeat(itemCount) { index ->
                rowContent(index)
                if (index < itemCount - 1) {
                    HorizontalDivider(color = openCodeColor("border-weaker-base"))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeListGroupPreview() {
    OpenCodeTheme(darkTheme = false) {
        val files = listOf("Sources/App.kt", "README.md", "Tests/AppTest.kt")
        OpenCodeListGroup(itemCount = files.size) { index ->
            OpenCodeListItem(
                title = files[index],
                trailing = { androidx.compose.material3.Icon(OpenCodeIcons.ChevronRight, contentDescription = null) },
            )
        }
    }
}
