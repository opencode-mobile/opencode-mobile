#if DEBUG
import SwiftUI

extension ComponentCaptureRegistry {
    static var chatEntries: [ComponentCaptureEntry] {
        [
            attachmentEntry, imageGalleryEntry, messageListEntry, outgoingMessageEntry, truncationRowEntry,
            assistantMessageEntry, renderedMarkdownEntry, toolCallCardEntry, toolChipEntry, messageNoteEntry,
            timelineNoticeEntry, childSessionRowEntry, taskRowEntry, shellRowEntry, stepRailEntry, activityCardEntry,
            workingIndicatorEntry, reasoningRowEntry, imageViewerEntry,
        ]
    }
}

/// Fixed clock for live rows: elapsed times read the same on every run.
private let captureNowMs = Int64(Date().timeIntervalSince1970 * 1000)

/// How the timeline composes a tool's images: an image gallery block under the tool.
private struct CaptureWithToolImages<Content: View>: View {
    let count: Int
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            content()
            if count > 0 {
                OpenCodeImageGallery(items: SnapshotEvidenceImageMocks.items(count, prefix: "tool"), onOpen: { _ in })
            }
        }
    }
}

private let toolImagesNote = "Composed as the timeline does: the tool's images are an image gallery block below it"

// MARK: - Attachments, images, message list

private extension ComponentCaptureRegistry {
    static func pendingChip(
        _ variant: OpenCodeAttachmentVariant,
        _ filename: String,
        detail: String? = nil,
        upload: Double? = nil,
        open: Bool = false
    ) -> some View {
        OpenCodeAttachmentChip(
            attachment: OpenCodeAttachmentPresentation(
                id: filename,
                filename: filename,
                variant: variant,
                image: variant == .image ? AttachmentGalleryView.sampleImage : nil,
                detail: detail,
                uploadFraction: upload
            ),
            onOpen: open ? { _ in } : nil,
            onRemove: { _ in }
        )
    }

    static func sentAttachment(_ variant: OpenCodeAttachmentVariant, _ filename: String) -> some View {
        OpenCodeMessageAttachment(
            attachment: OpenCodeAttachmentPresentation(
                id: filename,
                filename: filename,
                variant: variant,
                image: variant == .image ? AttachmentGalleryView.sampleImage : nil
            )
        )
    }

    static var attachmentEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(
            componentID: "attachment",
            states: [
                ComponentCaptureState("pending-image") { pendingChip(.image, "session-screenshot.png") },
                ComponentCaptureState("pending-pdf") { pendingChip(.pdf, "product-brief.pdf") },
                ComponentCaptureState("pending-text") { pendingChip(.text, "notes.md") },
                ComponentCaptureState("pending-file") { pendingChip(.file, "build-output.zip") },
                ComponentCaptureState("long-filename") {
                    pendingChip(.text, "a-very-long-filename-that-demonstrates-single-line-truncation-in-the-chip.md")
                },
                ComponentCaptureState("opens-custom-detail") {
                    pendingChip(.text, "Logs · 15 min", detail: "Tap to preview", open: true)
                },
                ComponentCaptureState("uploading-42") { pendingChip(.file, "build-output.zip", upload: 0.42) },
                ComponentCaptureState("sent-image") { sentAttachment(.image, "session-screenshot.png") },
                ComponentCaptureState("sent-pdf") { sentAttachment(.pdf, "product-brief.pdf") },
                ComponentCaptureState("sent-text") { sentAttachment(.text, "notes.md") },
                ComponentCaptureState("sent-file") { sentAttachment(.file, "build-output.zip") },
            ],
            skipped: [
                ComponentCaptureSkip(id: "upload-starting", reason: "Android only: an upload with no progress report yet"),
                ComponentCaptureSkip(id: "locked", reason: "Android only: enabled = false locks the chip"),
            ]
        )
    }

    static func gallery(_ items: [OpenCodeImageGalleryItem]) -> some View {
        OpenCodeImageGallery(items: items, onOpen: { _ in })
    }

    static func pending(_ id: String) -> OpenCodeImageGalleryItem {
        OpenCodeImageGalleryItem(id: id, filename: "\(id).png", isPending: true)
    }

    static func replacingOverflowTile(_ item: OpenCodeImageGalleryItem) -> [OpenCodeImageGalleryItem] {
        var items = SnapshotEvidenceImageMocks.items(6, prefix: "overflow")
        items[3] = item
        return items
    }

    static var imageGalleryEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "image-gallery", states: [
            ComponentCaptureState("1-image") { gallery(SnapshotEvidenceImageMocks.items(1, prefix: "one")) },
            ComponentCaptureState("2-images") { gallery(SnapshotEvidenceImageMocks.items(2, prefix: "two")) },
            ComponentCaptureState("3-images") { gallery(SnapshotEvidenceImageMocks.items(3, prefix: "three")) },
            ComponentCaptureState("4-images") { gallery(SnapshotEvidenceImageMocks.items(4, prefix: "four")) },
            ComponentCaptureState("6-images-3") { gallery(SnapshotEvidenceImageMocks.items(6, prefix: "six")) },
            ComponentCaptureState("8-images-5") { gallery(SnapshotEvidenceImageMocks.items(8, prefix: "eight")) },
            ComponentCaptureState("loading") { gallery([pending("loading-1"), pending("loading-2")]) },
            ComponentCaptureState("single-loading") { gallery([pending("single-loading")]) },
            ComponentCaptureState("failed-single") {
                gallery([ImageGalleryGalleryView.failed(id: "failed-single", filename: "missing.png")])
            },
            ComponentCaptureState("loaded-loading-failed") { gallery(ImageGalleryGalleryView.loadingAndFailed) },
            ComponentCaptureState("overflow-tile-loading") { gallery(replacingOverflowTile(pending("overflow-loading"))) },
            ComponentCaptureState("overflow-tile-failed") {
                gallery(replacingOverflowTile(ImageGalleryGalleryView.failed(id: "overflow-failed", filename: "missing.png")))
            },
        ])
    }

    static var messageListEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(
            componentID: "message-list",
            states: [
                ComponentCaptureState("pinned-to-end", height: 460) { CaptureMessageList(autoFollow: false) },
                ComponentCaptureState("auto-follow-armed", height: 460) { CaptureMessageList(autoFollow: true) },
                ComponentCaptureState("empty", height: 460) { CaptureMessageList(autoFollow: false, empty: true) },
            ],
            skipped: [
                ComponentCaptureSkip(
                    id: "scrolled-up-pill",
                    reason: "Gesture-driven: the native list shows the pill only after the user scrolls up"
                ),
            ]
        )
    }
}

private struct CaptureMessageList: View {
    @State private var model: MessageListGalleryModel

    init(autoFollow: Bool, empty: Bool = false) {
        let model = MessageListGalleryModel(autoFollowEnabled: autoFollow)
        if empty { model.messages = [] }
        _model = State(initialValue: model)
    }

    var body: some View {
        MessageListGalleryView(model: model)
    }
}

// MARK: - Messages

private extension ComponentCaptureRegistry {
    static let outgoingMetadata = "Build · DeepSeek V4 Flash · 9:05 PM"

    static func outgoing(
        _ text: String,
        steering: OpenCodeOutgoingMessageSteering? = nil,
        header: OpenCodeOutgoingMessageHeader? = nil,
        sentAt: Date? = nil
    ) -> some View {
        OpenCodeOutgoingMessage(
            text: text,
            metadataLabel: outgoingMetadata,
            sentAt: sentAt,
            steering: steering,
            header: header,
            onTap: {}
        )
    }

    static var outgoingAttachments: [OpenCodeAttachmentPresentation] {
        [
            .init(id: "pdf", filename: "requirements.pdf", variant: .pdf),
            .init(id: "text", filename: "notes.md", variant: .text),
        ]
    }

    // swiftlint:disable:next function_body_length
    static var outgoingMessageEntry: ComponentCaptureEntry {
        let limited = "Paste of a long build log, cut where the timeline stops showing it."
        return ComponentCaptureEntry(componentID: "outgoing-message", states: [
            ComponentCaptureState("short") { outgoing(OutgoingMessageSample.short.text) },
            ComponentCaptureState("long") { outgoing(OutgoingMessageSample.long.text) },
            ComponentCaptureState("command-token") { outgoing("[/review] the current branch") },
            ComponentCaptureState("attachments-only") {
                OpenCodeOutgoingMessage(text: "", attachments: outgoingAttachments, metadataLabel: outgoingMetadata, onTap: {})
            },
            ComponentCaptureState("text-and-attachments") {
                OpenCodeOutgoingMessage(
                    text: "Here are the requirements.", attachments: outgoingAttachments, metadataLabel: outgoingMetadata, onTap: {}
                )
            },
            ComponentCaptureState("image-gallery") {
                OpenCodeOutgoingMessage(
                    content: [
                        .images(id: "bubble", items: SnapshotEvidenceImageMocks.items(2, prefix: "bubble"), onOpen: { _ in }),
                        .text(id: "text", text: "Both phones clip the login button. Can you fix it?"),
                    ],
                    metadataLabel: outgoingMetadata
                )
            },
            ComponentCaptureState("rendered-markdown") {
                if let sample = RenderedMarkdownGalleryMocks.sample("request") {
                    OpenCodeOutgoingMessage(
                        text: sample.rawMarkdown,
                        renderedMarkdown: sample.document,
                        metadataLabel: outgoingMetadata
                    )
                }
            },
            ComponentCaptureState("cut-text") {
                OpenCodeOutgoingMessage(
                    content: [
                        .text(
                            id: "cut",
                            text: limited,
                            limit: OpenCodeOutgoingMessageLimit(
                                shownCharacters: 1_000, totalCharacters: 588_638, onShowFull: {}
                            )
                        ),
                    ],
                    metadataLabel: outgoingMetadata,
                    onTap: {}
                )
            },
            ComponentCaptureState("cut-nothing-shown") {
                OpenCodeOutgoingMessage(
                    content: [
                        .text(
                            id: "cut",
                            text: "",
                            limit: OpenCodeOutgoingMessageLimit(shownCharacters: 0, totalCharacters: 588_638, onShowFull: {})
                        ),
                    ],
                    metadataLabel: outgoingMetadata,
                    onTap: {}
                )
            },
            ComponentCaptureState("steering-delivered") {
                outgoing("Use the staging bucket instead.", steering: .delivered(showsLabel: true))
            },
            ComponentCaptureState("steering-pending") {
                outgoing("And skip the migration step.", steering: .pending(showsLabel: true))
            },
            ComponentCaptureState("pending-no-label") {
                outgoing("And skip the migration step.", steering: .pending(showsLabel: false))
            },
            ComponentCaptureState("goal-kickoff") {
                outgoing("Match the layout in the screenshot", header: .goal(label: "Goal"))
            },
            ComponentCaptureState(
                "time-from-another-day",
                note: "The sent time shows on a drag; the capture shows the message at rest"
            ) {
                outgoing("Drag this message left", sentAt: Date().addingTimeInterval(-26 * 60 * 60))
            },
        ])
    }

    static func truncation(_ shown: Int, _ total: Int) -> some View {
        OpenCodeTruncationRow(variant: .fullMessage(shownCharacters: shown, totalCharacters: total), action: {})
    }

    static var truncationRowEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "truncation-row", states: [
            ComponentCaptureState("default") { truncation(1_000, 588_638) },
            ComponentCaptureState("pressed") { truncation(1_000, 588_638).openCodePressedPreview() },
            ComponentCaptureState("small-counts") { truncation(987, 1_412) },
            ComponentCaptureState("nothing-shown") { truncation(0, 588_638) },
        ])
    }

    static func assistant(
        _ text: String,
        markdown: String? = nil,
        presentation: OpenCodeAssistantMessagePresentation = .bubble
    ) -> some View {
        let sample = markdown.flatMap(RenderedMarkdownGalleryMocks.sample)
        return OpenCodeAssistantMessage(
            text: sample?.rawMarkdown ?? text,
            renderedMarkdown: sample?.document,
            presentation: presentation
        )
    }

    static var assistantMessageEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "assistant-message", states: [
            ComponentCaptureState("short") { assistant(AssistantMessageSample.short.text) },
            ComponentCaptureState("long") { assistant(AssistantMessageSample.long.text) },
            ComponentCaptureState("empty") { assistant(AssistantMessageSample.empty.text) },
            ComponentCaptureState("markdown") { assistant("", markdown: "assistant") },
            ComponentCaptureState("code") { assistant("", markdown: "code") },
            ComponentCaptureState("plain-short") { assistant(AssistantMessageSample.short.text, presentation: .plain) },
            ComponentCaptureState("plain-long") { assistant(AssistantMessageSample.long.text, presentation: .plain) },
            ComponentCaptureState("plain-markdown") { assistant("", markdown: "assistant", presentation: .plain) },
        ])
    }

    static var renderedMarkdownEntry: ComponentCaptureEntry {
        let ids = ["assistant", "request", "code", "languages", "rich", "table", "diff"]
        return ComponentCaptureEntry(
            componentID: "rendered-markdown",
            states: ids.map { id in
                ComponentCaptureState(id) {
                    if let sample = RenderedMarkdownGalleryMocks.sample(id) { RenderedMarkdownGalleryView.render(sample) }
                }
            } + [
                ComponentCaptureState("headings-and-inline") {
                    if let sample = RenderedMarkdownGalleryMocks.sample("headings") { RenderedMarkdownGalleryView.render(sample) }
                },
            ],
            skipped: [
                ComponentCaptureSkip(
                    id: "syntax-roles",
                    reason: "Android gallery sample only; iOS draws the same code blocks in Code and Languages"
                ),
                ComponentCaptureSkip(id: "role-to-token-map", reason: "HTML reference table; no native view draws it"),
            ]
        )
    }
}

// MARK: - Tools

private extension ComponentCaptureRegistry {
    static var toolCallCardEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "tool-call-card", states: [
            ComponentCaptureState("pending") { OpenCodeToolCallCard(status: .pending, title: "Called bash", subtitle: "npm test") },
            ComponentCaptureState("running") {
                OpenCodeToolCallCard(status: .running, title: "Called task", subtitle: "Explore codebase")
            },
            ComponentCaptureState("completed") {
                OpenCodeToolCallCard(
                    status: .completed, title: "Called edit", subtitle: "OpenCodeListItem.swift", additions: 12, deletions: 3
                )
            },
            ComponentCaptureState("tappable") {
                OpenCodeToolCallCard(
                    status: .completed, title: "Called edit", subtitle: "OpenCodeListItem.swift", additions: 12, deletions: 3,
                    action: {}
                )
            },
            ComponentCaptureState("title-only") { OpenCodeToolCallCard(status: .completed, title: "Called skill") },
            ComponentCaptureState("error") {
                OpenCodeToolCallCard(status: .error, title: "Called webfetch", errorText: "Request failed with status 403")
            },
            ComponentCaptureState("interrupted") {
                OpenCodeToolCallCard(status: .interrupted, title: "Called shell", subtitle: "./deploy.sh", errorText: "Stopped")
            },
            ComponentCaptureState(
                "declined-with-note",
                note: "The card has no note line; the declined note shows in the Step Rail only"
            ) {
                OpenCodeToolCallCard(status: .interrupted, title: "Called shell", subtitle: "npm test", errorText: "Declined")
            },
            ComponentCaptureState("with-tool-images", note: toolImagesNote) {
                CaptureWithToolImages(count: 2) {
                    OpenCodeToolCallCard(status: .completed, title: "Called screenshot", subtitle: "Home screen")
                }
            },
        ])
    }

    static func chip(
        _ choice: ToolChipGalleryView.VariantChoice,
        status: OpenCodeToolChipStatus = .completed,
        trailing: String? = nil
    ) -> some View {
        OpenCodeToolChip(variant: choice.variant, status: status, trailingText: trailing, action: {})
    }

    static var toolChipEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "tool-chip", alignment: .stretch, states: [
            ComponentCaptureState("file") { chip(.file) },
            ComponentCaptureState("edit") { chip(.edit) },
            ComponentCaptureState("command") { chip(.command) },
            ComponentCaptureState("agent") { chip(.agent) },
            ComponentCaptureState("skill") { chip(.skill) },
            ComponentCaptureState("explore") { chip(.explore) },
            ComponentCaptureState("more") { chip(.more) },
            ComponentCaptureState("running") { chip(.command, status: .running) },
            ComponentCaptureState("pending") { chip(.command, status: .pending) },
            ComponentCaptureState("error") { chip(.command, status: .error) },
            ComponentCaptureState("interrupted") { chip(.command, status: .interrupted) },
            ComponentCaptureState("trailing-text") { chip(.command, trailing: "1m 4s") },
            ComponentCaptureState("with-tool-images", note: toolImagesNote) {
                CaptureWithToolImages(count: 3) { chip(.command) }
            },
        ])
    }

    static var messageNoteEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "message-note", states: [
            ComponentCaptureState("truncated") { OpenCodeMessageNote(variant: .truncated) },
            ComponentCaptureState("interrupted") { OpenCodeMessageNote(variant: .interrupted) },
        ])
    }

    static func notice(
        _ variant: OpenCodeTimelineNoticeVariant,
        _ label: String,
        _ value: String?,
        state: OpenCodeTimelineNoticeState = .collapsed,
        tag: String? = nil
    ) -> some View {
        OpenCodeTimelineNotice(
            variant: variant,
            label: label,
            value: value,
            state: state,
            onToggle: value == nil ? nil : {},
            onActivate: variant == .goal ? {} : nil,
            tag: tag
        )
    }

    static var timelineNoticeEntry: ComponentCaptureEntry {
        let moved = "/Users/example/project/.worktrees/feature-702-v2-timeline-notices"
        return ComponentCaptureEntry(componentID: "timeline-notice", states: [
            ComponentCaptureState("moved") { notice(.moved, "Moved to", moved) },
            ComponentCaptureState("agent") { notice(.plain, "Agent changed", "Build \u{2192} Plan") },
            ComponentCaptureState("model") { notice(.plain, "Switched to", "Claude Opus 5.5") },
            ComponentCaptureState("variant") { notice(.plain, "Switched variant to", "high") },
            ComponentCaptureState("variant-default") { notice(.plain, "Switched variant to Default", nil) },
            ComponentCaptureState("instructions") {
                notice(.plain, "Instructions updated", "AGENTS.md, packages/app/AGENTS.md, ~/.config/opencode/AGENTS.md")
            },
            ComponentCaptureState("restart") { notice(.plain, "Continuing after restart", nil) },
            ComponentCaptureState("goal-set") {
                notice(.goal, "Goal set", "Make the unit tests pass on the session list refactor")
            },
            ComponentCaptureState("goal-ended") {
                notice(.goalEnded, "Goal set", "Get lint to zero warnings in the session list package", tag: "ended")
            },
            ComponentCaptureState("expanded") { notice(.moved, "Moved to", moved, state: .expanded) },
        ])
    }
}

// MARK: - Rows, rail, card, indicators

private extension ComponentCaptureRegistry {
    static func childRow(
        title: String = "explore",
        caption: String? = nil,
        subtitle: String? = "Standards review of OPE-144",
        elapsed: String? = "1:04",
        status: OpenCodeChildSessionRowStatus? = nil,
        tappable: Bool = true
    ) -> some View {
        OpenCodeChildSessionRow(
            title: title, caption: caption, subtitle: subtitle, elapsed: elapsed, status: status,
            action: tappable ? {} : nil
        )
    }

    static var childSessionRowEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "child-session-row", states: [
            ComponentCaptureState("default") { childRow() },
            ComponentCaptureState("background") {
                childRow(caption: "background", subtitle: "Find auth bugs", elapsed: "0:12")
            },
            ComponentCaptureState("running") { childRow(status: .running) },
            ComponentCaptureState("waiting-for-input") { childRow(status: .waitingForInput) },
            ComponentCaptureState("done") { childRow(status: .done) },
            ComponentCaptureState("failed") { childRow(status: .failed) },
            ComponentCaptureState("stopped") { childRow(status: .stopped) },
            ComponentCaptureState("display-only") {
                childRow(title: "Task", subtitle: "Investigate flaky tests", elapsed: nil, tappable: false)
            },
        ])
    }

    static func taskRow(_ state: OpenCodeTaskRowState, detail: Bool = true, tappable: Bool = true) -> some View {
        let sample = TaskRowGalleryView.samples.first { $0.state == state } ?? TaskRowGalleryView.samples[0]
        return OpenCodeTaskRow(
            title: sample.title,
            state: sample.state,
            tag: sample.tag,
            detail: detail ? sample.detail : nil,
            detailCaption: sample.detailCaption,
            time: sample.time,
            action: tappable ? {} : nil
        )
    }

    static var taskRowEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "task-row", states: [
            ComponentCaptureState("running") { taskRow(.running) },
            ComponentCaptureState("waiting-for-input") { taskRow(.waitingForInput) },
            ComponentCaptureState("done") { taskRow(.done) },
            ComponentCaptureState("failed") { taskRow(.failed) },
            ComponentCaptureState("stopped") { taskRow(.stopped) },
            ComponentCaptureState("no-detail") { taskRow(.running, detail: false) },
            ComponentCaptureState("display-only") { taskRow(.done, tappable: false) },
        ])
    }

    static func shellRow(
        _ command: String = ShellRowGalleryView.shortCommand,
        description: Bool = true,
        elapsed: Bool = true,
        tappable: Bool = true
    ) -> some View {
        OpenCodeShellRow(
            command: command,
            description: description ? "Run the test suite" : nil,
            elapsed: elapsed ? SessionChatChildSessionElapsed.format(startEpochMs: 0, nowEpochMs: 72_000) : nil,
            action: tappable ? {} : nil
        )
    }

    static var shellRowEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "shell-row", states: [
            ComponentCaptureState("running") { shellRow() },
            ComponentCaptureState("long-command") { shellRow(ShellRowGalleryView.longCommand, description: false) },
            ComponentCaptureState("multi-line-command") { shellRow("npm run build\nnpm test") },
            ComponentCaptureState("no-description") { shellRow(description: false) },
            ComponentCaptureState("no-elapsed") { shellRow(elapsed: false) },
            ComponentCaptureState("display-only") { shellRow(description: false, elapsed: false, tappable: false) },
        ])
    }

    static func rail(_ sample: StepRailGalleryView.Sample) -> some View {
        StepRailGalleryView.rail(for: sample, startMs: captureNowMs)
    }

    static var stepRailEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "step-rail", states: [
            ComponentCaptureState("one-running") { rail(.oneRunning) },
            ComponentCaptureState("parallel-child") { rail(.parallel) },
            ComponentCaptureState("folded-failed") { rail(.folded) },
            ComponentCaptureState("finished") { rail(.finished) },
            ComponentCaptureState("declined-with-note") { rail(.declined) },
            ComponentCaptureState("pending") { rail(.pending) },
            ComponentCaptureState("failed-child") { rail(.failedChild) },
            ComponentCaptureState("with-tool-images", note: toolImagesNote) {
                CaptureWithToolImages(count: 2) { rail(.finished) }
            },
        ])
    }

    static func card(_ sample: ActivityCardGalleryView.Sample) -> some View {
        ActivityCardGalleryView.card(for: sample, startMs: captureNowMs)
    }

    static var activityCardEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "activity-card", states: [
            ComponentCaptureState("one-running-tool") { card(.oneTool) },
            ComponentCaptureState(
                "footer-crossfade",
                note: "Animated demo; captured at rest, the same as One running tool"
            ) { card(.oneTool) },
            ComponentCaptureState("two-agents-1") { card(.twoAgents) },
            ComponentCaptureState("last-tool-just-finished") { card(.justFinished) },
            ComponentCaptureState("waiting-for-agents") { card(.waiting) },
            ComponentCaptureState("settled") { card(.settled) },
            ComponentCaptureState("settled-with-agents") { card(.settledAgents) },
            ComponentCaptureState("settled-failed") { card(.settledFailure) },
            ComponentCaptureState("settled-stopped") { card(.settledStopped) },
            ComponentCaptureState("with-tool-images", note: toolImagesNote) {
                CaptureWithToolImages(count: 4) { card(.settled) }
            },
        ])
    }

    static var workingIndicatorEntry: ComponentCaptureEntry {
        let firstFrame = "Animated demo; captured at its first frame"
        return ComponentCaptureEntry(componentID: "working-indicator", states: [
            ComponentCaptureState("working") { OpenCodeWorkingIndicator(label: "Working", state: .working) },
            ComponentCaptureState("idle") { OpenCodeWorkingIndicator(label: "Idle", state: .idle) },
            ComponentCaptureState("expanded") { OpenCodeWorkingIndicator(label: "Retrying", state: .expanded) },
            ComponentCaptureState("fading") { OpenCodeWorkingIndicator(label: "Idle", state: .idle, fading: true) },
            ComponentCaptureState("collapsed", note: "Collapsed has zero height; the tile is its padding") {
                OpenCodeWorkingIndicator(label: "Idle", state: .collapsed)
            },
            ComponentCaptureState("working-to-idle", note: firstFrame) {
                OpenCodeWorkingIndicator(label: "Working", state: .working)
            },
            ComponentCaptureState("turn-pill-working-to-done", note: firstFrame) {
                OpenCodeTurnStatusPill(phase: .working(label: "Working"))
            },
            ComponentCaptureState("turn-pill-working") { OpenCodeTurnStatusPill(phase: .working(label: "Working")) },
            ComponentCaptureState("turn-pill-working-compact") {
                OpenCodeTurnStatusPill(phase: .working(label: "Working"), compact: true)
            },
            ComponentCaptureState("turn-pill-compacting") { OpenCodeTurnStatusPill(phase: .working(label: "Compacting…")) },
            ComponentCaptureState("turn-pill-review-permission") {
                OpenCodeTurnStatusPill(phase: .attention(label: "Review permission", mark: .arrowUp), action: {})
            },
            ComponentCaptureState("turn-pill-waiting-for-you") {
                OpenCodeTurnStatusPill(phase: .attention(label: "Waiting for you", mark: .pulsingDot))
            },
            ComponentCaptureState("turn-pill-request-failed") {
                OpenCodeTurnStatusPill(phase: .failed(label: "Request failed"), action: {}, onClose: {})
            },
            ComponentCaptureState("turn-pill-done") { OpenCodeTurnStatusPill(phase: .done) },
            ComponentCaptureState("turn-pill-done-compact") { OpenCodeTurnStatusPill(phase: .done, compact: true) },
        ])
    }

    static var reasoningRowEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "reasoning-row", states: [
            ComponentCaptureState("working") { OpenCodeReasoningRow(state: .working, durationLabel: "1.2s") },
            ComponentCaptureState("expanded-with-duration") {
                OpenCodeReasoningRow(state: .expanded, durationLabel: "1.2s", action: {})
            },
            ComponentCaptureState("expanded") { OpenCodeReasoningRow(state: .expanded, action: {}) },
            ComponentCaptureState("idle") { OpenCodeReasoningRow(state: .idle, durationLabel: "1.2s") },
            ComponentCaptureState("collapsed") { OpenCodeReasoningRow(state: .collapsed, durationLabel: "1.2s") },
        ])
    }

    static func viewer(
        _ items: [OpenCodeImageGalleryItem],
        index: Int = 0,
        retry: Bool = false
    ) -> some View {
        OpenCodeImageViewer(
            items: items,
            index: index,
            onIndexChange: { _ in },
            onDismiss: {},
            onRetry: retry ? { _ in } : nil
        )
    }

    static var imageViewerEntry: ComponentCaptureEntry {
        let note = "Full-screen viewer drawn inside a 400 pt tile"
        let preview = SnapshotEvidenceImageMocks.image(0)
        return ComponentCaptureEntry(componentID: "image-viewer", states: [
            ComponentCaptureState("one-image", height: 400, note: note) {
                viewer(SnapshotEvidenceImageMocks.items(1, prefix: "viewer-one"))
            },
            ComponentCaptureState("5-images-at-the-second", height: 400, note: note) {
                viewer(SnapshotEvidenceImageMocks.items(5, prefix: "viewer-five"), index: 1)
            },
            ComponentCaptureState("group-with-a-failed-image", height: 400, note: note) {
                viewer(
                    SnapshotEvidenceImageMocks.items(2, prefix: "viewer-failed")
                        + [ImageGalleryGalleryView.failed(id: "viewer-failed-missing", filename: "missing-capture.png")],
                    index: 2
                )
            },
            ComponentCaptureState("page-loading", height: 400, note: note) { viewer([pending("viewer-loading")]) },
            ComponentCaptureState("preview-full-picture-loading", height: 400, note: note) {
                viewer([OpenCodeImageGalleryItem(id: "preview-loading", filename: "login-iphone-se.png", preview: preview, fullState: .loading)])
            },
            ComponentCaptureState("preview-full-picture-failed", height: 400, note: note) {
                viewer(
                    [OpenCodeImageGalleryItem(id: "preview-failed", filename: "login-iphone-se.png", preview: preview, fullState: .failed)],
                    retry: true
                )
            },
            ComponentCaptureState("failed-with-retry", height: 400, note: note) {
                viewer([OpenCodeImageGalleryItem(id: "failed-retry", filename: "missing.png", fullState: .failed)], retry: true)
            },
        ])
    }
}
#endif
