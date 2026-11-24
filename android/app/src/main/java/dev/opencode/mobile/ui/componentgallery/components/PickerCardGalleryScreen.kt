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
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PickerCardGalleryLocal()
        PickerCardGalleryNewWorktree()
        PickerCardGalleryNotGit()
    }
}

@Composable
internal fun PickerCardGalleryLocal() {
    OpenCodePickerCard(
        caption = "Run session in",
        rows =
            listOf(
                pickerCardGalleryProject,
                OpenCodePickerCardRow("Worktree", "Local", OpenCodePickerCardTile.Icon(OpenCodeIcons.Terminal), onClick = {}),
            ),
        footnote = "/Users/example/project",
        footnoteMonospace = true,
    )
}

@Composable
internal fun PickerCardGalleryNewWorktree() {
    OpenCodePickerCard(
        caption = "Run session in",
        rows =
            listOf(
                pickerCardGalleryProject,
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
}

@Composable
internal fun PickerCardGalleryNotGit() {
    OpenCodePickerCard(
        caption = "Run session in",
        rows =
            listOf(
                pickerCardGalleryProject,
                OpenCodePickerCardRow(
                    "Worktree",
                    "None · not a Git repo",
                    OpenCodePickerCardTile.Icon(OpenCodeIcons.Folder),
                    enabled = false,
                ),
            ),
    )
}

private val pickerCardGalleryProject =
    OpenCodePickerCardRow("Project", "opencode-mobile", OpenCodePickerCardTile.Letter('O', LetterAccent.Teal), onClick = {})
