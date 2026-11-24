#if DEBUG
import SwiftUI

extension ComponentCaptureRegistry {
    static var diffsEntries: [ComponentCaptureEntry] {
        [diffStatEntry, hunkNavigationPillEntry, reviewChangesPillEntry, unifiedDiffEntry, codeFileEntry]
    }
}

private extension ComponentCaptureRegistry {
    static var diffStatEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "diff-stat", alignment: .center, states: [
            ComponentCaptureState("mixed") { OpenCodeDiffStat(additions: 12, deletions: 3) },
            ComponentCaptureState("additions-only") { OpenCodeDiffStat(additions: 12) },
            ComponentCaptureState("deletions-only") { OpenCodeDiffStat(deletions: 3) },
            ComponentCaptureState("zeros-shown") { OpenCodeDiffStat(additions: 0, deletions: 0, separator: .split) },
            ComponentCaptureState("disabled") { OpenCodeDiffStat(additions: 12, deletions: 3, enabled: false, style: .button) },
            ComponentCaptureState("file-row") { OpenCodeDiffStat(additions: 8, deletions: 2, style: .label, separator: .split) },
        ])
    }

    static func hunkPill(
        previous: Bool = true,
        next: Bool = true,
        context: Bool = true,
        expanded: Bool = false
    ) -> some View {
        OpenCodeHunkNavigationPill(
            previousEnabled: previous,
            nextEnabled: next,
            contextEnabled: context,
            contextExpanded: expanded,
            onPrevious: {},
            onToggleContext: {},
            onNext: {}
        )
    }

    static var hunkNavigationPillEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "hunk-navigation-pill", alignment: .center, states: [
            ComponentCaptureState("middle") { hunkPill() },
            ComponentCaptureState("first") { hunkPill(previous: false) },
            ComponentCaptureState("last") { hunkPill(next: false) },
            ComponentCaptureState("expanded") { hunkPill(expanded: true) },
            ComponentCaptureState("no-context") { hunkPill(context: false) },
            ComponentCaptureState("previous-pressed") { hunkPill().openCodePressedPreview(.part("previous")) },
            ComponentCaptureState("context-pressed") { hunkPill().openCodePressedPreview(.part("context")) },
            ComponentCaptureState("next-pressed") { hunkPill().openCodePressedPreview(.part("next")) },
        ])
    }

    static func reviewPill(files: Int = 5, additions: Int = 12, deletions: Int = 3, enabled: Bool = true) -> some View {
        OpenCodeReviewChangesPill(
            variant: .latestTurn,
            changedFileCount: files,
            additions: additions,
            deletions: deletions,
            enabled: enabled,
            action: {}
        )
    }

    static var reviewChangesPillEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "review-changes-pill", alignment: .center, states: [
            ComponentCaptureState("mixed") { reviewPill() },
            ComponentCaptureState("additions-only") { reviewPill(deletions: 0) },
            ComponentCaptureState("deletions-only") { reviewPill(additions: 0) },
            ComponentCaptureState("zero-lines") { reviewPill(additions: 0, deletions: 0) },
            ComponentCaptureState("1-file") { reviewPill(files: 1, additions: 1, deletions: 1) },
            ComponentCaptureState("pressed") { reviewPill().openCodePressedPreview() },
            ComponentCaptureState("disabled") { reviewPill(enabled: false) },
            ComponentCaptureState("disabled-zero-lines") { reviewPill(additions: 0, deletions: 0, enabled: false) },
        ])
    }

    static func unifiedDiff(_ label: String, expanded: Bool = false, selectedHunk: Bool = true) -> some View {
        let index = UnifiedDiffGalleryView.labels.firstIndex(of: label) ?? 0
        return Group {
            if let file = UnifiedDiffGalleryView.files[index] {
                OpenCodeUnifiedDiff(
                    file: file,
                    document: UnifiedDiffParser.parse(file.patch ?? ""),
                    contextMode: expanded ? .expanded : .collapsed,
                    selectedHunkID: selectedHunk ? 0 : nil
                )
            } else {
                Text("A text preview is unavailable for assets/logo.png.")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
            }
        }
    }

    static var unifiedDiffEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "unified-diff", states: [
            ComponentCaptureState("modified") { unifiedDiff("Modified") },
            ComponentCaptureState("expanded-context") { unifiedDiff("Modified", expanded: true) },
            ComponentCaptureState("no-selected-hunk") { unifiedDiff("Modified", selectedHunk: false) },
            ComponentCaptureState("added") { unifiedDiff("Added") },
            ComponentCaptureState("deleted") { unifiedDiff("Deleted") },
            ComponentCaptureState("large-line-numbers") { unifiedDiff("Large line numbers") },
            ComponentCaptureState(
                "unavailable",
                note: "The screen, not the diff, draws this message; the text is the gallery's"
            ) { unifiedDiff("Unavailable") },
        ])
    }

    static func codeFile(wraps: Bool = false, highlighted: Bool = true, marked: Bool = false) -> some View {
        OpenCodeCodeFile(
            lines: CodeFileGalleryView.lines,
            lineSpans: highlighted ? CodeFileGalleryView.spans : [:],
            lineMarks: marked ? CodeFileGalleryView.marks : [:],
            wrapsLines: wraps
        )
        .frame(height: 260)
        .background(OpenCodeColors.surfaceBase)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(OpenCodeColors.borderWeakerBase))
    }

    static var codeFileEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(
            componentID: "code-file",
            states: [
                ComponentCaptureState("highlighted") { codeFile() },
                ComponentCaptureState("wrapped") { codeFile(wraps: true) },
                ComponentCaptureState("marked-ranges") { codeFile(marked: true) },
                ComponentCaptureState("not-highlighted") { codeFile(highlighted: false) },
                ComponentCaptureState("plain-text") {
                    OpenCodeCodeFile(lines: (1 ... 400).map { "Line \($0) of a long plain text file" })
                        .frame(height: 160)
                        .background(OpenCodeColors.surfaceBase)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                },
            ],
            skipped: [
                ComponentCaptureSkip(id: "json", reason: "No iOS gallery sample: the iOS Code File gallery has no JSON file"),
            ]
        )
    }
}
#endif
