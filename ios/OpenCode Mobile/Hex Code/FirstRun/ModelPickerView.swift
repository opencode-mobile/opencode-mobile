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
            HexTopBar(title: title, subtitle: subtitle, onBack: onBack)
            switch page.phase {
            case .loading:
                OpenCodeStatusStack(spinner: OpenCodeText(LocalizedStringResource("model.loading", defaultValue: "Loading models")))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed:
                failed
            case let .loaded(picker):
                loaded(picker)
            }
        }
        .background(OpenCodeColors.backgroundBase)
    }

    private var subtitle: String {
        guard let picker = page.picker else { return serverLabel }
        let count = picker.catalog.options.count
        return String(localized: "model.subtitle", defaultValue: "\(serverLabel) · \(count) models from OpenCode")
    }

    private var failed: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(LocalizedStringResource("model.failed", defaultValue: "Couldn’t load the models from OpenCode."))
                .font(.system(size: 14.5))
                .foregroundStyle(OpenCodeColors.textWeak)
            Button {
                Task { await page.load() }
            } label: {
                Text(LocalizedStringResource("common.retry", defaultValue: "Retry"))
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(OpenCodeColors.textStrong)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(HexLayout.edge)
    }

    private func loaded(_ picker: ModelPickerState) -> some View {
        VStack(spacing: 0) {
            search(picker)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    let sections = picker.sections
                    if sections.isEmpty {
                        Text(LocalizedStringResource("model.no_match", defaultValue: "No models match “\(picker.query)”"))
                            .font(.system(size: 14.5))
                            .foregroundStyle(OpenCodeColors.textWeak)
                            .padding(.horizontal, HexLayout.edge)
                            .padding(.vertical, 14)
                    }
                    ForEach(sections) { section in
                        if let title = section.title {
                            Text(verbatim: title)
                                .textCase(.uppercase)
                                .font(.system(size: 11.5, weight: .semibold))
                                .tracking(0.7)
                                .foregroundStyle(OpenCodeColors.textWeaker)
                                .padding(.horizontal, HexLayout.edge)
                                .padding(.top, 16)
                                .padding(.bottom, 4)
                                .accessibilityAddTraits(.isHeader)
                        }
                        ForEach(section.rows) { row in
                            HexRow(
                                title: row.option.modelLabel,
                                titleWeight: row.isSelected ? .semibold : .regular,
                                tag: row.isServerDefault ? OpenCodeText(LocalizedStringResource("model.default_tag", defaultValue: "default")) : nil,
                                subtitle: row.providerCaption,
                                trailing: row.isSelected ? .check : .none,
                                minHeight: 46,
                                action: { page.select(row.option) }
                            )
                            .accessibilityAddTraits(row.isSelected ? .isSelected : [])
                        }
                    }
                }
            }
            .scrollDismissesKeyboard(.immediately)
            bar(picker)
        }
    }

    private func search(_ picker: ModelPickerState) -> some View {
        HStack(spacing: 8) {
            OpenCodeIconImage(icon: .search, size: 15)
                .foregroundStyle(OpenCodeColors.textWeaker)
            TextField(
                String(localized: "model.search", defaultValue: "Search models"),
                text: Binding(get: { picker.query }, set: { page.setQuery($0) })
            )
            .font(.system(size: 15.5))
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .submitLabel(.search)
        }
        .padding(.horizontal, 12)
        .frame(height: 40)
        .background(OpenCodeColors.surfaceBaseHover, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .padding(.horizontal, HexLayout.edge)
        .padding(.top, 6)
        .padding(.bottom, 2)
    }

    private func bar(_ picker: ModelPickerState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 0) {
                Text(LocalizedStringResource("model.reasoning", defaultValue: "Reasoning"))
                    .foregroundStyle(OpenCodeColors.textWeak)
                if let option = picker.selectedOption {
                    Text(verbatim: " · \(option.modelLabel)")
                        .foregroundStyle(OpenCodeColors.textWeaker)
                }
            }
            .font(.system(size: 12.5))
            .lineLimit(1)
            levels(picker)
            testStatus(picker)
                .frame(minHeight: 40, alignment: .leading)
            if picker.canStart {
                HexPrimaryButton(title: finishTitle, busy: finishing, action: onFinish)
            } else {
                HexPrimaryButton(
                    title: testButtonTitle(picker),
                    enabled: !picker.isTesting && picker.selectedOption != nil,
                    action: { page.test() }
                )
            }
            if let error = finishError {
                Text(verbatim: error)
                    .font(.system(size: 12.5))
                    .foregroundStyle(OpenCodeColors.error)
            }
        }
        .padding(.horizontal, HexLayout.edge)
        .padding(.top, 14)
        .padding(.bottom, 4)
        .background(OpenCodeColors.backgroundBase)
        .overlay(alignment: .top) { Divider().overlay(OpenCodeColors.borderWeakBase) }
    }

    @ViewBuilder
    private func levels(_ picker: ModelPickerState) -> some View {
        let levels = picker.reasoningLevels
        if levels.isEmpty {
            Text(LocalizedStringResource("model.no_levels", defaultValue: "This model has no reasoning levels"))
                .font(.system(size: 13))
                .foregroundStyle(OpenCodeColors.textWeaker)
                .padding(.horizontal, 12)
                .frame(maxWidth: .infinity, minHeight: 36, alignment: .leading)
                .background(OpenCodeColors.surfaceBaseHover, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        } else {
            HStack(spacing: 0) {
                ForEach(levels, id: \.self) { level in
                    let selected = picker.selectedLevel == level
                    Button {
                        page.selectLevel(level)
                    } label: {
                        Text(verbatim: TitleCaseIdentifier.displayName(for: level))
                            .font(.system(size: 13.5, weight: .medium))
                            .foregroundStyle(selected ? OpenCodeColors.textStrong : OpenCodeColors.textWeak)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(
                                selected ? OpenCodeColors.surfaceRaisedBase : .clear,
                                in: RoundedRectangle(cornerRadius: 7, style: .continuous)
                            )
                            .shadow(color: selected ? .black.opacity(0.15) : .clear, radius: 1, y: 1)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            .padding(3)
            .frame(height: 36)
            .background(OpenCodeColors.surfaceBaseHover, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
    }

    @ViewBuilder
    private func testStatus(_ picker: ModelPickerState) -> some View {
        let name = picker.selectedOption?.modelLabel ?? ""
        switch picker.currentTest {
        case .notRun:
            Text(LocalizedStringResource("model.test.hint", defaultValue: "A one-line test prompt checks it works"))
                .font(.system(size: 13))
                .foregroundStyle(OpenCodeColors.textWeaker)
        case .testing:
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text(LocalizedStringResource("model.test.running", defaultValue: "Asking \(name) to reply…"))
            }
            .font(.system(size: 13))
            .foregroundStyle(OpenCodeColors.textWeak)
        case let .passed(seconds):
            HStack(spacing: 6) {
                OpenCodeIconImage(icon: .check, size: 14).foregroundStyle(OpenCodeColors.success)
                Text(LocalizedStringResource(
                    "model.test.passed",
                    defaultValue: "Works · replied in \(seconds.formatted(.number.precision(.fractionLength(1)))) s"
                ))
            }
            .font(.system(size: 13))
            .foregroundStyle(OpenCodeColors.textStrong)
        case let .failed(message):
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                OpenCodeIconImage(icon: .close, size: 14).foregroundStyle(OpenCodeColors.error)
                Text(verbatim: message)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .font(.system(size: 13))
            .foregroundStyle(OpenCodeColors.error)
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
