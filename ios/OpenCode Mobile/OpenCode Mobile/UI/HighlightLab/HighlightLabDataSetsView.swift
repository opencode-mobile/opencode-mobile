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
                            title: .verbatim(row.displayName),
                            subtitle: .verbatim(row.subtitle),
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
                title: .verbatim(state.title),
                subtitle: .verbatim(state.subtitle),
                leading: presentedModally ? .close(action: { dismiss() }) : .back(action: { dismiss() }),
                actions: state.phase.highlightedEngineID == nil ? [] : [
                    .label(title: "Show plain") { model.showPlain() },
                ],
                showDivider: true
            )
            engines(state)
            if let engineID = state.phase.renderingEngineID {
                rendering(engineName: state.engines.first { $0.id == engineID }?.name ?? engineID)
            } else {
                HighlightLabTimingStrip(timing: state.timing, isRunning: false, idleCaption: "Not highlighted: plain text. Tap an engine to render it.")
            }
            if state.backgroundCalls > 0 {
                OpenCodeInlineNotice(
                    message: state.backgroundCalls == 1
                        ? "A cancelled run is still busy in the background; timings may be slower until it returns."
                        : "\(state.backgroundCalls) cancelled runs are still busy in the background; timings may be slower until they return.",
                    variant: .banner
                )
                .padding(.horizontal, 12)
                .padding(.bottom, 8)
                .accessibilityIdentifier("highlightLab.backgroundCalls")
            }
            Rectangle()
                .fill(OpenCodeColors.borderWeakerBase)
                .frame(height: 1)
            if let rendered = state.rendered {
                OpenCodeCodeFile(
                    lines: rendered.lines,
                    lineSpans: rendered.lineSpans,
                    maxLineLength: rendered.maxLineLength
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                OpenCodeStatusStack(spinner: "Loading text")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .background(OpenCodeColors.backgroundBase)
        .task { await model.start() }
        .onDisappear { model.cancel() }
    }

    /// One button per engine; the shown or rendering engine is selected. They stay enabled while
    /// an engine renders, so another tap switches engines at once.
    private func engines(_ state: HighlightLabViewerUiState) -> some View {
        let selected = state.phase.renderingEngineID ?? state.phase.highlightedEngineID
        let unsupported = state.engines.filter { !$0.isEnabled }
        return VStack(alignment: .leading, spacing: 6) {
            Text("Render with")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
                .padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(state.engines) { engine in
                        OpenCodeChoiceBadge(label: .verbatim(engine.name), selected: engine.id == selected, enabled: engine.isEnabled) {
                            model.select(engine.id)
                        }
                        .accessibilityHint(engine.unsupportedReason ?? "")
                    }
                }
                .padding(.horizontal, 16)
            }
            ForEach(unsupported) { engine in
                Text("\(engine.name): \(engine.unsupportedReason ?? "")")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeaker)
                    .padding(.horizontal, 16)
            }
        }
        .padding(.top, 8)
    }

    private func rendering(engineName: String) -> some View {
        HStack(spacing: 12) {
            ProgressView()
                .tint(OpenCodeColors.interactive)
            VStack(alignment: .leading, spacing: 2) {
                Text("Rendering with \(engineName)…")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textStrong)
                Text("Showing plain text until it finishes")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
            }
            Spacer(minLength: 0)
            OpenCodeButton(variant: .compactText(title: "Cancel"), contrast: .low) { model.cancel() }
                .accessibilityIdentifier("highlightLab.cancel")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("highlightLab.rendering")
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
