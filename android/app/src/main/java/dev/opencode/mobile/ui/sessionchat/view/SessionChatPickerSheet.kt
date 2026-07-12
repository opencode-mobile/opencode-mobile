package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.background
import dev.opencode.mobile.ui.sessionchat.SessionChatViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.ModelSelectionProvider
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetContentMode
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeaderAction
import dev.opencode.mobile.ui.icons.OpenCodeIcons

internal enum class ComposerPickerKind(
    val sheetTitle: String,
) {
    Model("Model"),
    Mode("Agent mode"),
    Variant("Variant"),
}

@Composable
internal fun SessionChatPickerSheet(
    activePicker: ComposerPickerKind?,
    activeModelProviderId: String?,
    modelFilterText: String,
    modelOptions: List<ModelSelectionOption>,
    selectedModel: ModelSelectionOption?,
    selectedMode: String,
    selectedVariantLabel: String,
    variantOptions: List<String>,
    onDismiss: () -> Unit,
    onActiveModelProviderIdChange: (String?) -> Unit,
    onModelFilterTextChange: (String) -> Unit,
    onSelectModel: (ModelSelectionOption) -> Unit,
    onSelectMode: (String) -> Unit,
    onSelectVariant: (String) -> Unit,
) {
    val modelCatalog =
        remember(modelOptions) {
            ModelSelectionCatalog(options = modelOptions, serverDefault = null)
        }
    val activeModelProvider = modelCatalog.providers.firstOrNull { it.id == activeModelProviderId }
    val modelSheetTitle =
        if (activePicker == ComposerPickerKind.Model) {
            activeModelProvider?.label ?: ComposerPickerKind.Model.sheetTitle
        } else {
            activePicker?.sheetTitle.orEmpty()
        }
    val showsModelFilter =
        activePicker == ComposerPickerKind.Model &&
            activeModelProvider != null &&
            activeModelProvider.models.size > SessionChatViewModel.ModelFilterThreshold
    val modelHeaderLeadingAction =
        if (activePicker == ComposerPickerKind.Model && activeModelProvider != null) {
            OpenCodeBottomSheetHeaderAction(
                imageVector = OpenCodeIcons.ArrowBack,
                contentDescription = "Back to providers",
                onClick = {
                    onActiveModelProviderIdChange(null)
                    onModelFilterTextChange("")
                },
            )
        } else {
            null
        }

    OpenCodeBottomSheet(
        visible = activePicker != null,
        title = modelSheetTitle,
        onDismissRequest = onDismiss,
        headerLeadingAction = modelHeaderLeadingAction,
        contentMode = if (showsModelFilter) OpenCodeBottomSheetContentMode.Form else OpenCodeBottomSheetContentMode.ActionList,
        pinnedContent = {
            if (showsModelFilter) {
                ModelPickerFilterField(
                    value = modelFilterText,
                    onValueChange = onModelFilterTextChange,
                )
            }
        },
    ) {
        when (activePicker) {
            ComposerPickerKind.Model ->
                ModelPickerSheetContent(
                    providers = modelCatalog.providers,
                    activeProvider = activeModelProvider,
                    selectedModel = selectedModel,
                    filterText = modelFilterText,
                    onSelectProvider = { provider ->
                        onActiveModelProviderIdChange(provider.id)
                        onModelFilterTextChange("")
                    },
                    onSelectModel = onSelectModel,
                )
            ComposerPickerKind.Mode ->
                SessionChatViewModel.AvailableModes.forEach { mode ->
                    OpenCodeBottomSheetEntry(
                        title = mode,
                        endDecorator =
                            if (mode == selectedMode) {
                                BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check, "Selected")
                            } else {
                                BottomSheetEntryDecorator.None
                            },
                        onClick = { onSelectMode(mode) },
                    )
                }
            ComposerPickerKind.Variant ->
                variantOptions.forEach { variant ->
                    OpenCodeBottomSheetEntry(
                        title = variant,
                        endDecorator =
                            if (variant == selectedVariantLabel) {
                                BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check, "Selected")
                            } else {
                                BottomSheetEntryDecorator.None
                            },
                        onClick = { onSelectVariant(variant) },
                    )
                }
            null -> Unit
        }
    }
}

@Composable
private fun ModelPickerSheetContent(
    providers: List<ModelSelectionProvider>,
    activeProvider: ModelSelectionProvider?,
    selectedModel: ModelSelectionOption?,
    filterText: String,
    onSelectProvider: (ModelSelectionProvider) -> Unit,
    onSelectModel: (ModelSelectionOption) -> Unit,
) {
    if (activeProvider == null) {
        providers.forEach { provider ->
            OpenCodeBottomSheetEntry(
                title = provider.label,
                endDecorator =
                    if (provider.id == selectedModel?.providerId) {
                        BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check, "Selected")
                    } else {
                        BottomSheetEntryDecorator.None
                    },
                onClick = { onSelectProvider(provider) },
            )
        }
    } else {
        val filteredModels = activeProvider.models.filter { it.matchesFilter(filterText) }
        if (filteredModels.isEmpty()) {
            OpenCodeBottomSheetEntry(
                title = "No models match your filter",
                enabled = false,
                onClick = {},
            )
        } else {
            filteredModels.forEach { model ->
                OpenCodeBottomSheetEntry(
                    title = model.modelLabel,
                    endDecorator =
                        if (model == selectedModel) {
                            BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check, "Selected")
                        } else {
                            BottomSheetEntryDecorator.None
                        },
                    onClick = { onSelectModel(model) },
                )
            }
        }
    }
}

@Composable
private fun ModelPickerFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { innerTextField ->
            Box {
                if (value.isBlank()) {
                    Text(
                        text = "Filter models...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )
}
