package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeFilterTab
import dev.opencode.mobile.ui.components.OpenCodeFilterTabs
import dev.opencode.mobile.ui.components.OpenCodeFilterTabsStyle

@Composable
fun FilterTabsGalleryScreen(modifier: Modifier = Modifier) {
    var selectedTabId by rememberSaveable { mutableStateOf("all") }
    var segmentedTabId by rememberSaveable { mutableStateOf("running") }
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Horizontal single-select filters with optional count badges.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OpenCodeFilterTabs(
            tabs = filterTabsGallerySamples,
            selectedTabId = selectedTabId,
            onTabSelected = { selectedTabId = it },
        )
        Text(
            text = "Segmented: equal-width views of one list. A dot marks something new since the tab was last viewed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OpenCodeFilterTabs(
            tabs =
                listOf(
                    OpenCodeFilterTab(id = "running", title = "Running", count = 2),
                    OpenCodeFilterTab(id = "done", title = "Done", count = 5, unseen = segmentedTabId != "done"),
                ),
            selectedTabId = segmentedTabId,
            onTabSelected = { segmentedTabId = it },
            style = OpenCodeFilterTabsStyle.Segmented,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

private val filterTabsGallerySamples =
    listOf(
        OpenCodeFilterTab(id = "all", title = "All", count = 5),
        OpenCodeFilterTab(id = "friends", title = "Friends"),
        OpenCodeFilterTab(id = "unread", title = "Unread", count = 45),
        OpenCodeFilterTab(id = "news", title = "News", count = 10),
        OpenCodeFilterTab(id = "mobile", title = "opencode-mobile", count = 3),
    )
