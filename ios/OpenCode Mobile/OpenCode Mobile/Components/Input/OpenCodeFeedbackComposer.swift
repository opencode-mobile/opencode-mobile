import SwiftUI
import UIKit

/// Send control of the feedback composer.
enum OpenCodeFeedbackComposerSendState: Equatable {
    /// Nothing to send yet (empty message) or sending is blocked.
    case disabled
    case enabled
    /// Spinner in place of the arrow; input is read-only.
    case sending
}

/// A dashed "+ Name" chip for an attachment the report does not have yet.
struct OpenCodeFeedbackComposerAddChip: Identifiable, Equatable {
    let id: String
    let title: String
}

struct OpenCodeFeedbackComposerState {
    let message: String
    let placeholder: String
    let selectionStart: Int?
    let selectionEnd: Int?
    let selectionRevision: Int
    let attachments: [OpenCodeAttachmentPresentation]
    let addChips: [OpenCodeFeedbackComposerAddChip]
    let characterLimit: Int
    let send: OpenCodeFeedbackComposerSendState
    let showDictationButton: Bool

    init(
        message: String,
        placeholder: String,
        selectionStart: Int? = nil,
        selectionEnd: Int? = nil,
        selectionRevision: Int = 0,
        attachments: [OpenCodeAttachmentPresentation] = [],
        addChips: [OpenCodeFeedbackComposerAddChip] = [],
        characterLimit: Int,
        send: OpenCodeFeedbackComposerSendState,
        showDictationButton: Bool = false
    ) {
        self.message = message
        self.placeholder = placeholder
        self.selectionStart = selectionStart
        self.selectionEnd = selectionEnd
        self.selectionRevision = selectionRevision
        self.attachments = attachments
        self.addChips = addChips
        self.characterLimit = characterLimit
        self.send = send
        self.showDictationButton = showDictationButton
    }

    /// `N/limit`, shown once the user has typed something.
    var counterLabel: String? {
        message.isEmpty ? nil : "\(message.count)/\(characterLimit)"
    }
}

enum OpenCodeFeedbackComposerAction: Equatable {
    case textChanged(text: String, selectionStart: Int, selectionEnd: Int)
    case openAttachment(String)
    case removeAttachment(String)
    case addAttachment(String)
    case beginDictation
    case dictationCancel
    case dictationInsert
    case dictationOpenSettings
    case dictationDownload
    case dictationRetry
    case send
}

/// Report composer (#721): the Session Chat composer shell with attachment
/// chips, a multiline message, the dictation mic and Send. Dictation takes the
/// shell over with the same card as Session Chat. Stateless: the parent owns
/// the message, attachments and send state.
struct OpenCodeFeedbackComposer: View {
    static let shellCornerRadius: CGFloat = 26

    let state: OpenCodeFeedbackComposerState
    var dictation: OpenCodeSessionComposerDictation?
    /// Change the value to move keyboard focus into the message.
    var focusRequestToken: Int = 0
    let onAction: (OpenCodeFeedbackComposerAction) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let dictation {
                dictationContent(dictation)
            } else {
                entryContent
            }
        }
        .padding(12)
        .clipShape(RoundedRectangle(cornerRadius: Self.shellCornerRadius, style: .continuous))
        .openCodeFloatingChrome(cornerRadius: Self.shellCornerRadius)
        .animation(.easeInOut(duration: 0.24), value: dictation != nil)
        .accessibilityIdentifier("feedbackComposer")
    }

    @ViewBuilder
    private var entryContent: some View {
        if !state.attachments.isEmpty {
            VStack(spacing: 8) {
                ForEach(state.attachments) { attachment in
                    OpenCodeAttachmentChip(
                        attachment: attachment,
                        onOpen: isSending ? nil : { onAction(.openAttachment($0)) },
                        onRemove: { onAction(.removeAttachment($0)) }
                    )
                    .disabled(isSending)
                }
            }
        }
        if !state.addChips.isEmpty {
            OpenCodeChipFlowLayout(spacing: 8, lineSpacing: 8) {
                ForEach(state.addChips) { chip in
                    OpenCodeAttachmentAddChip(
                        title: chip.title,
                        enabled: !isSending,
                        action: { onAction(.addAttachment(chip.id)) }
                    )
                }
            }
        }

        OpenCodeMentionTextView(
            text: state.message,
            mentionRanges: [],
            selectionStart: state.selectionStart,
            selectionEnd: state.selectionEnd,
            selectionRevision: state.selectionRevision,
            placeholder: state.placeholder,
            focusRequestToken: focusRequestToken,
            onChange: { text, selectionStart, selectionEnd in
                onAction(.textChanged(text: text, selectionStart: selectionStart, selectionEnd: selectionEnd))
            }
        )
        .frame(minHeight: OpenCodeMentionTextViewLayout.font.lineHeight * 3, alignment: .top)
        .padding(4)
        .allowsHitTesting(!isSending)

        HStack(spacing: 8) {
            if let counter = state.counterLabel {
                Text(counter)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .monospacedDigit()
                    .accessibilityLabel("\(state.message.count) of \(state.characterLimit) characters")
            }
            Spacer(minLength: 0)
            if state.showDictationButton {
                OpenCodeButton(
                    variant: .iconCircle(.mic, accessibilityLabel: "Dictate"),
                    enabled: !isSending,
                    contrast: .low,
                    action: { onAction(.beginDictation) }
                )
            }
            sendButton
        }
    }

    @ViewBuilder
    private var sendButton: some View {
        if isSending {
            ProgressView()
                .controlSize(.regular)
                .frame(width: 44, height: 44)
                .accessibilityLabel("Sending report")
        } else {
            OpenCodeButton(
                variant: .iconCircle(.arrowUpward, accessibilityLabel: "Send report"),
                enabled: state.send == .enabled,
                contrast: .accent,
                action: {
                    UIApplication.shared.sendAction(
                        #selector(UIResponder.resignFirstResponder),
                        to: nil,
                        from: nil,
                        for: nil
                    )
                    onAction(.send)
                }
            )
        }
    }

    private var isSending: Bool { state.send == .sending }

    private func dictationContent(_ dictation: OpenCodeSessionComposerDictation) -> some View {
        let showsClock = dictation.variant == .listening || dictation.variant == .finishing
        return Group {
            if showsClock {
                TimelineView(.periodic(from: dictation.startedAt, by: 1)) { context in
                    dictationCard(dictation, elapsed: Self.elapsedLabel(from: dictation.startedAt, to: context.date))
                }
            } else {
                dictationCard(dictation, elapsed: Self.elapsedLabel(from: dictation.startedAt, to: Date()))
            }
        }
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private func dictationCard(_ dictation: OpenCodeSessionComposerDictation, elapsed: String) -> some View {
        OpenCodeDictationCard(
            variant: dictation.variant,
            committed: dictation.committed,
            volatileText: dictation.volatileText,
            level: dictation.level,
            status: dictation.status,
            elapsed: elapsed,
            onCancel: { onAction(.dictationCancel) },
            onInsert: { onAction(.dictationInsert) },
            onUnavailableAction: {
                switch dictation.variant {
                case .unavailable(.permissionDenied):
                    onAction(.dictationOpenSettings)
                case .unavailable(.modelNotInstalled):
                    onAction(.dictationDownload)
                case .unavailable(.recognizerError):
                    onAction(.dictationRetry)
                case .listening, .finishing:
                    onAction(.dictationInsert)
                }
            }
        )
    }

    static func elapsedLabel(from start: Date, to now: Date) -> String {
        let elapsed = max(0, Int(now.timeIntervalSince(start)))
        return String(format: "%d:%02d", elapsed / 60, elapsed % 60)
    }
}

#Preview("Feedback composer") {
    VStack(spacing: 16) {
        OpenCodeFeedbackComposer(
            state: OpenCodeFeedbackComposerState(
                message: "",
                placeholder: "What happened? What did you expect?",
                attachments: [
                    .init(id: "logs", filename: "Logs · 15 min", variant: .text, detail: "Tap to preview"),
                ],
                addChips: [.init(id: "screenshot", title: "Screenshot")],
                characterLimit: 5_000,
                send: .disabled,
                showDictationButton: true
            ),
            onAction: { _ in }
        )
        OpenCodeFeedbackComposer(
            state: OpenCodeFeedbackComposerState(
                message: "The approval card is missing until I pull to refresh.",
                placeholder: "What happened? What did you expect?",
                characterLimit: 5_000,
                send: .sending
            ),
            onAction: { _ in }
        )
    }
    .padding()
    .openCodeTheme()
}
