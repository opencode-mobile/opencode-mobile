package dev.opencode.mobile.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme

enum class OpenCodeFileIconKind {
    File,
    Directory,
}

/**
 * File or folder icon picked from a path's name or extension, ported from
 * opencode's `FileIcon` (`packages/ui/src/components/file-icon.tsx`).
 *
 * [mono] draws the icon in [LocalContentColor] instead of its colours.
 */
@Composable
fun OpenCodeFileIcon(
    path: String,
    modifier: Modifier = Modifier,
    kind: OpenCodeFileIconKind = OpenCodeFileIconKind.File,
    expanded: Boolean = false,
    mono: Boolean = false,
    size: Dp = 24.dp,
) {
    val painter = painterResource(OpenCodeFileIconLookup.drawable(path, kind, expanded))
    if (mono) {
        Icon(painter = painter, contentDescription = null, modifier = modifier.size(size), tint = LocalContentColor.current)
    } else {
        Image(painter = painter, contentDescription = null, modifier = modifier.size(size))
    }
}

object OpenCodeFileIconLookup {
    @DrawableRes
    fun drawable(
        path: String,
        kind: OpenCodeFileIconKind,
        expanded: Boolean,
    ): Int =
        OpenCodeFileTypeIconTables.drawables[iconName(path, kind, expanded)]
            ?: OpenCodeFileTypeIconTables.drawables.getValue(OpenCodeFileTypeIconTables.DEFAULT_FILE)

    /**
     * Upstream `chooseIconName`: file names, then dotted suffixes longest
     * first, then the default. Folders try `name`, `.name`, `_name`, `__name__`.
     */
    fun iconName(
        path: String,
        kind: OpenCodeFileIconKind,
        expanded: Boolean,
    ): String {
        val base = basename(path).lowercase()
        if (kind == OpenCodeFileIconKind.Directory) {
            for (candidate in listOf(base, ".$base", "_$base", "__${base}__")) {
                val icon = OpenCodeFileTypeIconTables.folderNames[candidate] ?: continue
                return if (expanded) openVariant(icon) else icon
            }
            return if (expanded) {
                OpenCodeFileTypeIconTables.DEFAULT_FOLDER_OPEN
            } else {
                OpenCodeFileTypeIconTables.DEFAULT_FOLDER
            }
        }
        OpenCodeFileTypeIconTables.fileNames[base]?.let { return it }
        for (suffix in dottedSuffixesLongestFirst(base)) {
            OpenCodeFileTypeIconTables.fileExtensions[suffix]?.let { return it }
        }
        return OpenCodeFileTypeIconTables.DEFAULT_FILE
    }

    private fun basename(path: String): String = path.replace('\\', '/').split('/').lastOrNull { it.isNotEmpty() } ?: ""

    /** The whole name plus every suffix after a dot, longest first. */
    private fun dottedSuffixesLongestFirst(name: String): List<String> {
        val suffixes = linkedSetOf(name)
        name.forEachIndexed { index, char ->
            if (char == '.' && index + 1 < name.length) suffixes += name.substring(index + 1)
        }
        // Suffixes of one string are unique by length, so this order is total.
        return suffixes.sortedByDescending { it.length }
    }

    private fun openVariant(icon: String): String =
        when {
            !icon.startsWith("Folder") -> icon
            icon.endsWith("_light") -> icon.replace("_light", "Open_light")
            !icon.endsWith("Open") -> icon + "Open"
            else -> icon
        }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeFileIconPreview() {
    OpenCodeTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Package.swift", "build.gradle", "README.md", "src/app.test.ts", "unknown.zzz").forEach { path ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenCodeFileIcon(path = path, size = 18.dp)
                    Text(path)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenCodeFileIcon(path = "src", kind = OpenCodeFileIconKind.Directory, size = 18.dp)
                OpenCodeFileIcon(path = "src", kind = OpenCodeFileIconKind.Directory, expanded = true, size = 18.dp)
                OpenCodeFileIcon(path = "Main.kt", mono = true, size = 18.dp)
            }
        }
    }
}
