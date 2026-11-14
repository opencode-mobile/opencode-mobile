import OpenCodeKit
import SwiftUI

/// Routes first run's steps.
struct FirstRunView: View {
    let model: FirstRunModel

    var body: some View {
        Group {
            switch model.step {
            case .welcome:
                WelcomeView(onScan: { model.startScan() })
            case .scan:
                ScanView(
                    phase: model.scan,
                    attempt: model.scanAttempt,
                    onScanned: { model.scanned($0) },
                    onScanAgain: { model.scanAgain() },
                    onClose: { model.closeScan() }
                )
            case .project:
                if let picker = model.projectPicker {
                    ProjectPickerView(
                        picker: picker,
                        serverLabel: model.server?.displayName ?? "",
                        onBack: { model.backFromProjects() },
                        onPick: { chosen in Task { await model.pickProject(chosen) } }
                    )
                }
            case .model:
                if let page = model.modelPage {
                    ModelPickerView(
                        page: page,
                        serverLabel: model.server?.displayName ?? "",
                        finishing: model.isStarting,
                        finishError: model.startError,
                        onBack: { model.backFromModels() },
                        onFinish: { Task { await model.start() } }
                    )
                }
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.step)
    }
}
