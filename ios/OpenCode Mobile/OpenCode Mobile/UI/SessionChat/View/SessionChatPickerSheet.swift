import SwiftUI

enum ComposerPickerKind {
    case model
    case mode
    case variant

    var sheetTitle: String {
        switch self {
        case .model:
            "Model"
        case .mode:
            "Agent mode"
        case .variant:
            "Variant"
        }
    }
}

struct SessionChatPickerSheetModifier: ViewModifier {
    @Binding var activePicker: ComposerPickerKind?
    @Binding var activeModelProviderID: String?
    @Binding var modelFilterText: String
    let composer: SessionChatComposerState
    let onSelectModel: (ModelSelectionOption) -> Void
    let onSelectMode: (String) -> Void
    let onSelectVariant: (String) -> Void

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: pickerPresentedBinding,
                title: modelPickerSheetTitle,
                leadingAction: modelPickerHeaderLeadingAction,
                scrollTargetID: modelPickerScrollTargetID,
                height: showsModelFilter ? .half : .fitContent,
                keyboard: showsModelFilter ? .keep : .dismiss,
                pinnedTop: { modelPickerPinnedTop },
                content: { pickerSheetEntries }
            )
    }

    private var showsModelFilter: Bool {
        activePicker == .model &&
            activeModelProvider.map { $0.models.count > SessionChatPickers.modelFilterThreshold } == true
    }

    private var pickerPresentedBinding: Binding<Bool> {
        Binding(
            get: { activePicker != nil },
            set: { presented in
                if !presented {
                    if let picker = activePicker {
                        ProductAnalytics.logUserAction(
                            screenId: Self.screenId(for: picker),
                            actionId: Self.dismissActionId(for: picker)
                        )
                    }
                    activePicker = nil
                    activeModelProviderID = nil
                    modelFilterText = ""
                }
            },
        )
    }

    private static func screenId(for picker: ComposerPickerKind) -> String {
        switch picker {
        case .model: ProductAnalytics.Screen.sessionModelPicker
        case .mode: ProductAnalytics.Screen.sessionModePicker
        case .variant: ProductAnalytics.Screen.sessionVariantPicker
        }
    }

    private static func dismissActionId(for picker: ComposerPickerKind) -> String {
        switch picker {
        case .model: ProductAnalytics.Action.modelPickerDismiss
        case .mode: ProductAnalytics.Action.modePickerDismiss
        case .variant: ProductAnalytics.Action.variantPickerDismiss
        }
    }

    private var modelSelectionCatalog: ModelSelectionCatalog {
        ModelSelectionCatalog(options: composer.modelOptions, serverDefault: nil)
    }

    private var activeModelProvider: ModelSelectionProvider? {
        guard let activeModelProviderID else { return nil }
        return modelSelectionCatalog.providers.first { $0.id == activeModelProviderID }
    }

    private var modelPickerSheetTitle: String {
        guard activePicker == .model else { return activePicker?.sheetTitle ?? "" }
        return activeModelProvider?.label ?? ComposerPickerKind.model.sheetTitle
    }

    private var modelPickerHeaderLeadingAction: OpenCodeBottomSheetHeaderAction? {
        guard activePicker == .model, activeModelProvider != nil else { return nil }
        return OpenCodeBottomSheetHeaderAction(
            icon: .arrowBack,
            accessibilityLabel: "Back to providers",
            action: {
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.sessionModelPicker,
                    actionId: ProductAnalytics.Action.modelPickerBackToProviders
                )
                activeModelProviderID = nil
                modelFilterText = ""
            }
        )
    }

    private func filteredModels(in provider: ModelSelectionProvider) -> [ModelSelectionOption] {
        provider.models.filter { $0.matchesFilter(modelFilterText) }
    }

    private var modelPickerScrollTargetID: String? {
        guard activePicker == .model else { return nil }
        guard let provider = activeModelProvider else { return nil }
        guard let selected = composer.selectedModel?.option else { return nil }
        let filtered = filteredModels(in: provider)
        guard filtered.contains(where: { $0.id == selected.id }) else { return nil }
        return selected.id
    }

    @ViewBuilder
    private var modelPickerPinnedTop: some View {
        if showsModelFilter {
            // Filter text is never sent; only the fact that filtering happened.
            ModelPickerFilterField(
                text: $modelFilterText,
                onFirstEdit: {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.sessionModelPicker,
                        actionId: ProductAnalytics.Action.modelPickerFilter
                    )
                }
            )
        }
    }

    @ViewBuilder
    private func modelPickerEntries() -> some View {
        if let provider = activeModelProvider {
            let filtered = filteredModels(in: provider)
            if filtered.isEmpty {
                OpenCodeBottomSheetEntry(
                    title: "No models match your filter",
                    enabled: false,
                    action: {}
                )
            } else {
                ForEach(filtered) { model in
                    OpenCodeBottomSheetEntry(
                        title: model.modelLabel,
                        endDecorator: model == composer.selectedModel?.option
                            ? .icon(.check, accessibilityLabel: "Selected")
                            : .none,
                        action: {
                            onSelectModel(model)
                            activePicker = nil
                            activeModelProviderID = nil
                            modelFilterText = ""
                        },
                    )
                    .id(model.id)
                }
            }
        } else {
            ForEach(modelSelectionCatalog.providers) { provider in
                OpenCodeBottomSheetEntry(
                    title: provider.label,
                    endDecorator: provider.id == composer.selectedModel?.option.providerID
                        ? .icon(.check, accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        ProductAnalytics.logUserAction(
                            screenId: ProductAnalytics.Screen.sessionModelPicker,
                            actionId: ProductAnalytics.Action.modelPickerSelectProvider
                        )
                        activeModelProviderID = provider.id
                        modelFilterText = ""
                    },
                )
            }
        }
    }

    @ViewBuilder
    private var pickerSheetEntries: some View {
        switch activePicker {
        case .model:
            modelPickerEntries()
        case .mode:
            ForEach(composer.selectableAgents) { agent in
                OpenCodeBottomSheetEntry(
                    title: agent.displayName,
                    endDecorator: agent.id.compare(composer.selectedMode, options: .caseInsensitive) == .orderedSame
                        ? .icon(.check, accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        onSelectMode(agent.id)
                        activePicker = nil
                    },
                )
            }
        case .variant:
            ForEach(composer.variantOptions, id: \.self) { level in
                OpenCodeBottomSheetEntry(
                    title: TitleCaseIdentifier.displayName(for: level),
                    endDecorator: level.compare(
                        composer.selectedVariant ?? ModelSelection.defaultVariantLabel,
                        options: .caseInsensitive
                    ) == .orderedSame
                        ? .icon(.check, accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        onSelectVariant(level)
                        activePicker = nil
                    },
                )
            }
        case .none:
            EmptyView()
        }
    }
}

private struct ModelPickerFilterField: View {
    @Binding var text: String
    let onFirstEdit: () -> Void
    @State private var loggedEdit = false

    var body: some View {
        OpenCodeTextInput(
            text: $text,
            hint: "Filter models...",
            kind: .filter
        )
        .onChange(of: text) { _, _ in
            guard !loggedEdit else { return }
            loggedEdit = true
            onFirstEdit()
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 8)
    }
}

extension View {
    func sessionChatPickerSheet(
        activePicker: Binding<ComposerPickerKind?>,
        activeModelProviderID: Binding<String?>,
        modelFilterText: Binding<String>,
        composer: SessionChatComposerState,
        onSelectModel: @escaping (ModelSelectionOption) -> Void,
        onSelectMode: @escaping (String) -> Void,
        onSelectVariant: @escaping (String) -> Void
    ) -> some View {
        modifier(
            SessionChatPickerSheetModifier(
                activePicker: activePicker,
                activeModelProviderID: activeModelProviderID,
                modelFilterText: modelFilterText,
                composer: composer,
                onSelectModel: onSelectModel,
                onSelectMode: onSelectMode,
                onSelectVariant: onSelectVariant
            )
        )
    }
}
