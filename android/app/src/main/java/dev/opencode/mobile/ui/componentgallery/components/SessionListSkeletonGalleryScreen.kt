package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSessionListSkeleton
import dev.opencode.mobile.ui.components.OpenCodeSessionListSkeletonRow
import dev.opencode.mobile.ui.components.SessionListScopeLoad

@Composable
fun SessionListSkeletonGalleryScreen(modifier: Modifier = Modifier) {
    var slow by rememberSaveable { mutableStateOf(false) }
    var accented by rememberSaveable { mutableStateOf(true) }
    var rowCount by rememberSaveable { mutableIntStateOf(6) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge(label = "Slow caption", selected = slow, onClick = { slow = !slow })
            OpenCodeChoiceBadge(label = "Project accent", selected = accented, onClick = { accented = !accented })
            OpenCodeChoiceBadge(
                label = if (rowCount == 6) "6 rows" else "3 rows",
                selected = rowCount == 3,
                onClick = { rowCount = if (rowCount == 6) 3 else 6 },
            )
        }
        OpenCodeSectionHeader(title = "Row")
        OpenCodeSessionListSkeletonRow(accent = if (accented) LetterAccent.Teal else null)
        OpenCodeSectionHeader(title = "List")
        OpenCodeSessionListSkeleton(
            rowCount = rowCount,
            accent = if (accented) LetterAccent.Violet else null,
            title = "opencode-mobile",
            caption = if (slow) SessionListScopeLoad.SLOW_CAPTION else null,
        )
    }
}
