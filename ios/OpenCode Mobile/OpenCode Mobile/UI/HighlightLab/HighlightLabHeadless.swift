#if HIGHLIGHT_LAB
import Foundation
import OpenCodeMarkdownCore
import SwiftUI

/// `xcrun simctl launch <device> <bundle> -labRun <value>`: runs the plan off the main actor,
/// writes the reports under Documents `highlight-lab/`, prints their paths, and exits.
/// The value format is `HighlightBenchHeadless` (for example `quick:engines=textmate`).
enum HighlightLabHeadless {
    /// The `-labRun` launch argument's value, if the app was started with one.
    static var requestedValue: String? {
        value(in: ProcessInfo.processInfo.arguments)
    }

    nonisolated static func value(in arguments: [String]) -> String? {
        let flag = "-" + HighlightBenchHeadless.shared.ARGUMENT_NAME
        guard let index = arguments.firstIndex(of: flag), arguments.indices.contains(index + 1) else { return nil }
        return arguments[index + 1]
    }

    /// Runs `value` to the end and returns the saved files; throws on an invalid value.
    nonisolated static func run(
        _ value: String,
        environment: [String],
        storage: HighlightLabStorage,
        onProgress: @escaping @Sendable (HighlightLabProgress) -> Void
    ) async throws -> HighlightLabStorage.SavedFiles {
        let outcome: Result<HighlightLabReport, Error> = await HighlightLabThread.run("highlight-lab-headless") {
            let title = "Highlighting Lab run: \(value)"
            do {
                let result = try HighlightBenchHeadless.shared.run(
                    value: value,
                    available: HighlightBenchEngines.shared.available(),
                    callGuard: HighlightLabThread.callGuard(),
                    environment: environment
                ) { progress in
                    onProgress(HighlightLabReportConverter.progress(progress, title: title))
                }
                let tables = HighlightLabReportConverter.tables(result.report, title: title)
                return .success(HighlightLabReport(tables: tables, markdown: result.markdown, json: result.json))
            } catch {
                return .failure(error)
            }
        }
        let report = try outcome.get()
        return try storage.save(report, label: value)
    }

    nonisolated static func log(_ line: String) {
        print("HighlightLab: \(line)")
        fflush(stdout)
    }
}

/// What the app shows during a headless run, until it exits.
struct HighlightLabHeadlessView: View {
    let value: String
    @State private var status = "Starting"

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Highlighting Lab headless run")
                .font(OpenCodeTypography.title)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text(value)
                .font(OpenCodeTypography.code)
                .foregroundStyle(OpenCodeColors.textBase)
            Text(status)
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textWeak)
                .accessibilityIdentifier("highlightLabHeadlessStatus")
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(OpenCodeColors.backgroundBase)
        .task { await run() }
    }

    private func run() async {
        HighlightLabHeadless.log("labRun \(value)")
        do {
            let files = try await HighlightLabHeadless.run(
                value,
                environment: HighlightLabEnvironment.lines(),
                storage: .documents
            ) { progress in
                let line = "case \(progress.caseNumber)/\(progress.caseCount) \(progress.engineName): \(progress.caseTitle)"
                Task { @MainActor in status = line }
                if progress.completedSteps == progress.totalSteps {
                    HighlightLabHeadless.log("finished \(progress.totalSteps) steps")
                }
            }
            HighlightLabHeadless.log("wrote \(files.markdown.path)")
            HighlightLabHeadless.log("wrote \(files.json.path)")
            HighlightLabHeadless.log("wrote \(files.stampedMarkdown.path)")
            HighlightLabHeadless.log("wrote \(files.stampedJSON.path)")
            exit(0)
        } catch {
            HighlightLabHeadless.log("failed: \(error.localizedDescription)")
            status = "Failed: \(error.localizedDescription)"
            exit(2)
        }
    }
}
#endif
