package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.icons.OpenCodeFileIcon
import dev.opencode.mobile.ui.icons.OpenCodeFileIconKind
import kotlin.math.max

private val FileIconCellMinWidth = 96.dp
private val FileIconGridSpacing = 12.dp

private val FileIconGalleryFiles =
    listOf(
        "Package.swift",
        "App.swift",
        "build.gradle",
        "Main.kt",
        "package.json",
        "index.ts",
        "App.tsx",
        "foo.test.ts",
        "README.md",
        "Dockerfile",
        ".gitignore",
        "notes.zzz",
    )

private val FileIconGalleryFolders = listOf("src", "tests", ".github", "docs", "node_modules", "assets")

@Composable
fun FileIconGalleryScreen(modifier: Modifier = Modifier) {
    var mono by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Picked from the file name or extension, like opencode's FileIcon.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge(label = "Colour", selected = !mono, onClick = { mono = false })
            OpenCodeChoiceBadge(label = "Mono", selected = mono, onClick = { mono = true })
        }
        CompositionLocalProvider(LocalContentColor provides openCodeColor("icon-strong-base")) {
            FileIconGrid(title = "Files", labels = FileIconGalleryFiles) { path ->
                OpenCodeFileIcon(path = path, mono = mono)
            }
            FileIconGrid(title = "Folders, collapsed and expanded", labels = FileIconGalleryFolders) { path ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OpenCodeFileIcon(path = path, kind = OpenCodeFileIconKind.Directory, mono = mono)
                    OpenCodeFileIcon(path = path, kind = OpenCodeFileIconKind.Directory, expanded = true, mono = mono)
                }
            }
        }
    }
}

@Composable
private fun FileIconGrid(
    title: String,
    labels: List<String>,
    icon: @Composable (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = openCodeColor("text-strong"),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns =
                max(1, ((maxWidth + FileIconGridSpacing) / (FileIconCellMinWidth + FileIconGridSpacing)).toInt())
            val cellWidth = (maxWidth - FileIconGridSpacing * (columns - 1)) / columns
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                labels.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(FileIconGridSpacing)) {
                        row.forEach { label ->
                            Column(
                                modifier = Modifier.width(cellWidth),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.height(32.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) { icon(label) }
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
