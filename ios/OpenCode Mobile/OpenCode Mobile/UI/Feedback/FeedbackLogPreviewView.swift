import SwiftUI

/// Diagnostic log preview (#721): the redacted lines a report would attach.
struct FeedbackLogPreviewView: View {
    let model: FeedbackViewModel

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Diagnostic log",
                leading: .back(action: { model.closeLogPreview() }),
                showDivider: true
            )
            OpenCodeFilterTabs(
                tabs: DiagnosticLogExportWindow.allCases.map {
                    OpenCodeFilterTab(id: String($0.minutes), title: "\($0.minutes) min")
                },
                selectedTabId: String(model.uiState.logWindow.minutes),
                onTabSelected: { id in
                    if let window = Int(id).flatMap(DiagnosticLogExportWindow.init(rawValue:)) {
                        model.setLogWindow(window)
                    }
                }
            )
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            lines
        }
        .background(OpenCodeColors.backgroundBase)
    }

    @ViewBuilder
    private var lines: some View {
        let lines = model.uiState.logPreview ?? []
        if lines.isEmpty {
            OpenCodeEmptyState(
                title: "No log lines",
                message: "Nothing was logged in the last \(model.uiState.logWindow.minutes) minutes."
            )
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 4) {
                    ForEach(lines) { line in
                        Text("\(line.time) \(line.level.rawValue) \(line.area)  \(line.message)")
                            .font(OpenCodeTypography.code)
                            .foregroundStyle(color(for: line.level))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                }
                .padding(16)
            }
        }
    }

    private func color(for level: DiagnosticLogLevel) -> Color {
        switch level {
        case .error: OpenCodeColors.error
        case .warning: OpenCodeColors.warning
        case .debug, .info: OpenCodeColors.textBase
        }
    }
}
