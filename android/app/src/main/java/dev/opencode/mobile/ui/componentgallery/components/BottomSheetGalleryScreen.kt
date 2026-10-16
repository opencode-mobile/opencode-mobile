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
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeaderAction
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetKeyboard
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.components.StartDecorator
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
    var heightName by rememberSaveable { mutableStateOf(OpenCodeBottomSheetHeight.FitContent.name) }
    var keyboardName by rememberSaveable { mutableStateOf(OpenCodeBottomSheetKeyboard.Dismiss.name) }
    var showsPinnedTop by rememberSaveable { mutableStateOf(false) }
    var showsPinnedBottom by rememberSaveable { mutableStateOf(false) }
    var formValue by rememberSaveable { mutableStateOf("http://10.0.2.2:4096/") }
    var keyboardExpandVisible by rememberSaveable { mutableStateOf(false) }
    var keyboardExpandFilter by rememberSaveable { mutableStateOf("") }

    val height = galleryEnumValue(heightName, OpenCodeBottomSheetHeight.FitContent)
    val keyboard = galleryEnumValue(keyboardName, OpenCodeBottomSheetKeyboard.Dismiss)
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
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenCodeChoiceBadge(label = "Pinned top", selected = showsPinnedTop, onClick = { showsPinnedTop = !showsPinnedTop })
                OpenCodeChoiceBadge(
                    label = "Pinned bottom",
                    selected = showsPinnedBottom,
                    onClick = { showsPinnedBottom = !showsPinnedBottom },
                )
            }
        }

        ChoiceControls(
            title = "Height",
            options = OpenCodeBottomSheetHeight.entries,
            selected = height,
            onSelect = { heightName = it.name },
        )
        ChoiceControls(
            title = "Keyboard",
            options = OpenCodeBottomSheetKeyboard.entries,
            selected = keyboard,
            onSelect = { keyboardName = it.name },
        )

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

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Keyboard expand",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Half-height sheet that grows to the top inset while the keyboard is visible.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OpenCodeButton(
                variant = OpenCodeButtonVariant.FullWidthText("Show keyboard expand sheet"),
                onClick = { keyboardExpandVisible = true },
            )
        }
    }

    KeyboardExpandBottomSheet(
        visible = keyboardExpandVisible,
        filter = keyboardExpandFilter,
        onFilterChange = { keyboardExpandFilter = it },
        onDismiss = { keyboardExpandVisible = false },
    )

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
        height = height,
        keyboard = keyboard,
        pinnedTop = {
            if (showsPinnedTop) {
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
        pinnedBottom =
            if (showsPinnedBottom) {
                {
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText("Add project"),
                        onClick = { visible = false },
                    )
                }
            } else {
                null
            },
    ) {
        // Keyboard policies other than Dismiss need a field to type into.
        if (keyboard != OpenCodeBottomSheetKeyboard.Dismiss) {
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
        }
        ActionSheetEntries(
            enabled = enabled,
            startDecorator = startDecorator,
            endDecorator = endDecorator,
            itemCount = itemCount,
            onDismiss = { visible = false },
        )
    }
}

@Composable
private fun KeyboardExpandBottomSheet(
    visible: Boolean,
    filter: String,
    onFilterChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val folders = (1..20).map { index -> "Folder ${index.toString().padStart(2, '0')}" }
    OpenCodeBottomSheet(
        visible = visible,
        title = "Keyboard expand",
        onDismissRequest = onDismiss,
        height = OpenCodeBottomSheetHeight.Half,
        keyboard = OpenCodeBottomSheetKeyboard.Expand,
        pinnedTop = {
            OpenCodeTextInput(
                value = filter,
                onValueChange = onFilterChange,
                hint = "Folder path",
                kind = OpenCodeTextInputKind.Filter,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
        },
    ) {
        folders.forEach { folder ->
            OpenCodeListItem(
                title = folder,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, contentDescription = null),
                onClick = {},
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
private fun <T : Enum<T>> ChoiceControls(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                OpenCodeChoiceBadge(
                    label = option.name,
                    selected = selected == option,
                    onClick = { onSelect(option) },
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

private inline fun <reified T : Enum<T>> galleryEnumValue(
    name: String,
    fallback: T,
): T = enumValues<T>().firstOrNull { it.name == name } ?: fallback
