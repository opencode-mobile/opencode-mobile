package dev.opencode.mobile.ui.componentgallery.capture

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.componentgallery.components.AssistantMessageSample
import dev.opencode.mobile.ui.componentgallery.components.MessageListGalleryMocks
import dev.opencode.mobile.ui.componentgallery.components.MessageListGalleryPreview
import dev.opencode.mobile.ui.componentgallery.components.OutgoingMessageSample
import dev.opencode.mobile.ui.componentgallery.components.RenderedMarkdownGalleryMocks
import dev.opencode.mobile.ui.componentgallery.components.SHELL_ROW_GALLERY_LONG_COMMAND
import dev.opencode.mobile.ui.componentgallery.components.SHELL_ROW_GALLERY_SHORT_COMMAND
import dev.opencode.mobile.ui.componentgallery.components.ToolChipGalleryVariant
import dev.opencode.mobile.ui.componentgallery.components.TruncationRowGallerySample
import dev.opencode.mobile.ui.componentgallery.components.ratioImage
import dev.opencode.mobile.ui.componentgallery.components.taskRowGallerySamples
import dev.opencode.mobile.ui.componentgallery.components.toVariant
import dev.opencode.mobile.ui.components.INLINE_DECODE_MAX_DIMENSION
import dev.opencode.mobile.ui.components.LocalOpenCodeGalleryImageLoader
import dev.opencode.mobile.ui.components.OpenCodeActivityCard
import dev.opencode.mobile.ui.components.OpenCodeActivityCardSamples
import dev.opencode.mobile.ui.components.OpenCodeActivityCardState
import dev.opencode.mobile.ui.components.OpenCodeActivityCurrentRow
import dev.opencode.mobile.ui.components.OpenCodeActivityStatus
import dev.opencode.mobile.ui.components.OpenCodeAssistantMessage
import dev.opencode.mobile.ui.components.OpenCodeAssistantMessagePresentation
import dev.opencode.mobile.ui.components.OpenCodeAttachmentChip
import dev.opencode.mobile.ui.components.OpenCodeAttachmentPresentation
import dev.opencode.mobile.ui.components.OpenCodeAttachmentUploadState
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
import dev.opencode.mobile.ui.components.OpenCodeChatActivityState
import dev.opencode.mobile.ui.components.OpenCodeChildSessionRow
import dev.opencode.mobile.ui.components.OpenCodeChildSessionRowStatus
import dev.opencode.mobile.ui.components.OpenCodeGalleryImage
import dev.opencode.mobile.ui.components.OpenCodeGalleryImageLoader
import dev.opencode.mobile.ui.components.OpenCodeImageBitmapCache
import dev.opencode.mobile.ui.components.OpenCodeImageGallery
import dev.opencode.mobile.ui.components.OpenCodeMessageAttachment
import dev.opencode.mobile.ui.components.OpenCodeMessageNote
import dev.opencode.mobile.ui.components.OpenCodeMessageNoteVariant
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessageContent
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessageLabel
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessageLimit
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessageSteering
import dev.opencode.mobile.ui.components.OpenCodeReasoningRow
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdown
import dev.opencode.mobile.ui.components.OpenCodeShellRow
import dev.opencode.mobile.ui.components.OpenCodeStepRail
import dev.opencode.mobile.ui.components.OpenCodeStepRailItem
import dev.opencode.mobile.ui.components.OpenCodeStepRailSamples
import dev.opencode.mobile.ui.components.OpenCodeStepRailStatus
import dev.opencode.mobile.ui.components.OpenCodeTaskRow
import dev.opencode.mobile.ui.components.OpenCodeTaskRowState
import dev.opencode.mobile.ui.components.OpenCodeTimelineNotice
import dev.opencode.mobile.ui.components.OpenCodeTimelineNoticeState
import dev.opencode.mobile.ui.components.OpenCodeTimelineNoticeVariant
import dev.opencode.mobile.ui.components.OpenCodeToolCallCard
import dev.opencode.mobile.ui.components.OpenCodeToolCallCardStatus
import dev.opencode.mobile.ui.components.OpenCodeToolChip
import dev.opencode.mobile.ui.components.OpenCodeToolChipStatus
import dev.opencode.mobile.ui.components.OpenCodeTruncationRow
import dev.opencode.mobile.ui.components.OpenCodeTruncationRowVariant
import dev.opencode.mobile.ui.components.OpenCodeTurnStatusAttentionMark
import dev.opencode.mobile.ui.components.OpenCodeTurnStatusPill
import dev.opencode.mobile.ui.components.OpenCodeTurnStatusPillPhase
import dev.opencode.mobile.ui.components.OpenCodeWorkingIndicator
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.snapshot.SnapshotEvidenceGalleryMocks
import kotlinx.coroutines.awaitCancellation
import java.time.LocalDate
import java.time.ZoneId

internal fun chatCaptures(): List<ComponentCaptureComponent> =
    listOf(
        attachmentCaptures(),
        imageGalleryCaptures(),
        messageListCaptures(),
        outgoingMessageCaptures(),
        truncationRowCaptures(),
        assistantMessageCaptures(),
        renderedMarkdownCaptures(),
        toolCallCardCaptures(),
        toolChipCaptures(),
        messageNoteCaptures(),
        timelineNoticeCaptures(),
        childSessionRowCaptures(),
        taskRowCaptures(),
        shellRowCaptures(),
        stepRailCaptures(),
        activityCardCaptures(),
        workingIndicatorCaptures(),
        reasoningRowCaptures(),
    )

// ---- Images ----

private enum class CaptureTile { Loaded, Loading, Failed }

/**
 * Serves the gallery's image seeds by position, with chosen tiles held loading or failing.
 * Loaded tiles are decoded into the tile cache up front, so they paint on the first frame.
 */
private class CaptureImageLoader(
    private val tiles: Map<String, CaptureTile>,
) : OpenCodeGalleryImageLoader {
    override suspend fun load(id: String): ByteArray? =
        when (tiles[id]) {
            CaptureTile.Loading -> awaitCancellation()
            CaptureTile.Failed, null -> null
            CaptureTile.Loaded -> SnapshotEvidenceGalleryMocks.png(seedFor(id))
        }
}

private fun seedFor(id: String): SnapshotEvidenceGalleryMocks.Seed {
    val seeds = SnapshotEvidenceGalleryMocks.seeds
    return seeds[(id.substringAfterLast('-').toInt() - 1) % seeds.size]
}

/** [count] gallery images; [states] overrides a tile's load by index. */
private fun captureImages(
    count: Int,
    states: Map<Int, CaptureTile> = emptyMap(),
): Pair<List<OpenCodeGalleryImage>, Map<String, CaptureTile>> {
    val tiles = mutableMapOf<String, CaptureTile>()
    val images =
        (1..count).map { number ->
            // Unique per tile set: the bitmap cache outlives a tile, and a cached id paints as loaded.
            val id = "capture-${states.hashCode()}-$count-image-$number"
            val tile = states[number - 1] ?: CaptureTile.Loaded
            tiles[id] = tile
            val name = if (tile == CaptureTile.Failed) "missing-capture.png" else seedFor(id).name
            if (tile == CaptureTile.Loaded) {
                val png = SnapshotEvidenceGalleryMocks.png(seedFor(id))
                OpenCodeImageBitmapCache.put(id, INLINE_DECODE_MAX_DIMENSION, BitmapFactory.decodeByteArray(png, 0, png.size))
            }
            OpenCodeGalleryImage(id, name)
        }
    return images to tiles
}

@Composable
private fun CaptureImageGallery(
    count: Int,
    states: Map<Int, CaptureTile> = emptyMap(),
) {
    val (images, tiles) = remember(count, states) { captureImages(count, states) }
    CompositionLocalProvider(LocalOpenCodeGalleryImageLoader provides CaptureImageLoader(tiles)) {
        OpenCodeImageGallery(images = images, onImageClick = {})
    }
}

/** A tool block with its images under it, as the timeline lays them out. */
@Composable
private fun WithToolImages(
    count: Int,
    block: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block()
        CaptureImageGallery(count)
    }
}

private const val TOOL_IMAGES_NOTE = "The tool block with an image gallery block under it, as the timeline lays them out"

// ---- Attachment ----

private fun attachmentCaptures(): ComponentCaptureComponent {
    fun presentation(
        variant: OpenCodeAttachmentVariant,
        filename: String,
        upload: OpenCodeAttachmentUploadState = OpenCodeAttachmentUploadState.Idle,
    ): OpenCodeAttachmentPresentation =
        if (variant == OpenCodeAttachmentVariant.Image) {
            ratioImage("image", filename, 640, 360, 0xFF3B6EA8.toInt())
        } else {
            OpenCodeAttachmentPresentation(id = filename, filename = filename, variant = variant, uploadState = upload)
        }

    fun chip(
        id: String,
        variant: OpenCodeAttachmentVariant,
        filename: String,
        upload: OpenCodeAttachmentUploadState = OpenCodeAttachmentUploadState.Idle,
        opens: Boolean = false,
        enabled: Boolean = true,
        note: String? = null,
    ) = ComponentCaptureState(id, note = note) {
        val attachment = remember { presentation(variant, filename, upload) }
        OpenCodeAttachmentChip(attachment = attachment, onRemove = {}, onClick = if (opens) ({}) else null, enabled = enabled)
    }

    fun sent(
        id: String,
        variant: OpenCodeAttachmentVariant,
        filename: String,
    ) = ComponentCaptureState(id) {
        val attachment = remember { presentation(variant, filename) }
        OpenCodeMessageAttachment(attachment)
    }
    val image = OpenCodeAttachmentVariant.Image
    val pdf = OpenCodeAttachmentVariant.Pdf
    val text = OpenCodeAttachmentVariant.Text
    val file = OpenCodeAttachmentVariant.File
    return ComponentCaptureComponent(
        id = "attachment",
        states =
            listOf(
                chip("pending-image", image, "session-screenshot.png"),
                chip("pending-pdf", pdf, "product-brief.pdf"),
                chip("pending-text", text, "notes.md"),
                chip("pending-file", file, "build-output.zip"),
                chip("long-filename", text, "a-very-long-filename-that-demonstrates-single-line-truncation-in-the-chip.md"),
                chip(
                    "opens-custom-detail",
                    text,
                    "Logs · 15 min",
                    opens = true,
                    note = "Android chips have no custom detail line; this chip opens on tap",
                ),
                chip("uploading-42", file, "build-output.zip", upload = OpenCodeAttachmentUploadState.InProgress(42)),
                chip("upload-starting", file, "recording.mov", upload = OpenCodeAttachmentUploadState.InProgress(null)),
                chip("locked", pdf, "product-brief.pdf", enabled = false),
                sent("sent-image", image, "session-screenshot.png"),
                sent("sent-pdf", pdf, "product-brief.pdf"),
                sent("sent-text", text, "notes.md"),
                sent("sent-file", file, "build-output.zip"),
            ),
    )
}

private fun imageGalleryCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        count: Int,
        tiles: Map<Int, CaptureTile> = emptyMap(),
    ) = ComponentCaptureState(id) { CaptureImageGallery(count, tiles) }
    val loading = CaptureTile.Loading
    val failed = CaptureTile.Failed
    return ComponentCaptureComponent(
        id = "image-gallery",
        states =
            listOf(
                state("1-image", 1),
                state("2-images", 2),
                state("3-images", 3),
                state("4-images", 4),
                state("6-images-3", 6),
                state("8-images-5", 8),
                state("loading", 2, mapOf(0 to loading, 1 to loading)),
                state("single-loading", 1, mapOf(0 to loading)),
                state("failed-single", 1, mapOf(0 to failed)),
                state("loaded-loading-failed", 3, mapOf(1 to loading, 2 to failed)),
                state("overflow-tile-loading", 6, mapOf(3 to loading)),
                state("overflow-tile-failed", 6, mapOf(3 to failed)),
            ),
    )
}

private fun messageListCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        autoFollow: Boolean = false,
        empty: Boolean = false,
        press: CapturePress? = null,
    ) = ComponentCaptureState(id, press = press, note = "The gallery preview, with its add, remove and resize controls") {
        MessageListGalleryPreview(
            messages = if (empty) emptyList() else MessageListGalleryMocks.messages,
            autoFollowEnabled = autoFollow,
            onAddMessage = {},
            onRemoveLastMessage = {},
            onResizeLastMessage = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
    return ComponentCaptureComponent(
        id = "message-list",
        height = 460.dp,
        states =
            listOf(
                state("pinned-to-end"),
                state("auto-follow-armed", autoFollow = true),
                state("scrolled-up-pill", press = CapturePress(gesture = CaptureGesture.ScrollBack)),
                state("empty", empty = true),
            ),
    )
}

// ---- Messages ----

private const val OUTGOING_METADATA = "Build · DeepSeek V4 Flash · 9:05 PM"

/** Yesterday at 9:05 PM, so the revealed time reads the same on every run of a day. */
private fun yesterdayEvening(): Long =
    LocalDate
        .now()
        .minusDays(1)
        .atTime(21, 5)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

private fun outgoingMessageCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        text: String = OutgoingMessageSample.Short.text,
        attachments: List<OpenCodeAttachmentPresentation> = emptyList(),
        content: (() -> List<OpenCodeOutgoingMessageContent>)? = null,
        steering: OpenCodeOutgoingMessageSteering? = null,
        goal: Boolean = false,
        sentAt: Long? = null,
        press: CapturePress? = null,
        markdown: Boolean = false,
    ) = ComponentCaptureState(id, press = press) {
        val request = RenderedMarkdownGalleryMocks.samples.first { it.id == "request" }
        OpenCodeOutgoingMessage(
            text = if (markdown) request.rawMarkdown else text,
            metadataLabel = OUTGOING_METADATA,
            sentAtEpochMs = sentAt,
            renderedMarkdown = if (markdown) request.document else null,
            attachments = attachments,
            content = content?.invoke(),
            steering = steering,
            label = if (goal) OpenCodeOutgoingMessageLabel("Goal", OpenCodeIcons.RadioButtonChecked) else null,
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val attachments = OutgoingMessageSample.Attachments.attachments
    return ComponentCaptureComponent(
        id = "outgoing-message",
        states =
            listOf(
                state("short"),
                state("long", text = OutgoingMessageSample.Long.text),
                state("command-token", text = "/review the current branch"),
                state("attachments-only", text = "", attachments = attachments),
                state("text-and-attachments", text = "Here are the requirements.", attachments = attachments),
                state("image-gallery", content = {
                    listOf(
                        OpenCodeOutgoingMessageContent.ImageGallery(id = "sent", images = SnapshotEvidenceGalleryMocks.images(3)),
                        OpenCodeOutgoingMessageContent.Text(OutgoingMessageSample.Short.text),
                    )
                }),
                state("rendered-markdown", markdown = true),
                state("cut-text", content = {
                    listOf(
                        OpenCodeOutgoingMessageContent.Text(
                            text = TruncationRowGallerySample,
                            limit = OpenCodeOutgoingMessageLimit(TruncationRowGallerySample.length, 588_638, onShowFull = {}),
                        ),
                    )
                }),
                state("cut-nothing-shown", content = {
                    listOf(
                        OpenCodeOutgoingMessageContent.Text(
                            text = "",
                            limit = OpenCodeOutgoingMessageLimit(0, 588_638, onShowFull = {}),
                        ),
                    )
                }),
                state("steering-delivered", "Use the staging bucket instead.", steering = OpenCodeOutgoingMessageSteering.Delivered(true)),
                state("steering-pending", "And skip the migration step.", steering = OpenCodeOutgoingMessageSteering.Pending(true)),
                state("pending-no-label", "And skip the migration step.", steering = OpenCodeOutgoingMessageSteering.Pending(false)),
                state("goal-kickoff", "Match the layout in the screenshot", goal = true),
                state(
                    "time-from-another-day",
                    "Drag this message left",
                    sentAt = yesterdayEvening(),
                    // On the bubble, which hugs the end edge.
                    press = CapturePress(fromEnd = 60.dp, gesture = CaptureGesture.DragStartHold),
                ),
            ),
    )
}

private fun truncationRowCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        shown: Int,
        total: Int,
        pressed: Boolean = false,
    ) = ComponentCaptureState(id, press = if (pressed) CapturePress() else null) {
        OpenCodeTruncationRow(variant = OpenCodeTruncationRowVariant.FullMessage(shown, total), onClick = {})
    }
    return ComponentCaptureComponent(
        id = "truncation-row",
        states =
            listOf(
                state("default", 1_000, 588_638),
                state("pressed", 1_000, 588_638, pressed = true),
                state("small-counts", 987, 1_412),
                state("nothing-shown", 0, 588_638),
            ),
    )
}

private fun assistantMessageCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        sample: AssistantMessageSample? = null,
        markdownId: String? = null,
        plain: Boolean = false,
    ) = ComponentCaptureState(id) {
        val markdown = markdownId?.let { mid -> RenderedMarkdownGalleryMocks.samples.first { it.id == mid } }
        OpenCodeAssistantMessage(
            text = markdown?.rawMarkdown ?: sample?.text.orEmpty(),
            renderedMarkdown = markdown?.document,
            presentation = if (plain) OpenCodeAssistantMessagePresentation.Plain else OpenCodeAssistantMessagePresentation.Bubble,
        )
    }
    return ComponentCaptureComponent(
        id = "assistant-message",
        states =
            listOf(
                state("short", AssistantMessageSample.Short),
                state("long", AssistantMessageSample.Long),
                state("empty", AssistantMessageSample.Empty),
                state("markdown", markdownId = "assistant"),
                state("code", markdownId = "code"),
                state("plain-short", AssistantMessageSample.Short, plain = true),
                state("plain-long", AssistantMessageSample.Long, plain = true),
                state("plain-markdown", markdownId = "assistant", plain = true),
            ),
    )
}

private fun renderedMarkdownCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        sampleId: String,
    ) = ComponentCaptureState(id) {
        val sample = RenderedMarkdownGalleryMocks.samples.first { it.id == sampleId }
        if (sample.usesOutgoingMessage) {
            OpenCodeOutgoingMessage(
                text = sample.rawMarkdown,
                renderedMarkdown = sample.document,
                metadataLabel = "Build · Claude Sonnet · just now",
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OpenCodeRenderedMarkdown(document = sample.document, variant = sample.variant, modifier = Modifier.fillMaxWidth())
        }
    }
    val noSample = "The Android gallery has no rendered markdown sample for this state"
    return ComponentCaptureComponent(
        id = "rendered-markdown",
        states =
            listOf(
                state("assistant", "assistant"),
                state("request", "request"),
                state("code", "code"),
                state("syntax-roles", "syntax-roles"),
                state("languages", "languages"),
                state("rich", "rich"),
                state("table", "table"),
                state("diff", "diff"),
            ),
        skipped = listOf(ComponentCaptureSkip("headings-and-inline", noSample), ComponentCaptureSkip("role-to-token-map", noSample)),
    )
}

// ---- Tools ----

private fun toolCallCardCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        status: OpenCodeToolCallCardStatus,
        title: String,
        subtitle: String? = null,
        additions: Int? = null,
        deletions: Int? = null,
        errorText: String? = null,
        note: String? = null,
        tappable: Boolean = false,
        images: Int = 0,
    ) = ComponentCaptureState(id, note = if (images > 0) TOOL_IMAGES_NOTE else null) {
        val card: @Composable () -> Unit = {
            OpenCodeToolCallCard(
                status = status,
                title = title,
                subtitle = subtitle,
                additions = additions,
                deletions = deletions,
                errorText = errorText,
                noteText = note,
                onClick = if (tappable) ({}) else null,
            )
        }
        if (images > 0) WithToolImages(images, card) else card()
    }
    val completed = OpenCodeToolCallCardStatus.Completed
    val interrupted = OpenCodeToolCallCardStatus.Interrupted
    return ComponentCaptureComponent(
        id = "tool-call-card",
        states =
            listOf(
                state("pending", OpenCodeToolCallCardStatus.Pending, "Called bash", "npm test"),
                state("running", OpenCodeToolCallCardStatus.Running, "Called task", "Explore codebase"),
                state("completed", completed, "Called edit", "OpenCodeListItem.swift", 12, 3),
                state("tappable", completed, "Called edit", "OpenCodeListItem.swift", 12, 3, tappable = true),
                state("title-only", completed, "Called skill"),
                state("error", OpenCodeToolCallCardStatus.Error, "Called webfetch", errorText = "Request failed with status 403"),
                state("interrupted", interrupted, "Called shell", "./deploy.sh", errorText = "Stopped"),
                state(
                    "declined-with-note",
                    interrupted,
                    "Called shell",
                    "npm test",
                    errorText = "Declined",
                    note = "Your note: Run the tests with --watch=false instead",
                ),
                state("with-tool-images", completed, "Called screenshot", "Home screen", images = 2),
            ),
    )
}

private fun toolChipCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        variant: ToolChipGalleryVariant = ToolChipGalleryVariant.Command,
        status: OpenCodeToolChipStatus = OpenCodeToolChipStatus.Completed,
        trailing: Boolean = false,
        images: Int = 0,
    ) = ComponentCaptureState(id, note = if (images > 0) TOOL_IMAGES_NOTE else null) {
        val chip: @Composable () -> Unit = {
            OpenCodeToolChip(variant = variant.toVariant(), status = status, trailingText = if (trailing) "42s" else null, onClick = {})
        }
        if (images > 0) WithToolImages(images, chip) else chip()
    }
    return ComponentCaptureComponent(
        id = "tool-chip",
        states =
            listOf(
                state("file", ToolChipGalleryVariant.File),
                state("edit", ToolChipGalleryVariant.Change),
                state("command"),
                state("agent", ToolChipGalleryVariant.Agent),
                state("skill", ToolChipGalleryVariant.Skill),
                state("explore", ToolChipGalleryVariant.Explore),
                state("more", ToolChipGalleryVariant.More),
                state("running", status = OpenCodeToolChipStatus.Running),
                state("pending", status = OpenCodeToolChipStatus.Pending),
                state("error", status = OpenCodeToolChipStatus.Error),
                state("interrupted", status = OpenCodeToolChipStatus.Interrupted),
                state("trailing-text", trailing = true),
                state("with-tool-images", images = 3),
            ),
    )
}

private fun messageNoteCaptures() =
    ComponentCaptureComponent(
        id = "message-note",
        states =
            listOf(
                ComponentCaptureState("truncated") { OpenCodeMessageNote(OpenCodeMessageNoteVariant.Truncated) },
                ComponentCaptureState("interrupted") { OpenCodeMessageNote(OpenCodeMessageNoteVariant.Interrupted) },
            ),
    )

private fun timelineNoticeCaptures(): ComponentCaptureComponent {
    val movePath = "/Users/example/project/.worktrees/feature-702-v2-timeline-notices"

    fun state(
        id: String,
        label: String,
        value: String? = null,
        move: Boolean = false,
        expanded: Boolean = false,
    ) = ComponentCaptureState(id) {
        OpenCodeTimelineNotice(
            label = label,
            value = value,
            variant = if (move) OpenCodeTimelineNoticeVariant.Move else OpenCodeTimelineNoticeVariant.Change,
            state = if (expanded) OpenCodeTimelineNoticeState.Expanded else OpenCodeTimelineNoticeState.Collapsed,
            onToggle = if (value != null) ({}) else null,
        )
    }
    val goalRow = "Android draws the Goal set row in Session Chat, not as a Timeline Notice variant"
    return ComponentCaptureComponent(
        id = "timeline-notice",
        states =
            listOf(
                state("moved", "Moved to", movePath, move = true),
                state("agent", "Agent changed", "Build → Plan"),
                state("model", "Switched to", "Claude Opus 5.5"),
                state("variant", "Switched variant to", "high"),
                state("variant-default", "Switched variant to Default"),
                state("instructions", "Instructions updated", "AGENTS.md, packages/app/AGENTS.md, ~/.config/opencode/AGENTS.md"),
                state("restart", "Continuing after restart"),
                state("expanded", "Moved to", movePath, move = true, expanded = true),
            ),
        skipped = listOf(ComponentCaptureSkip("goal-set", goalRow), ComponentCaptureSkip("goal-ended", goalRow)),
    )
}

private fun childSessionRowCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        status: OpenCodeChildSessionRowStatus? = null,
        title: String = "explore",
        subtitle: String = "Standards review of OPE-144",
        elapsed: String? = "1:04",
        caption: String? = null,
        tappable: Boolean = true,
    ) = ComponentCaptureState(id) {
        OpenCodeChildSessionRow(
            title = title,
            caption = caption,
            subtitle = subtitle,
            elapsed = elapsed,
            status = status,
            onClick = if (tappable) ({}) else null,
        )
    }
    return ComponentCaptureComponent(
        id = "child-session-row",
        states =
            listOf(
                state("default"),
                state("background", subtitle = "Find auth bugs", elapsed = "0:12", caption = "background"),
                state("running", OpenCodeChildSessionRowStatus.Running),
                state("waiting-for-input", OpenCodeChildSessionRowStatus.WaitingForInput),
                state("done", OpenCodeChildSessionRowStatus.Done),
                state("failed", OpenCodeChildSessionRowStatus.Failed),
                state("stopped", OpenCodeChildSessionRowStatus.Stopped),
                state("display-only", title = "Task", subtitle = "Investigate flaky tests", elapsed = null, tappable = false),
            ),
    )
}

private fun taskRowCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        taskState: OpenCodeTaskRowState,
        detail: Boolean = true,
        tappable: Boolean = true,
    ) = ComponentCaptureState(id) {
        val sample = taskRowGallerySamples.first { it.state == taskState }
        OpenCodeTaskRow(
            title = sample.title,
            state = sample.state,
            tag = sample.tag,
            detail = sample.detail.takeIf { detail },
            detailCaption = sample.detailCaption,
            time = sample.time,
            onClick = if (tappable) ({}) else null,
        )
    }
    return ComponentCaptureComponent(
        id = "task-row",
        states =
            listOf(
                state("running", OpenCodeTaskRowState.Running),
                state("waiting-for-input", OpenCodeTaskRowState.WaitingForInput),
                state("done", OpenCodeTaskRowState.Done),
                state("failed", OpenCodeTaskRowState.Failed),
                state("stopped", OpenCodeTaskRowState.Stopped),
                state("no-detail", OpenCodeTaskRowState.Running, detail = false),
                state("display-only", OpenCodeTaskRowState.Done, tappable = false),
            ),
    )
}

private fun shellRowCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        command: String = SHELL_ROW_GALLERY_SHORT_COMMAND,
        description: Boolean = true,
        elapsed: Boolean = true,
        tappable: Boolean = true,
    ) = ComponentCaptureState(id) {
        OpenCodeShellRow(
            command = command,
            description = if (description) "Run the unit tests" else null,
            elapsed = if (elapsed) "1:12" else null,
            onClick = if (tappable) ({}) else null,
        )
    }
    return ComponentCaptureComponent(
        id = "shell-row",
        states =
            listOf(
                state("running"),
                state("long-command", SHELL_ROW_GALLERY_LONG_COMMAND, description = false),
                state("multi-line-command", "npm run build\nnpm test"),
                state("no-description", description = false),
                state("no-elapsed", elapsed = false),
                state("display-only", description = false, elapsed = false, tappable = false),
            ),
    )
}

// ---- Activity ----

private fun stepRailCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        live: Boolean,
        thinking: Boolean = false,
        images: Int = 0,
        note: String? = null,
        steps: (Long) -> List<OpenCodeStepRailItem>,
    ) = ComponentCaptureState(id, note = note ?: if (images > 0) TOOL_IMAGES_NOTE else null) {
        val nowMs = remember { System.currentTimeMillis() }
        val rail: @Composable () -> Unit = {
            OpenCodeStepRail(
                steps = steps(nowMs),
                isLive = live,
                thinking = thinking,
                onStepTap = {},
                onFoldTap = {},
                onChildSessionTap = {},
            )
        }
        if (images > 0) WithToolImages(images, rail) else rail()
    }
    val completed = OpenCodeStepRailStatus.Completed
    val fromHtml = "Steps built from the HTML gallery's sample; the Android gallery has no such sample"
    return ComponentCaptureComponent(
        id = "step-rail",
        states =
            listOf(
                state("one-running", live = true) { OpenCodeStepRailSamples.liveOneRunning(it) },
                state("parallel-child", live = true) { OpenCodeStepRailSamples.liveParallelWithChild(it) },
                state("folded-failed", live = true, thinking = true) { OpenCodeStepRailSamples.foldedWithFailure },
                state("finished", live = false) { OpenCodeStepRailSamples.finished },
                state("declined-with-note", live = false, note = "$fromHtml. Android steps have no note line") {
                    listOf(
                        OpenCodeStepRailItem.Step(
                            id = "a",
                            title = "Ran npm test",
                            detail = "Declined",
                            status = completed,
                            interrupted = true,
                        ),
                        OpenCodeStepRailItem.Step(id = "b", title = "Thought · 2.1s", status = completed),
                    )
                },
                state("pending", live = true, note = fromHtml) { now ->
                    listOf(
                        OpenCodeStepRailItem.Step(id = "a", title = "Package.swift", status = completed),
                        OpenCodeStepRailItem.Step(
                            id = "b",
                            title = "Running npm test",
                            status = OpenCodeStepRailStatus.Pending,
                            startedAtMs = now - 1_000,
                        ),
                    )
                },
                state("failed-child", live = false, note = fromHtml) {
                    listOf(
                        OpenCodeStepRailItem.Step(id = "a", title = "Package.swift", status = completed),
                        OpenCodeStepRailItem.ChildSession(
                            id = "b",
                            agent = "explore",
                            status = OpenCodeStepRailStatus.Failed,
                            startedAtMs = 0L,
                            endedAtMs = 18_000L,
                            detail = "Rate limited",
                        ),
                    )
                },
                state("with-tool-images", live = false, images = 2) { OpenCodeStepRailSamples.finished },
            ),
    )
}

private fun activityCardCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        images: Int = 0,
        note: String? = null,
        card: @Composable (nowMs: Long) -> Unit,
    ) = ComponentCaptureState(id, note = note ?: if (images > 0) TOOL_IMAGES_NOTE else null) {
        val nowMs = remember { System.currentTimeMillis() }
        if (images > 0) WithToolImages(images) { card(nowMs) } else card(nowMs)
    }

    @Composable
    fun live(
        nowMs: Long,
        startedAgoMs: Long,
        summary: String,
        twoAgents: Boolean = false,
        current: OpenCodeActivityCurrentRow?,
        idleText: String? = null,
    ) = OpenCodeActivityCard(
        state = OpenCodeActivityCardState.Live(elapsedStartMs = nowMs - startedAgoMs),
        summary = summary,
        agents = if (twoAgents) OpenCodeActivityCardSamples.twoAgents(nowMs) else emptyList(),
        current = current,
        idleText = idleText,
        onAgentTap = {},
        onOpen = {},
    )

    @Composable
    fun settled(
        status: OpenCodeActivityStatus,
        steps: Int,
        summary: String,
        agents: Boolean = false,
    ) = OpenCodeActivityCard(
        state = OpenCodeActivityCardState.Settled(worstStatus = status, stepCount = steps),
        summary = summary,
        agents = if (agents) OpenCodeActivityCardSamples.settledAgents else emptyList(),
        current = null,
        idleText = null,
        onAgentTap = {},
        onOpen = {},
    )
    val samples = OpenCodeActivityCardSamples
    return ComponentCaptureComponent(
        id = "activity-card",
        states =
            listOf(
                state("one-running-tool") { live(it, 12_000, "1 step · 1 command", current = samples.oneTool(it)) },
                state("footer-crossfade", note = "The footer crossfade is animated; this is one frame") {
                    live(it, 12_000, "1 step · 1 command", current = samples.oneTool(it))
                },
                state("two-agents-1") {
                    live(it, 64_000, samples.LIVE_SUMMARY, twoAgents = true, current = samples.editPlusOne(it))
                },
                state("last-tool-just-finished") { live(it, 9_000, "3 steps · 2 reads · 1 search", current = samples.justFinished) },
                state("waiting-for-agents") {
                    live(it, 40_000, "4 steps · 2 reads · 2 agents", twoAgents = true, current = null, idleText = "Waiting for agents")
                },
                state("settled") { settled(OpenCodeActivityStatus.Completed, 6, samples.SETTLED_NAMES) },
                state("settled-with-agents") { settled(OpenCodeActivityStatus.Completed, 4, samples.SETTLED_AGENT_NAMES, agents = true) },
                state("settled-failed") { settled(OpenCodeActivityStatus.Failed, 7, samples.SETTLED_FAILED_NAMES) },
                state("settled-stopped") { settled(OpenCodeActivityStatus.Interrupted, 6, samples.SETTLED_NAMES) },
                state("with-tool-images", images = 4) { settled(OpenCodeActivityStatus.Completed, 6, samples.SETTLED_NAMES) },
            ),
    )
}

private fun workingIndicatorCaptures(): ComponentCaptureComponent {
    fun row(
        id: String,
        label: String,
        state: OpenCodeChatActivityState,
        fading: Boolean = false,
        note: String? = null,
    ) = ComponentCaptureState(id, note = note) { OpenCodeWorkingIndicator(label = label, state = state, fading = fading) }

    fun pill(
        id: String,
        phase: OpenCodeTurnStatusPillPhase,
        compact: Boolean = false,
        note: String? = null,
    ) = ComponentCaptureState(id, note = note) { OpenCodeTurnStatusPill(phase = phase, compact = compact) }
    val working = OpenCodeTurnStatusPillPhase.Working("Working")
    val lifecycle = "An animated lifecycle; this is its first frame"
    return ComponentCaptureComponent(
        id = "working-indicator",
        states =
            listOf(
                row("working", "Working", OpenCodeChatActivityState.Working),
                row("idle", "Idle", OpenCodeChatActivityState.Idle),
                row("expanded", "Retrying", OpenCodeChatActivityState.Expanded),
                row("fading", "Idle", OpenCodeChatActivityState.Idle, fading = true),
                row("collapsed", "Idle", OpenCodeChatActivityState.Collapsed),
                row("working-to-idle", "Working", OpenCodeChatActivityState.Working, note = lifecycle),
                pill("turn-pill-working-to-done", working, note = lifecycle),
                pill("turn-pill-working", working),
                pill("turn-pill-working-compact", working, compact = true),
                pill("turn-pill-compacting", OpenCodeTurnStatusPillPhase.Working("Compacting…")),
                pill(
                    "turn-pill-review-permission",
                    OpenCodeTurnStatusPillPhase.Attention("Review permission", OpenCodeTurnStatusAttentionMark.Reopen),
                ),
                pill(
                    "turn-pill-waiting-for-you",
                    OpenCodeTurnStatusPillPhase.Attention("Waiting for you", OpenCodeTurnStatusAttentionMark.PulsingDot),
                ),
                pill("turn-pill-request-failed", OpenCodeTurnStatusPillPhase.Failed("Request failed")),
                pill("turn-pill-done", OpenCodeTurnStatusPillPhase.Done),
                pill("turn-pill-done-compact", OpenCodeTurnStatusPillPhase.Done, compact = true),
            ),
    )
}

private fun reasoningRowCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        state: OpenCodeChatActivityState,
        duration: String? = null,
        tappable: Boolean = false,
    ) = ComponentCaptureState(id) {
        OpenCodeReasoningRow(state = state, durationLabel = duration, onClick = if (tappable) ({}) else null)
    }
    return ComponentCaptureComponent(
        id = "reasoning-row",
        states =
            listOf(
                state("working", OpenCodeChatActivityState.Working, "1.2s"),
                state("expanded-with-duration", OpenCodeChatActivityState.Expanded, "1.2s", tappable = true),
                state("expanded", OpenCodeChatActivityState.Expanded, tappable = true),
                state("idle", OpenCodeChatActivityState.Idle, "1.2s"),
                state("collapsed", OpenCodeChatActivityState.Collapsed, "1.2s"),
            ),
    )
}
