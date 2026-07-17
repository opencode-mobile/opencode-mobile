package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetContentMode
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeaderAction
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private enum class GalleryBottomSheetDecoratorKind {
    None,
    Icon,
}

@Composable
fun BottomSheetGalleryScreen(modifier: Modifier = Modifier) {
    var visible by rememberSaveable { mutableStateOf(false) }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var showsHeaderBack by rememberSaveable { mutableStateOf(false) }
    var itemCount by rememberSaveable { mutableStateOf(2) }
    var startDecoratorName by rememberSaveable { mutableStateOf(GalleryBottomSheetDecoratorKind.Icon.name) }
    var endDecoratorName by rememberSaveable { mutableStateOf(GalleryBottomSheetDecoratorKind.None.name) }
    var formMode by rememberSaveable { mutableStateOf(false) }
    var alwaysFixedHeight by rememberSaveable { mutableStateOf(false) }
    var formValue by rememberSaveable { mutableStateOf("http://10.0.2.2:4096/") }

    val startDecoratorKind = galleryDecoratorKind(startDecoratorName)
    val endDecoratorKind = galleryDecoratorKind(endDecoratorName)
    val startDecorator =
        when (startDecoratorKind) {
            GalleryBottomSheetDecoratorKind.None -> BottomSheetEntryDecorator.None
            GalleryBottomSheetDecoratorKind.Icon -> BottomSheetEntryDecorator.Icon(OpenCodeIcons.Folder, "Project")
        }
    val endDecorator =
        when (endDecoratorKind) {
            GalleryBottomSheetDecoratorKind.None -> BottomSheetEntryDecorator.None
            GalleryBottomSheetDecoratorKind.Icon -> BottomSheetEntryDecorator.Icon(OpenCodeIcons.ChevronRight, null)
        }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Bottom sheet", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenCodeChoiceBadge(label = "Entry enabled", selected = enabled, onClick = { enabled = !enabled })
                OpenCodeChoiceBadge(label = "Header back", selected = showsHeaderBack, onClick = { showsHeaderBack = !showsHeaderBack })
                OpenCodeChoiceBadge(label = "Form mode", selected = formMode, onClick = { formMode = !formMode })
                OpenCodeChoiceBadge(
                    label = "Fixed 50%",
                    selected = alwaysFixedHeight,
                    onClick = { alwaysFixedHeight = !alwaysFixedHeight },
                )
            }
        }

        DecoratorControls(
            title = "Start decorator",
            selected = startDecoratorKind,
            onSelect = { startDecoratorName = it.name },
        )
        DecoratorControls(
            title = "End decorator",
            selected = endDecoratorKind,
            onSelect = { endDecoratorName = it.name },
        )
        ItemCountControls(selected = itemCount, onSelect = { itemCount = it })

        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Show bottom sheet"),
            onClick = { visible = true },
        )
    }

    OpenCodeBottomSheet(
        visible = visible,
        title = if (showsHeaderBack) "Anthropic" else "Session actions",
        onDismissRequest = { visible = false },
        headerLeadingAction =
            if (showsHeaderBack) {
                OpenCodeBottomSheetHeaderAction(
                    imageVector = OpenCodeIcons.ArrowBack,
                    contentDescription = "Back to providers",
                    onClick = { showsHeaderBack = false },
                )
            } else {
                null
            },
        contentMode = if (formMode) OpenCodeBottomSheetContentMode.Form else OpenCodeBottomSheetContentMode.ActionList,
        alwaysFixedHeight = alwaysFixedHeight,
        pinnedContent = {
            if (alwaysFixedHeight) {
                OpenCodeBottomSheetEntry(
                    title = "Go up",
                    startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ArrowUpward, null),
                    onClick = {},
                )
                OpenCodeBottomSheetEntry(
                    title = "/Users/demo/Projects",
                    startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.FolderCheck, null),
                    onClick = {},
                )
            }
        },
    ) {
        if (formMode) {
            OpenCodeTextInput(
                value = formValue,
                onValueChange = { formValue = it },
                hint = "Server URL",
                kind = OpenCodeTextInputKind.Url,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            OpenCodeTextInput(
                value = "gallery password",
                onValueChange = {},
                hint = "Password",
                kind = OpenCodeTextInputKind.Password,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            ActionSheetEntries(
                enabled = enabled,
                startDecorator = startDecorator,
                endDecorator = endDecorator,
                itemCount = itemCount,
                onDismiss = { visible = false },
            )
        }
    }
}

@Composable
private fun ActionSheetEntries(
    enabled: Boolean,
    startDecorator: BottomSheetEntryDecorator,
    endDecorator: BottomSheetEntryDecorator,
    itemCount: Int,
    onDismiss: () -> Unit,
) {
        OpenCodeBottomSheetEntry(
            title = "Open current project",
            enabled = enabled,
            startDecorator = startDecorator,
            endDecorator = endDecorator,
            onClick = onDismiss,
        )
        OpenCodeBottomSheetEntry(
            title = "Create new session",
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Add, "Create new session"),
            onClick = onDismiss,
        )
        if (itemCount >= 4) {
            OpenCodeBottomSheetEntry(
                title = "Rename session",
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Edit, "Rename session"),
                onClick = onDismiss,
            )
            OpenCodeBottomSheetEntry(
                title = "Fork session",
                endDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ChevronRight, null),
                onClick = onDismiss,
            )
        }
        if (itemCount >= 6) {
            OpenCodeBottomSheetEntry(
                title = "Review diff",
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Description, "Review diff"),
                onClick = onDismiss,
            )
            OpenCodeBottomSheetEntry(
                title = "Abort active work",
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.StopCircle, "Abort active work"),
                onClick = onDismiss,
            )
        }
    }

@Composable
private fun DecoratorControls(
    title: String,
    selected: GalleryBottomSheetDecoratorKind,
    onSelect: (GalleryBottomSheetDecoratorKind) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GalleryBottomSheetDecoratorKind.entries.forEach { kind ->
                OpenCodeChoiceBadge(
                    label = kind.name,
                    selected = selected == kind,
                    onClick = { onSelect(kind) },
                )
            }
        }
    }
}

@Composable
private fun ItemCountControls(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Items", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 4, 6).forEach { count ->
                OpenCodeChoiceBadge(
                    label = count.toString(),
                    selected = selected == count,
                    onClick = { onSelect(count) },
                )
            }
        }
    }
}

private fun galleryDecoratorKind(name: String): GalleryBottomSheetDecoratorKind =
    try {
        GalleryBottomSheetDecoratorKind.valueOf(name)
    } catch (_: IllegalArgumentException) {
        GalleryBottomSheetDecoratorKind.None
    }
