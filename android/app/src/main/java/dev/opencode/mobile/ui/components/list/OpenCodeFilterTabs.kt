package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

private val FilterTabsTabShape = RoundedCornerShape(percent = 50)
private val FilterTabsCountShape = RoundedCornerShape(percent = 50)
private val FilterTabsTabPaddingHorizontal = 12.dp
private val FilterTabsTabPaddingVertical = 8.dp
private val FilterTabsTabMinHeight = 36.dp
private val FilterTabsTabSpacing = 8.dp
private val FilterTabsContentSpacing = 6.dp
private val FilterTabsCountSize = 22.dp
private val FilterTabsCountPaddingHorizontal = 6.dp
private val FilterTabsRowPaddingHorizontal = 16.dp
private val FilterTabsRowPaddingVertical = 8.dp
private val FilterTabsBorderWidth = 1.5.dp

private val FilterTabsSegmentedTrackPadding = 3.dp
private val FilterTabsSegmentedMinHeight = 32.dp
private val FilterTabsUnseenDotSize = 6.dp

data class OpenCodeFilterTab(
    val id: String,
    val title: String,
    val count: Int? = null,
    /** Something new arrived since the tab was last viewed: a small accent dot after the label. */
    val unseen: Boolean = false,
)

/** How [OpenCodeFilterTabs] lays out its tabs. */
enum class OpenCodeFilterTabsStyle {
    /** Outlined capsules in a row that scrolls sideways, with count badges; the default. */
    Scrolling,

    /**
     * A segmented control: equal-width segments on a track, filling the width, the selected one
     * raised. For two or three fixed views of one list, such as Running and Done.
     */
    Segmented,
}

@Composable
fun OpenCodeFilterTabs(
    tabs: List<OpenCodeFilterTab>,
    selectedTabId: String,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: OpenCodeFilterTabsStyle = OpenCodeFilterTabsStyle.Scrolling,
) {
    if (tabs.isEmpty()) return
    if (style == OpenCodeFilterTabsStyle.Segmented) {
        SegmentedFilterTabs(tabs, selectedTabId, onTabSelected, modifier)
        return
    }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(
                    horizontal = FilterTabsRowPaddingHorizontal,
                    vertical = FilterTabsRowPaddingVertical,
                ),
        horizontalArrangement = Arrangement.spacedBy(FilterTabsTabSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            FilterTabItem(
                tab = tab,
                selected = tab.id == selectedTabId,
                onClick = { onTabSelected(tab.id) },
            )
        }
    }
}

@Composable
private fun FilterTabItem(
    tab: OpenCodeFilterTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val labelColor =
        if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val border =
        if (selected) {
            BorderStroke(FilterTabsBorderWidth, MaterialTheme.colorScheme.outline)
        } else {
            null
        }
    val accessibilityLabel = filterTabAccessibilityLabel(tab)

    Surface(
        onClick = onClick,
        modifier =
            Modifier
                .heightIn(min = FilterTabsTabMinHeight)
                .openCodeFocusRing(FilterTabsTabShape)
                .semantics {
                    this.selected = selected
                    contentDescription = accessibilityLabel
                },
        shape = FilterTabsTabShape,
        color = Color.Transparent,
        border = border,
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = FilterTabsTabPaddingHorizontal,
                    vertical = FilterTabsTabPaddingVertical,
                ),
            horizontalArrangement = Arrangement.spacedBy(FilterTabsContentSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tab.title,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            tab.count?.let { count ->
                Box(
                    modifier =
                        Modifier
                            .height(FilterTabsCountSize)
                            .widthIn(min = FilterTabsCountSize)
                            .clip(FilterTabsCountShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = FilterTabsCountPaddingHorizontal),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = count.toString(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle =
                                    LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both,
                                    ),
                            ),
                        maxLines = 1,
                    )
                }
            }
            if (tab.unseen) UnseenDot()
        }
    }
}

@Composable
private fun SegmentedFilterTabs(
    tabs: List<OpenCodeFilterTab>,
    selectedTabId: String,
    onTabSelected: (String) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(FilterTabsTabShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(FilterTabsSegmentedTrackPadding),
        horizontalArrangement = Arrangement.spacedBy(FilterTabsSegmentedTrackPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = tab.id == selectedTabId
            val accessibilityLabel = filterTabAccessibilityLabel(tab)
            Surface(
                onClick = { onTabSelected(tab.id) },
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = FilterTabsSegmentedMinHeight)
                        .semantics {
                            this.selected = selected
                            contentDescription = accessibilityLabel
                        }.openCodeControl(),
                shape = FilterTabsTabShape,
                color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = FilterTabsTabPaddingHorizontal, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(FilterTabsContentSpacing, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    tab.count?.let { count ->
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (tab.unseen) UnseenDot()
                }
            }
        }
    }
}

@Composable
private fun UnseenDot() {
    Box(
        modifier =
            Modifier
                .size(FilterTabsUnseenDotSize)
                .background(openCodeColor("interactive"), CircleShape),
    )
}

/** "Done, 3, new" — the count and the unseen dot are read out with the title. */
internal fun filterTabAccessibilityLabel(tab: OpenCodeFilterTab): String =
    listOfNotNull(tab.title, tab.count?.toString(), "new".takeIf { tab.unseen }).joinToString(", ")

@Preview(showBackground = true)
@Composable
private fun OpenCodeFilterTabsPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeFilterTabs(
            tabs =
                listOf(
                    OpenCodeFilterTab(id = "all", title = "All", count = 5),
                    OpenCodeFilterTab(id = "friends", title = "Friends"),
                    OpenCodeFilterTab(id = "unread", title = "Unread", count = 45),
                    OpenCodeFilterTab(id = "news", title = "News", count = 10),
                ),
            selectedTabId = "all",
            onTabSelected = {},
        )
    }
}
