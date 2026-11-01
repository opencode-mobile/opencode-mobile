import SwiftUI
import UIKit

extension View {
    /// Half-height sheet with one shell's live output: the command, a status line with a
    /// live timer, the output following the bottom, Copy, and Stop shell when the server
    /// can stop it. The sheet stays open after the shell ends and shows how it ended.
    func sessionChatShellOutputSheet(
        state: SessionChatShellOutputState?,
        onCopy: @escaping () -> Void,
        onStop: @escaping (String) -> Void,
        onDismiss: @escaping () -> Void
    ) -> some View {
        modifier(SessionChatShellOutputSheetModifier(state: state, onCopy: onCopy, onStop: onStop, onDismiss: onDismiss))
    }
}

private struct SessionChatShellOutputSheetModifier: ViewModifier {
    let state: SessionChatShellOutputState?
    let onCopy: () -> Void
    let onStop: (String) -> Void
    let onDismiss: () -> Void

    func body(content: Content) -> some View {
        content.openCodeBottomSheet(
            isPresented: Binding(
                get: { state != nil },
                set: { presented in if !presented { onDismiss() } }
            ),
            title: "Shell",
            followsBottom: state?.shell.status.isRunning == true,
            height: .half,
            pinnedTop: {
                if let state {
                    SessionChatShellOutputHeader(shell: state.shell)
                        .openCodeBottomSheetContentInset()
                }
            },
            content: {
                if let state {
                    SessionChatShellOutputBody(output: state.output)
                        .openCodeBottomSheetContentInset()
                }
            },
            pinnedBottom: {
                if let state {
                    SessionChatShellOutputActions(state: state, onCopy: onCopy, onStop: onStop)
                }
            }
        )
    }
}

/// The command (monospace, up to two lines) and "Running · 1 min 12 s".
struct SessionChatShellOutputHeader: View {
    let shell: AgentShell

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(shell.command.isEmpty ? " " : shell.command)
                .font(OpenCodeTypography.code)
                .foregroundStyle(OpenCodeColors.textStrong)
                .lineLimit(2)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityLabel("Command, \(shell.command)")
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let nowMs = Int64(context.date.timeIntervalSince1970 * 1_000)
                Text(SessionChatShellPresentation.statusLine(shell, nowMs: nowMs))
                    .font(OpenCodeTypography.caption.monospacedDigit())
                    .foregroundStyle(statusColor)
            }
        }
        .padding(.bottom, 8)
    }

    private var statusColor: Color {
        switch shell.status {
        case .failed, .timedOut: OpenCodeColors.error
        case .exited(let code?) where code != 0: OpenCodeColors.error
        default: OpenCodeColors.textWeak
        }
    }
}

/// The output in monospace; "No output yet" before the first line, and a note on top when
/// older output was dropped.
struct SessionChatShellOutputBody: View {
    let output: AgentShellOutput

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if output.isTruncated {
                Text(SessionChatShellPresentation.truncatedNotice)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeaker)
            }
            if output.text.isEmpty {
                Text(SessionChatShellPresentation.emptyOutput)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textWeak)
            } else {
                Text(output.text)
                    .font(OpenCodeTypography.code)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.bottom, 8)
    }
}

/// Copy, and Stop shell while the server can stop it: "Stopping…" while the request is
/// out, and an inline error when it fails.
struct SessionChatShellOutputActions: View {
    let state: SessionChatShellOutputState
    let onCopy: () -> Void
    let onStop: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if state.stopFailed {
                Text(SessionChatShellPresentation.stopFailedMessage)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.error)
            }
            HStack(spacing: 8) {
                OpenCodeButton(
                    variant: .fullWidthText(title: SessionChatShellPresentation.copyLabel),
                    enabled: !state.output.text.isEmpty,
                    contrast: .low
                ) {
                    UIPasteboard.general.string = state.output.text
                    onCopy()
                }
                .accessibilityLabel("Copy output")
                if state.canStop {
                    OpenCodeButton(
                        variant: .fullWidthText(
                            title: state.isStopping
                                ? SessionChatShellPresentation.stoppingLabel
                                : SessionChatShellPresentation.stopLabel
                        ),
                        enabled: !state.isStopping,
                        contrast: .destructive
                    ) {
                        onStop(state.shell.id)
                    }
                }
            }
        }
    }
}
