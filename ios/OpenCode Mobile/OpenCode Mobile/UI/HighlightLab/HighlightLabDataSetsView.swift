#if HIGHLIGHT_LAB
import SwiftUI
import UIKit

/// Data set browser: filter by language, size class, and kind; open one highlighted.
struct HighlightLabDataSetsView: View {
    let service: any HighlightLabService

    @Environment(\.dismiss) private var dismiss
    @State private var model: HighlightLabDataSetsModel
    @State private var opened: HighlightLabInput?
    @State private var showsPaste = false

    init(service: any HighlightLabService) {
        self.service = service
        _model = State(initialValue: HighlightLabDataSetsModel(service: service))
    }

    var body: some View {
        let state = model.state
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Data sets",
                subtitle: "\(state.rows.count) shown",
                leading: .back(action: { dismiss() }),
                actions: [.label(title: "Paste text") { showsPaste = true }],
                showDivider: true
            )
            ScrollView {
                LazyVStack(spacing: 8) {
                    filters(state)
                    if state.rows.isEmpty {
                        OpenCodeEmptyState(title: "No data sets", message: "No data set matches these filters.", icon: .description, fillsAvailableSpace: false)
                            .padding(.top, 24)
                    }
                    ForEach(state.rows) { row in
                        OpenCodeListItem(
                            title: row.displayName,
                            subtitle: row.subtitle,
                            style: .card,
                            accessory: .disclosure,
                            action: { opened = .catalog(id: row.id) }
                        )
                        .padding(.horizontal, 16)
                    }
                }
                .padding(.vertical, 12)
            }
            .openCodeReadableColumn()
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .task { await model.start() }
        .navigationDestination(item: $opened) { input in
            HighlightLabViewerView(input: input, service: service)
        }
        .navigationDestination(isPresented: $showsPaste) {
            HighlightLabPasteView(service: service)
        }
    }

    private func filters(_ state: HighlightLabDataSetsUiState) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HighlightLabChipRow(
                title: "Language",
                options: state.languages.map { ($0, $0) },
                selected: state.language,
                allLabel: "All",
                onSelect: model.setLanguage
            )
            HighlightLabChipRow(
                title: "Size",
                options: state.sizeClasses.map { ($0.id, $0.label) },
                selected: state.sizeClassID,
                allLabel: "All",
                onSelect: model.setSizeClass
            )
            HighlightLabChipRow(
                title: "Kind",
                options: state.kinds.map { ($0, $0.label) },
                selected: state.kind,
                allLabel: "All",
                onSelect: model.setKind
            )
        }
        .padding(.bottom, 8)
    }
}

/// One input highlighted with the app's code file component, a timing strip, and an engine picker.
struct HighlightLabViewerView: View {
    /// True when shown as the root of a sheet (from the File Viewer): the leading button closes it.
    var presentedModally = false

    @Environment(\.dismiss) private var dismiss
    @State private var model: HighlightLabViewerModel

    init(input: HighlightLabInput, service: any HighlightLabService, presentedModally: Bool = false) {
        self.presentedModally = presentedModally
        _model = State(initialValue: HighlightLabViewerModel(input: input, service: service))
    }

    var body: some View {
        let state = model.state
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: state.title,
                subtitle: state.subtitle,
                leading: presentedModally ? .close(action: { dismiss() }) : .back(action: { dismiss() }),
                actions: [
                    .label(title: "Run again", enabled: !state.isHighlighting) {
                        Task { await model.runAgain() }
                    },
                ],
                showDivider: true
            )
            HighlightLabChipRow(
                title: "Engine",
                options: state.engines.map { ($0.id, $0.name) },
                selected: state.engineID,
                onSelect: { id in
                    guard let id else { return }
                    Task { await model.selectEngine(id) }
                }
            )
            .padding(.top, 8)
            HighlightLabTimingStrip(timing: state.timing, isRunning: state.isHighlighting)
            Rectangle()
                .fill(OpenCodeColors.borderWeakerBase)
                .frame(height: 1)
            switch state.phase {
            case .highlighting:
                OpenCodeStatusStack(spinner: "Highlighting")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case let .ready(rendered):
                OpenCodeCodeFile(
                    lines: rendered.lines,
                    lineSpans: rendered.lineSpans,
                    maxLineLength: rendered.maxLineLength
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .background(OpenCodeColors.backgroundBase)
        .task { await model.start() }
    }
}

/// Pasted text with a language, opened as a custom data set.
struct HighlightLabPasteView: View {
    let service: any HighlightLabService

    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var language = "typescript"
    @State private var opened: HighlightLabInput?

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Paste text",
                leading: .back(action: { dismiss() }),
                actions: [
                    .label(title: "Clipboard") { text = UIPasteboard.general.string ?? text },
                ],
                showDivider: true
            )
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HighlightLabChipRow(
                        title: "Language",
                        options: service.languages.map { ($0, $0) },
                        selected: language,
                        onSelect: { if let value = $0 { language = value } }
                    )
                    OpenCodeTextInput(
                        text: $text,
                        hint: "Paste or type code",
                        accessibilityLabel: "Text to highlight",
                        minLines: 8,
                        maxLines: 16
                    )
                    .padding(.horizontal, 16)
                    Text("\(HighlightLabFormat.count(text.utf16.count)) characters")
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .padding(.horizontal, 16)
                    OpenCodeButton(
                        variant: .fullWidthText(title: "Highlight"),
                        size: .large,
                        enabled: !text.isEmpty,
                        action: {
                            opened = .custom(
                                HighlightLabCustomText(
                                    id: "paste-\(UUID().uuidString.prefix(8))",
                                    displayName: "Pasted \(language)",
                                    language: language,
                                    source: text
                                )
                            )
                        }
                    )
                    .padding(.horizontal, 16)
                }
                .padding(.vertical, 12)
            }
            .openCodeReadableColumn()
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .navigationDestination(item: $opened) { input in
            HighlightLabViewerView(input: input, service: service)
        }
    }
}
#endif
