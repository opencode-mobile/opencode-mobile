import SwiftUI

/// Window picker pinned on the Diagnostic log page (#721).
struct FeedbackLogWindowPicker: View {
    let model: FeedbackViewModel

    var body: some View {
        HStack(spacing: 8) {
            ForEach(DiagnosticLogExportWindow.allCases, id: \.self) { window in
                OpenCodeChoiceBadge(
                    label: "\(window.minutes) min",
                    selected: model.uiState.logWindow == window,
                    action: { model.setLogWindow(window) }
                )
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 8)
    }
}

/// Diagnostic log page of the report sheet: the redacted lines a report attaches.
struct FeedbackLogPreviewContent: View {
    let model: FeedbackViewModel

    var body: some View {
        let lines = model.uiState.logPreview ?? []
        VStack(alignment: .leading, spacing: 4) {
            if lines.isEmpty {
                OpenCodeEmptyState(
                    title: "No log lines",
                    message: "Nothing was logged in the last \(model.uiState.logWindow.minutes) minutes.",
                    fillsAvailableSpace: false
                )
                .padding(.vertical, 16)
            } else {
                ForEach(lines) { line in
                    Text("\(line.time) \(line.level.rawValue) \(line.area)  \(line.message)")
                        .font(OpenCodeTypography.code)
                        .foregroundStyle(color(for: line.level))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func color(for level: DiagnosticLogLevel) -> Color {
        switch level {
        case .error: OpenCodeColors.error
        case .warning: OpenCodeColors.warning
        case .debug, .info: OpenCodeColors.textBase
        }
    }
}
