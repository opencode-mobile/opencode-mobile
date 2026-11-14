package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.components.OpenCodePickerCard
import dev.opencode.mobile.ui.components.OpenCodePickerCardRow
import dev.opencode.mobile.ui.components.OpenCodePickerCardTile
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun PickerCardGalleryScreen(modifier: Modifier = Modifier) {
    val project = OpenCodePickerCardRow("Project", "opencode-mobile", OpenCodePickerCardTile.Letter('O', LetterAccent.Teal), onClick = {})
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        OpenCodePickerCard(
            caption = "Run session in",
            rows =
                listOf(
                    project,
                    OpenCodePickerCardRow("Worktree", "Local", OpenCodePickerCardTile.Icon(OpenCodeIcons.Terminal), onClick = {}),
                ),
            footnote = "/Users/example/project",
            footnoteMonospace = true,
        )
        OpenCodePickerCard(
            caption = "Run session in",
            rows =
                listOf(
                    project,
                    OpenCodePickerCardRow(
                        "Worktree",
                        "New worktree",
                        OpenCodePickerCardTile.Icon(OpenCodeIcons.Add, highlighted = true),
                        onClick = {},
                    ),
                    OpenCodePickerCardRow(
                        "Starts from",
                        "main",
                        OpenCodePickerCardTile.Icon(OpenCodeIcons.CallSplit),
                        monospaceValue = true,
                        onClick = {},
                    ),
                ),
            footnote = "Created when you send",
            compact = true,
        )
        OpenCodePickerCard(
            caption = "Run session in",
            rows =
                listOf(
                    project,
                    OpenCodePickerCardRow(
                        "Worktree",
                        "None · not a Git repo",
                        OpenCodePickerCardTile.Icon(OpenCodeIcons.Folder),
                        enabled = false,
                    ),
                ),
        )
    }
}
