package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.domain.agent.SelectableAgent
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.ModelSelectionProvider
import dev.opencode.mobile.domain.text.TitleCaseIdentifier
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeaderAction
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetKeyboard
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.ui.components.openCodeBottomSheetScrollTarget
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.SessionChatViewModel

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
    // Center the selected model when its provider list opens, unless the filter hides it.
    val modelPickerScrollTarget =
        selectedModel?.takeIf { selected ->
            activePicker == ComposerPickerKind.Model &&
                activeModelProvider?.models?.any { it == selected && it.matchesFilter(modelFilterText) } == true
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
        scrollTargetKey = modelPickerScrollTarget,
        // The model filter is a long list typed into: fixed height with the keyboard kept.
        height = if (showsModelFilter) OpenCodeBottomSheetHeight.Half else OpenCodeBottomSheetHeight.FitContent,
        keyboard = if (showsModelFilter) OpenCodeBottomSheetKeyboard.Keep else OpenCodeBottomSheetKeyboard.Dismiss,
        pinnedTop = {
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
                        selected = agent.id.equals(selectedMode, ignoreCase = true),
                        onClick = { onSelectMode(agent.id) },
                    )
                }
            ComposerPickerKind.Variant ->
                variantOptions.forEach { variant ->
                    OpenCodeBottomSheetEntry(
                        title = TitleCaseIdentifier.displayName(variant),
                        selected =
                            variant.equals(
                                selectedVariant ?: ModelSelection.DefaultVariantLabel,
                                ignoreCase = true,
                            ),
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
                selected = provider.id == selectedModel?.providerId,
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
                    selected = model == selectedModel,
                    onClick = { onSelectModel(model) },
                    modifier = Modifier.openCodeBottomSheetScrollTarget(model),
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
    OpenCodeTextInput(
        value = value,
        onValueChange = onValueChange,
        hint = "Filter models...",
        kind = OpenCodeTextInputKind.Filter,
        modifier =
            modifier
                .openCodeBottomSheetContentInset()
                .padding(bottom = 8.dp),
    )
}
