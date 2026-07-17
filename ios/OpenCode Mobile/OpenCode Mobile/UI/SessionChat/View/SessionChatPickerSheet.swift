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
                contentMode: modelPickerContentMode,
                pinnedContent: { modelPickerPinnedContent },
                content: { pickerSheetEntries }
            )
    }

    private var showsModelFilter: Bool {
        activePicker == .model &&
            activeModelProvider.map { $0.models.count > SessionChatPickers.modelFilterThreshold } == true
    }

    private var modelPickerContentMode: OpenCodeBottomSheetContentMode {
        showsModelFilter ? .form : .actionList
    }

    private var pickerPresentedBinding: Binding<Bool> {
        Binding(
            get: { activePicker != nil },
            set: { presented in
                if !presented {
                    activePicker = nil
                    activeModelProviderID = nil
                    modelFilterText = ""
                }
            },
        )
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
    private var modelPickerPinnedContent: some View {
        if showsModelFilter {
            ModelPickerFilterField(text: $modelFilterText)
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
            ForEach(SessionChatPickers.modes, id: \.self) { mode in
                OpenCodeBottomSheetEntry(
                    title: mode,
                    endDecorator: mode == composer.selectedMode
                        ? .icon(.check, accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        onSelectMode(mode)
                        activePicker = nil
                    },
                )
            }
        case .variant:
            ForEach(composer.variantOptions, id: \.self) { level in
                OpenCodeBottomSheetEntry(
                    title: level,
                    endDecorator: level == composer.selectedVariantLabel
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

    var body: some View {
        TextField("Filter models...", text: $text)
            .font(OpenCodeTypography.body)
            .foregroundStyle(OpenCodeColors.textStrong)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 12))
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
