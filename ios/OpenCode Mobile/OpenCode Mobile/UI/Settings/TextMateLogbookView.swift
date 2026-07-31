import Observation
import OpenCodeMarkdownCore
import SwiftUI

@Observable
@MainActor
final class TextMateLogbookViewModel {
    private(set) var entries: [TextMateLogbookEntry] = []
    private(set) var droppedEntries = 0
    private(set) var storageError: String?
    private(set) var isLoading = true

    private let logbook: TextMateLogbook

    init(logbook: TextMateLogbook = .shared) {
        self.logbook = logbook
    }

    func refresh() async {
        isLoading = true
        let snapshot = await logbook.snapshot()
        entries = snapshot.entries
        droppedEntries = snapshot.droppedEntries
        storageError = snapshot.storageError
        isLoading = false
    }

    func clear() async {
        await logbook.clear()
        await refresh()
    }
}

struct TextMateLogbookView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var model = TextMateLogbookViewModel()
    @State private var selectedEntryID: UUID?
    @State private var showClearConfirmation = false

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "TextMate Logbook",
                leading: .back(action: { dismiss() }),
                showDivider: true
            )

            HStack(spacing: 8) {
                Spacer()
                OpenCodeButton(
                    variant: .compactText(title: "Refresh"),
                    contrast: .low
                ) {
                    Task { await model.refresh() }
                }
                OpenCodeButton(
                    variant: .compactText(title: "Clear"),
                    enabled: !model.entries.isEmpty,
                    contrast: .low
                ) {
                    showClearConfirmation = true
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)

            ScrollView {
                LazyVStack(spacing: 8) {
                    if model.droppedEntries > 0 || model.storageError != nil {
                        TextMateLogbookStoreHealthView(
                            droppedEntries: model.droppedEntries,
                            storageError: model.storageError
                        )
                    }

                    if model.isLoading {
                        ProgressView()
                            .tint(OpenCodeColors.interactive)
                            .padding(24)
                    } else if model.entries.isEmpty {
                        Text("No TextMate parsing entries yet.")
                            .font(OpenCodeTypography.body)
                            .foregroundStyle(OpenCodeColors.textBase)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(24)
                    } else {
                        ForEach(model.entries) { entry in
                            TextMateLogbookEntryView(
                                entry: entry,
                                expanded: selectedEntryID == entry.id
                            ) {
                                selectedEntryID = selectedEntryID == entry.id ? nil : entry.id
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
        }
        .navigationBarBackButtonHidden(true)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .task {
            await model.refresh()
        }
        .confirmationDialog(
            "Clear TextMate logbook?",
            isPresented: $showClearConfirmation,
            titleVisibility: .visible
        ) {
            Button("Clear", role: .destructive) {
                selectedEntryID = nil
                Task { await model.clear() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes persisted parsing measurements.")
        }
    }
}

private struct TextMateLogbookEntryView: View {
    let entry: TextMateLogbookEntry
    let expanded: Bool
    let toggleExpanded: () -> Void

    var body: some View {
        VStack(spacing: 6) {
            OpenCodeListItem(
                title: entry.observation.displayLanguage,
                subtitle: entry.observation.summary,
                status: entry.observation.status,
                caption: entry.timestampLabel,
                selected: expanded,
                style: .card,
                action: toggleExpanded
            )

            if expanded {
                TextMateLogbookDetailsView(observation: entry.observation)
            }
        }
    }
}

private struct TextMateLogbookDetailsView: View {
    let observation: NativeTextMateParseObservation

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextMateLogbookDetailLine(label: "Fence", value: "\(observation.fenceIndex)/\(observation.fenceCount)")
            TextMateLogbookDetailLine(label: "Outcome", value: observation.outcome)
            TextMateLogbookDetailLine(label: "Backend", value: observation.backend ?? "none")
            TextMateLogbookDetailLine(label: "Fence lines", value: "\(observation.fenceLines)")
            TextMateLogbookDetailLine(
                label: "Throughput",
                value: TextMateHighlightSpeed.shared.throughputLabel(
                    totalNanoseconds: observation.totalNanoseconds,
                    fenceCharacters: Int32(observation.fenceCharacters),
                    spanCount: Int32(observation.spanCount)
                ) ?? "n/a"
            )
            TextMateLogbookDetailLine(
                label: "Diagnostics",
                value: observation.diagnosticKinds.isEmpty ? "none" : observation.diagnosticKinds.joined(separator: ", ")
            )

            Text("Stage timings overlap and are not additive.")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeaker)

            ForEach(observation.stages, id: \.stage) { stage in
                TextMateLogbookDetailLine(
                    label: stage.stageDisplayName,
                    value: "\(stage.totalNanoseconds.milliseconds) ms · \(stage.eventCount) events · " +
                        "max \(stage.maximumNanoseconds.milliseconds) ms"
                )
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 8))
        .overlay {
            RoundedRectangle(cornerRadius: 8)
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        }
    }
}

private struct TextMateLogbookDetailLine: View {
    let label: String
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
            Spacer(minLength: 8)
            Text(value)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textStrong)
                .multilineTextAlignment(.trailing)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct TextMateLogbookStoreHealthView: View {
    let droppedEntries: Int
    let storageError: String?

    var body: some View {
        Text(message)
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.error)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(OpenCodeColors.error.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
    }

    private var message: String {
        var parts: [String] = []
        if droppedEntries > 0 {
            parts.append("\(droppedEntries) entries dropped under writer pressure.")
        }
        if let storageError {
            parts.append("Storage error: \(storageError).")
        }
        return parts.joined(separator: " ")
    }
}

private extension TextMateLogbookEntry {
    var timestampLabel: String {
        Date(timeIntervalSince1970: Double(timestampEpochMilliseconds) / 1_000)
            .formatted(date: .numeric, time: .standard)
    }
}

private extension NativeTextMateParseObservation {
    var displayLanguage: String {
        canonicalLanguage ?? requestedLanguage ?? "Unspecified language"
    }

    var summary: String {
        let base =
            "\(messageCharacters) message chars · \(fenceCharacters) fence chars · " +
            "\(spanCount) spans · \(totalNanoseconds.milliseconds) ms"
        guard let throughput = TextMateHighlightSpeed.shared.throughputLabel(
            totalNanoseconds: totalNanoseconds,
            fenceCharacters: Int32(fenceCharacters),
            spanCount: Int32(spanCount)
        ) else {
            return base
        }
        return "\(base) · \(throughput)"
    }

    var status: OpenCodeListItemStatus {
        if outcome == "Highlighted" || outcome == "NoSpans" {
            let band = TextMateHighlightSpeed.shared.band(
                totalNanoseconds: totalNanoseconds,
                fenceCharacters: Int32(fenceCharacters)
            )
            if band == TextMateHighlightSpeedBand.fast {
                return OpenCodeListItemStatus(text: "fast", tone: .success)
            }
            if band == TextMateHighlightSpeedBand.ok {
                return OpenCodeListItemStatus(text: "ok", tone: .warning)
            }
            return OpenCodeListItemStatus(text: "slow", tone: .error)
        }
        switch outcome {
        case "Highlighted":
            return OpenCodeListItemStatus(text: "success", tone: .success)
        case "NoSpans", "Unsupported", "Fallback":
            return OpenCodeListItemStatus(text: outcome.lowercased(), tone: .warning)
        default:
            return OpenCodeListItemStatus(text: outcome.lowercased(), tone: .error)
        }
    }
}

private extension NativeTextMateStageSummary {
    var stageDisplayName: String {
        switch stage {
        case "GrammarLoad": "Grammar load"
        case "GrammarLookup": "Grammar lookup"
        case "RuleResolve": "Rule resolve"
        case "RegexCompile": "Regex compile"
        case "RegexSearch": "Regex search"
        case "NativeRegexSearch": "Native regex search"
        case "PreparedLineBuffer": "Prepared line buffer"
        case "Utf8IndexMap": "UTF-8 index map"
        case "Tokenize": "Tokenize"
        case "Repair": "Repair"
        case "IncrementalReuse": "Incremental reuse"
        default: stage
        }
    }
}

private extension Int64 {
    var milliseconds: String {
        formatted(
            .number
                .precision(.fractionLength(3))
                .scale(0.000001)
        )
    }
}
