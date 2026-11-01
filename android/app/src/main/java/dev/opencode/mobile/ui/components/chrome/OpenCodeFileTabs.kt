package dev.opencode.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val FileTabsRowPaddingHorizontal = 8.dp
private val FileTabsRowPaddingVertical = 6.dp
private val FileTabsSpacing = 4.dp
private val FileTabTitleStartPadding = 10.dp
private val FileTabTitleEndPadding = 2.dp
private val FileTabTitleMaxWidth = 180.dp
private val FileTabCloseTarget = 32.dp
private val FileTabCloseIconSize = 14.dp

/** One open file in [OpenCodeFileTabs]. */
data class OpenCodeFileTab(
    val id: String,
    /** File name shown on the tab. */
    val title: String,
)

/**
 * Horizontally scrolling strip of open files for the tablet File Viewer: tap a tab to select it,
 * × to close it. The selected tab scrolls into view.
 */
@Composable
fun OpenCodeFileTabs(
    tabs: List<OpenCodeFileTab>,
    selectedTabId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    if (tabs.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = FileTabsRowPaddingHorizontal, vertical = FileTabsRowPaddingVertical),
            horizontalArrangement = Arrangement.spacedBy(FileTabsSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                FileTabItem(
                    tab = tab,
                    selected = tab.id == selectedTabId,
                    onSelect = { onSelect(tab.id) },
                    onClose = { onClose(tab.id) },
                )
            }
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(openCodeColor("border-weaker-base")),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTabItem(
    tab: OpenCodeFileTab,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val shape = OpenCodeSmallShape
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) bringIntoView.bringIntoView() }
    Row(
        modifier =
            Modifier
                .bringIntoViewRequester(bringIntoView)
                .clip(shape)
                .background(if (selected) openCodeColor("surface-raised-base") else Color.Transparent, shape)
                .border(1.dp, if (selected) openCodeColor("border-weak-base") else Color.Transparent, shape)
                .openCodeFocusRing(shape)
                .clickable(onClick = onSelect)
                .semantics {
                    role = Role.Tab
                    this.selected = selected
                    contentDescription = tab.title
                }.padding(start = FileTabTitleStartPadding, end = FileTabTitleEndPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tab.title,
            modifier = Modifier.widthIn(max = FileTabTitleMaxWidth),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) openCodeColor("text-strong") else openCodeColor("text-weak"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier =
                Modifier
                    .size(FileTabCloseTarget)
                    .clip(shape)
                    .openCodeFocusRing(shape)
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "Close ${tab.title}" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = OpenCodeIcons.Close,
                contentDescription = null,
                modifier = Modifier.size(FileTabCloseIconSize),
                tint = openCodeColor("icon-base"),
            )
        }
    }
}

@Preview(showBackground = true, name = "File Tabs")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "File Tabs Dark")
@Composable
private fun OpenCodeFileTabsPreview() {
    OpenCodeTheme {
        OpenCodeFileTabs(
            tabs =
                listOf(
                    OpenCodeFileTab(id = "README.md", title = "README.md"),
                    OpenCodeFileTab(id = "src/main.kt", title = "main.kt"),
                    OpenCodeFileTab(id = "src/ui/Button.kt", title = "Button.kt"),
                ),
            selectedTabId = "src/main.kt",
            onSelect = {},
            onClose = {},
        )
    }
}
