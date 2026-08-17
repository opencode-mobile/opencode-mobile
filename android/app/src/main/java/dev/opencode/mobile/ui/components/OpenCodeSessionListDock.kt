package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.badgeColors
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val NewSessionButtonShape = RoundedCornerShape(percent = 50)
private val ScopeControlShape = RoundedCornerShape(14.dp)

@Composable
fun OpenCodeSessionListDock(
    filterTabs: List<SessionListFilterTab>,
    selectedFilterId: String,
    onOpenProjectPicker: () -> Unit,
    onAddProject: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    createError: String? = null,
) {
    val textStrong = openCodeColor("text-strong")
    val error = openCodeColor("error")
    val iconStrong = openCodeColor("icon-strong-base")
    val ctaFill = textStrong.copy(alpha = 0.14f)
    val selectedTab = filterTabs.firstOrNull { it.id == selectedFilterId } ?: filterTabs.firstOrNull()

    OpenCodeFloatingDock(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            createError?.let {
                Text(
                    text = it,
                    color = error,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            selectedTab?.let { tab ->
                SessionListScopeControl(
                    tab = tab,
                    onClick = onOpenProjectPicker,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockCta(
                    onClick = onAddProject,
                    fill = ctaFill,
                    shape = CircleShape,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        imageVector = OpenCodeIcons.CreateNewFolder,
                        contentDescription = "Add project",
                        tint = iconStrong,
                        modifier = Modifier.size(22.dp),
                    )
                }
                DockCta(
                    onClick = onNewSession,
                    fill = ctaFill,
                    shape = NewSessionButtonShape,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(52.dp),
                ) {
                    Text(
                        text = "New session",
                        color = textStrong,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    )
                }
            }
        }
    }
}

@Composable
fun SessionListScopeControl(
    tab: SessionListFilterTab,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStrong = openCodeColor("text-strong")
    val textWeak = openCodeColor("text-weak")
    val subtitle = displayedScopeSubtitle(tab)
    val description =
        if (subtitle.isEmpty()) {
            "${tab.title}, ${tab.count} sessions"
        } else {
            "${tab.title}, $subtitle, ${tab.count} sessions"
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(ScopeControlShape)
                .background(textStrong.copy(alpha = 0.08f))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description }
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SessionListScopeMark(tab = tab)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tab.title,
                color = textStrong,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = textWeak,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SessionListScopeCountBadge(count = tab.count)
        Icon(
            imageVector = OpenCodeIcons.KeyboardArrowDown,
            contentDescription = null,
            tint = textWeak,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
fun SessionListScopeMark(
    tab: SessionListFilterTab,
    modifier: Modifier = Modifier,
) {
    val letter = tab.letter
    val accent = tab.letterAccent
    if (letter != null && accent != null) {
        val dark = isSystemInDarkTheme()
        val (fill, tint) = accent.badgeColors(dark)
        Box(
            modifier =
                modifier
                    .size(32.dp)
                    .background(fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = letter.toString(),
                color = tint,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        val textStrong = openCodeColor("text-strong")
        Box(
            modifier =
                modifier
                    .size(32.dp)
                    .background(textStrong.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = OpenCodeIcons.Widgets,
                contentDescription = null,
                tint = textStrong,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
fun SessionListScopeCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    val textStrong = openCodeColor("text-strong")
    Text(
        text = count.toString(),
        color = textStrong,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        modifier =
            modifier
                .background(textStrong.copy(alpha = 0.10f), RoundedCornerShape(percent = 50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun SessionListProjectPickerRows(
    tabs: List<SessionListFilterTab>,
    query: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRemoveProject: ((String) -> Unit)? = null,
) {
    val matches = SessionListProjectPickerMatching.tabs(tabs, query)
    if (matches.isEmpty()) {
        Text(
            text = "No matches",
            color = openCodeColor("text-weak"),
            style = MaterialTheme.typography.bodyMedium,
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        )
        return
    }
    matches.forEach { tab ->
        OpenCodeListItem(
            title = tab.title,
            subtitle = displayedScopeSubtitle(tab),
            startDecorator = pickerStartDecorator(tab),
            onClick = { onSelect(tab.id) },
            onLongPress =
                if (tab.isAll) {
                    null
                } else {
                    onRemoveProject?.let { remove -> { remove(tab.id) } }
                },
            trailing = { SessionListScopeCountBadge(count = tab.count) },
            modifier = modifier,
        )
    }
}

private fun displayedScopeSubtitle(tab: SessionListFilterTab): String = if (tab.isAll) tab.subtitle else compactHomePath(tab.subtitle)

private fun pickerStartDecorator(tab: SessionListFilterTab): StartDecorator {
    val letter = tab.letter
    val accent = tab.letterAccent
    return if (letter != null && accent != null) {
        StartDecorator.Letter(letter, accent)
    } else {
        StartDecorator.Icon(OpenCodeIcons.Widgets, null)
    }
}

@Composable
private fun DockCta(
    onClick: () -> Unit,
    fill: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(fill)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionListDockPreview() {
    OpenCodeTheme(darkTheme = true) {
        Box(modifier = Modifier.padding(16.dp)) {
            OpenCodeSessionListDock(
                filterTabs =
                    listOf(
                        SessionListFilterTab.all(projectCount = 3, sessionCount = 4),
                        SessionListFilterTab.project(
                            worktree = "/a",
                            title = "opencode-mobile",
                            count = 2,
                        ),
                        SessionListFilterTab.project(worktree = "/b", title = "playground", count = 1),
                    ),
                selectedFilterId = SessionListFilterTab.ALL_ID,
                onOpenProjectPicker = {},
                onAddProject = {},
                onNewSession = {},
            )
        }
    }
}
