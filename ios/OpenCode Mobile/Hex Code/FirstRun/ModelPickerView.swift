import OpenCodeKit
import SwiftUI

/// The model page: search, models grouped by provider, reasoning level, and the test that must
/// pass before the finish button (Start in first run, Save in Settings). Everything sits on one
/// 18-point edge.
struct ModelPickerView: View {
    let page: ModelPageModel
    var title = LocalizedStringResource("model.title", defaultValue: "Pick a model")
    let serverLabel: String
    var finishTitle = LocalizedStringResource("model.start", defaultValue: "Start")
    var finishing = false
    var finishError: String?
    let onBack: () -> Void
    let onFinish: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HexTopBar(title, subtitle: subtitle, onBack: onBack)
            switch page.phase {
            case .loading:
                HexStatusLine(.waiting, LocalizedStringResource("model.loading", defaultValue: "Loading models"))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed:
                PickerNotice(
                    text: String(localized: "model.failed", defaultValue: "Couldn’t load the models from OpenCode."),
                    onRetry: { Task { await page.load() } }
                )
                Spacer()
            case let .loaded(picker):
                loaded(picker)
            }
        }
        .hexScreenBackground()
    }

    private var subtitle: String {
        guard let picker = page.picker else { return serverLabel }
        let count = picker.catalog.options.count
        return String(localized: "model.subtitle", defaultValue: "\(serverLabel) · \(count) models from OpenCode")
    }

    private func loaded(_ picker: ModelPickerState) -> some View {
        VStack(spacing: 0) {
            HexSearchField(
                LocalizedStringResource("model.search", defaultValue: "Search models"),
                text: Binding(get: { picker.query }, set: { page.setQuery($0) })
            )
            .padding(.horizontal, HexSpacing.screen)
            .padding(.top, 6)
            .padding(.bottom, 2)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    let sections = picker.sections
                    if sections.isEmpty {
                        PickerNotice(text: String(localized: "model.no_match", defaultValue: "No models match “\(picker.query)”"))
                    }
                    ForEach(sections) { section in
                        if let title = section.title {
                            HexSectionLabel(verbatim: title)
                        }
                        ForEach(section.rows) { row in
                            HexPickerRow(
                                variant: .model,
                                title: row.option.modelLabel,
                                selected: row.isSelected,
                                badge: row.isServerDefault ? Text(LocalizedStringResource("model.default_tag", defaultValue: "default")) : nil,
                                monoBadge: true,
                                subtitle: row.providerCaption,
                                trailing: row.isSelected ? .check : .none,
                                action: { page.select(row.option) }
                            )
                        }
                    }
                }
            }
            .scrollDismissesKeyboard(.immediately)
            bar(picker)
        }
    }

    private func bar(_ picker: ModelPickerState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 0) {
                Text(LocalizedStringResource("model.reasoning", defaultValue: "Reasoning"))
                    .foregroundStyle(HexColor.text2)
                if let option = picker.selectedOption {
                    Text(verbatim: " · \(option.modelLabel)")
                        .foregroundStyle(HexColor.text3)
                }
            }
            .hexFont(.caption)
            .lineLimit(1)
            levels(picker)
            testStatus(picker)
                .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
            if picker.canStart {
                HexPrimaryButton(finishTitle, loading: finishing, action: onFinish)
            } else {
                HexPrimaryButton(
                    testButtonTitle(picker),
                    enabled: !picker.isTesting && picker.selectedOption != nil,
                    action: { page.test() }
                )
            }
            if let error = finishError {
                HexStatusLine(.warning, verbatim: error)
            }
        }
        .padding(.horizontal, HexSpacing.screen)
        .padding(.top, 14)
        .padding(.bottom, 4)
        .background(HexColor.background)
        .overlay(alignment: .top) { HexColor.line.frame(height: 1) }
    }

    @ViewBuilder
    private func levels(_ picker: ModelPickerState) -> some View {
        let levels = picker.reasoningLevels
        if levels.isEmpty {
            HexSegmentedPlaceholder(LocalizedStringResource("model.no_levels", defaultValue: "This model has no reasoning levels"))
        } else {
            HexSegmented(
                levels,
                selection: Binding(get: { picker.selectedLevel }, set: { page.selectLevel($0) }),
                label: { TitleCaseIdentifier.displayName(for: $0) }
            )
        }
    }

    @ViewBuilder
    private func testStatus(_ picker: ModelPickerState) -> some View {
        let name = picker.selectedOption?.modelLabel ?? ""
        switch picker.currentTest {
        case .notRun:
            HexStatusLine(.hint, LocalizedStringResource("model.test.hint", defaultValue: "A one-line test prompt checks it works"))
        case .testing:
            HexStatusLine(.waiting, LocalizedStringResource("model.test.running", defaultValue: "Asking \(name) to reply…"))
        case let .passed(seconds):
            HexStatusLine(.ok, LocalizedStringResource(
                "model.test.passed",
                defaultValue: "Works · replied in \(seconds.formatted(.number.precision(.fractionLength(1)))) s"
            ))
        case let .failed(message):
            HexStatusLine(.warning, verbatim: message)
        }
    }

    private func testButtonTitle(_ picker: ModelPickerState) -> LocalizedStringResource {
        switch picker.currentTest {
        case .testing: LocalizedStringResource("model.test.button.testing", defaultValue: "Testing…")
        case .failed: LocalizedStringResource("model.test.button.again", defaultValue: "Test again")
        default: LocalizedStringResource("model.test.button", defaultValue: "Test model")
        }
    }
}
