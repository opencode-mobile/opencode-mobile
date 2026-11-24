#if DEBUG
import SwiftUI

extension ComponentCaptureRegistry {
    static var sessionEntries: [ComponentCaptureEntry] {
        [sessionComposerEntry] + sessionScreenEntries
    }
}

// MARK: - Session composer

private extension ComponentCaptureRegistry {
    static func composer(
        _ scenario: SessionComposerGalleryScenario,
        style: SessionComposerStyle = .outlineFade,
        dictationButton: Bool = false,
        interruptHint: Bool = false
    ) -> some View {
        var fixture = SessionComposerGalleryFixture(scenario: scenario)
        fixture.showsDictationButton = dictationButton
        return OpenCodeSessionComposer(
            state: fixture.state(for: scenario),
            interruptHintVisible: interruptHint,
            style: style,
            onAction: { _ in }
        )
    }

    static var sessionComposerEntry: ComponentCaptureEntry {
        let scenarios: [(String, SessionComposerGalleryScenario)] = [
            ("empty", .empty),
            ("prompt", .prompt),
            ("mentions", .mentions),
            ("active", .active),
            ("attachments", .attachments),
            ("revert", .revert),
            ("shell", .shell),
            ("btw", .sideQuestion),
            ("limit", .limitNote),
            ("child", .childLockout),
            ("permission", .permission),
            ("edit", .permissionEdit),
            ("deny-note", .permissionNote),
            ("queued", .queued),
            ("subagent", .subagentPermission),
            ("submitting", .submitting),
            ("error", .error),
            ("question", .question),
            ("wizard", .questionWizard),
            ("wizard-last", .questionWizardLast),
            ("multi-select", .multiSelect),
            ("up-to-2", .multiSelectBounded),
            ("custom", .custom),
            ("q-submit", .questionSubmitting),
            ("q-error", .questionError),
            ("q-subagent", .subagentQuestion),
        ]
        return ComponentCaptureEntry(
            componentID: "session-composer",
            alignment: .stretch,
            states: scenarios.map { id, scenario in
                ComponentCaptureState(id) { composer(scenario) }
            } + [
                ComponentCaptureState("docked") { composer(.prompt, style: .docked) },
                ComponentCaptureState("dictation-button") { composer(.empty, dictationButton: true) },
                ComponentCaptureState("interrupt-hint") { composer(.active, interruptHint: true) },
                ComponentCaptureState("docked-permission") { composer(.permission, style: .docked) },
                ComponentCaptureState("docked-child-lockout") { composer(.childLockout, style: .docked) },
            ],
            skipped: [
                ComponentCaptureSkip(
                    id: "hint-android",
                    reason: "Android only: iOS draws no inline command argument hint"
                ),
            ]
        )
    }
}
#endif
