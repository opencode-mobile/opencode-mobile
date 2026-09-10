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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.domain.agent.SelectableAgent
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.ModelSelectionProvider
import dev.opencode.mobile.domain.text.TitleCaseIdentifier
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
    ;

    val productScreenId: String
        get() =
            when (this) {
                Model -> ProductAnalytics.Screen.SESSION_MODEL_PICKER
                Mode -> ProductAnalytics.Screen.SESSION_MODE_PICKER
                Variant -> ProductAnalytics.Screen.SESSION_VARIANT_PICKER
            }

    val productDismissActionId: String
        get() =
            when (this) {
                Model -> ProductAnalytics.Action.MODEL_PICKER_DISMISS
                Mode -> ProductAnalytics.Action.MODE_PICKER_DISMISS
                Variant -> ProductAnalytics.Action.VARIANT_PICKER_DISMISS
            }
}

@Composable
internal fun SessionChatPickerSheet(
    activePicker: ComposerPickerKind?,
    activeModelProviderId: String?,
    modelFilterText: String,
    modelOptions: List<ModelSelectionOption>,
    selectedModel: ModelSelectionOption?,
    selectedMode: String,
    selectableAgents: List<SelectableAgent>,
    selectedVariant: String?,
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
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SESSION_MODEL_PICKER,
                        actionId = ProductAnalytics.Action.MODEL_PICKER_BACK_TO_PROVIDERS,
                    )
                    onActiveModelProviderIdChange(null)
                    onModelFilterTextChange("")
                },
            )
        } else {
            null
        }
    // Filtering fires per keystroke and the query is user text: report engagement
    // once per provider list, never the query itself.
    var filterEditReported by remember(activePicker, activeModelProviderId) { mutableStateOf(false) }

    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_MODEL_PICKER,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = activePicker == ComposerPickerKind.Model,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_MODE_PICKER,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = activePicker == ComposerPickerKind.Mode,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_VARIANT_PICKER,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = activePicker == ComposerPickerKind.Variant,
    )

    OpenCodeBottomSheet(
        visible = activePicker != null,
        title = modelSheetTitle,
        onDismissRequest = {
            activePicker?.let { picker ->
                ProductAnalytics.logUserAction(
                    screenId = picker.productScreenId,
                    actionId = picker.productDismissActionId,
                )
            }
            onDismiss()
        },
        headerLeadingAction = modelHeaderLeadingAction,
        contentMode = if (showsModelFilter) OpenCodeBottomSheetContentMode.Form else OpenCodeBottomSheetContentMode.ActionList,
        pinnedContent = {
            if (showsModelFilter) {
                ModelPickerFilterField(
                    value = modelFilterText,
                    onValueChange = { text ->
                        if (!filterEditReported) {
                            filterEditReported = true
                            ProductAnalytics.logUserAction(
                                screenId = ProductAnalytics.Screen.SESSION_MODEL_PICKER,
                                actionId = ProductAnalytics.Action.MODEL_PICKER_FILTER,
                            )
                        }
                        onModelFilterTextChange(text)
                    },
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
                        ProductAnalytics.logUserAction(
                            screenId = ProductAnalytics.Screen.SESSION_MODEL_PICKER,
                            actionId = ProductAnalytics.Action.MODEL_PICKER_SELECT_PROVIDER,
                        )
                        onActiveModelProviderIdChange(provider.id)
                        onModelFilterTextChange("")
                    },
                    onSelectModel = onSelectModel,
                )
            ComposerPickerKind.Mode ->
                selectableAgents.forEach { agent ->
                    OpenCodeBottomSheetEntry(
                        title = agent.displayName,
                        endDecorator =
                            if (agent.id.equals(selectedMode, ignoreCase = true)) {
                                BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check, "Selected")
                            } else {
                                BottomSheetEntryDecorator.None
                            },
                        onClick = { onSelectMode(agent.id) },
                    )
                }
            ComposerPickerKind.Variant ->
                variantOptions.forEach { variant ->
                    OpenCodeBottomSheetEntry(
                        title = TitleCaseIdentifier.displayName(variant),
                        endDecorator =
                            if (variant.equals(
                                    selectedVariant ?: ModelSelection.DefaultVariantLabel,
                                    ignoreCase = true,
                                )
                            ) {
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
