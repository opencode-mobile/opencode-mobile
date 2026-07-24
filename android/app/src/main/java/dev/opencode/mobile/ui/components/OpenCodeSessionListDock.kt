package dev.opencode.mobile.ui.components

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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** Full pill — ends read as half-circles at the control height. */
private val NewSessionButtonShape = RoundedCornerShape(percent = 50)

@Composable
fun OpenCodeSessionListDock(
    filterTabs: List<SessionListFilterTab>,
    selectedFilterId: String,
    onFilterSelected: (String) -> Unit,
    onAddProject: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    createError: String? = null,
) {
    val textStrong = openCodeColor("text-strong")
    val borderWeak = openCodeColor("border-weak-base")
    val error = openCodeColor("error")
    val iconStrong = openCodeColor("icon-strong-base")

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

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filterTabs.forEach { tab ->
                    OpenCodeFilterChip(
                        title = tab.title,
                        count = tab.count,
                        selected = tab.id == selectedFilterId,
                        onClick = { onFilterSelected(tab.id) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockCta(
                    onClick = onAddProject,
                    borderWeak = borderWeak,
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
                    borderWeak = borderWeak,
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
private fun DockCta(
    onClick: () -> Unit,
    borderWeak: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .clip(shape)
                .border(width = 1.5.dp, color = borderWeak, shape = shape)
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
                        SessionListFilterTab(id = SessionListFilterTab.ALL_ID, title = "All", count = 4),
                        SessionListFilterTab(id = "/a", title = "opencode-mobile", count = 2),
                        SessionListFilterTab(id = "/b", title = "playground", count = 1),
                    ),
                selectedFilterId = SessionListFilterTab.ALL_ID,
                onFilterSelected = {},
                onAddProject = {},
                onNewSession = {},
            )
        }
    }
}
