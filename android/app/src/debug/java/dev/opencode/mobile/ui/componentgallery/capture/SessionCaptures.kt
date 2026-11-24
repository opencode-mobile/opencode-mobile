package dev.opencode.mobile.ui.componentgallery.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.command.ComposerShellCommand
import dev.opencode.mobile.domain.featureflags.SessionComposerStyle
import dev.opencode.mobile.domain.preferences.HomeSessionRowStyle
import dev.opencode.mobile.domain.shell.AgentShell
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.componentgallery.components.DictationCardGallerySample
import dev.opencode.mobile.ui.componentgallery.components.DictationCardGalleryVariant
import dev.opencode.mobile.ui.componentgallery.components.DropOverlayGalleryImages
import dev.opencode.mobile.ui.componentgallery.components.DropOverlayGalleryMixed
import dev.opencode.mobile.ui.componentgallery.components.DropOverlayGalleryReject
import dev.opencode.mobile.ui.componentgallery.components.GALLERY_NEW_PROJECT_WORKTREE
import dev.opencode.mobile.ui.componentgallery.components.GalleryScopeLoadStep
import dev.opencode.mobile.ui.componentgallery.components.MENTION_GALLERY_TEXT
import dev.opencode.mobile.ui.componentgallery.components.MentionGalleryRanges
import dev.opencode.mobile.ui.componentgallery.components.galleryContent
import dev.opencode.mobile.ui.componentgallery.components.galleryShells
import dev.opencode.mobile.ui.componentgallery.components.gallerySubagents
import dev.opencode.mobile.ui.componentgallery.components.galleryTabsWithNewProject
import dev.opencode.mobile.ui.componentgallery.components.permissionState
import dev.opencode.mobile.ui.componentgallery.components.promptState
import dev.opencode.mobile.ui.componentgallery.components.questionState
import dev.opencode.mobile.ui.componentgallery.components.sessionListDockGalleryFilterTabs
import dev.opencode.mobile.ui.componentgallery.components.sessionListDockGalleryOneProjectFilterTabs
import dev.opencode.mobile.ui.componentgallery.components.sessionListGalleryFilterTabs
import dev.opencode.mobile.ui.componentgallery.components.sessionListGallerySamples
import dev.opencode.mobile.ui.components.OpenCodeAttachmentPresentation
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
import dev.opencode.mobile.ui.components.OpenCodeSessionBranchPill
import dev.opencode.mobile.ui.components.OpenCodeSessionComposer
import dev.opencode.mobile.ui.components.OpenCodeSessionComposerNote
import dev.opencode.mobile.ui.components.OpenCodeSessionComposerState
import dev.opencode.mobile.ui.components.OpenCodeSessionListDock
import dev.opencode.mobile.ui.components.OpenCodeSessionListSkeleton
import dev.opencode.mobile.ui.components.OpenCodeSessionListSkeletonRow
import dev.opencode.mobile.ui.components.OpenCodeSessionStatusBar
import dev.opencode.mobile.ui.components.OpenCodeSessionTodoItem
import dev.opencode.mobile.ui.components.OpenCodeSessionTodoItemStatus
import dev.opencode.mobile.ui.components.SessionBranchPillPreviewCase
import dev.opencode.mobile.ui.components.SessionList
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListScopeLoad
import dev.opencode.mobile.ui.sessionchat.SessionChatBackgroundWork
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagent
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus
import dev.opencode.mobile.ui.sessionchat.view.SessionChatBackgroundWorkPill

internal fun sessionCaptures(): List<ComponentCaptureComponent> =
    listOf(
        sessionListCaptures(),
        sessionListSkeletonCaptures(),
        sessionListDockCaptures(),
        sessionComposerCaptures(),
        dictationCardCaptures(),
        dropOverlayCaptures(),
        sessionBranchPillCaptures(),
        sessionStatusBarCaptures(),
        backgroundWorkPillCaptures(),
        sessionTodoItemCaptures(),
    )

private const val MOBILE_WORKTREE = "/Users/demo/opencode-mobile"

private fun sessionListCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        classic: Boolean = false,
        selected: String = SessionListFilterTab.ALL_ID,
        load: GalleryScopeLoadStep = GalleryScopeLoadStep.None,
        empty: Boolean = false,
        note: String? = null,
    ) = ComponentCaptureState(id, note = note) {
        SessionList(
            sessions = if (empty) emptyList() else sessionListGallerySamples,
            filterTabs = galleryTabsWithNewProject(base = sessionListGalleryFilterTabs, oneProject = false, load = load.load),
            selectedFilterId = selected,
            onOpenProjectPicker = {},
            onFilterSelected = {},
            onSessionSelected = {},
            onAddProject = {},
            onNewSession = {},
            onRetryScopeLoad = {},
            rowStyle = if (classic) HomeSessionRowStyle.Classic else HomeSessionRowStyle.Compact,
            modifier = Modifier.fillMaxSize(),
        )
    }
    return ComponentCaptureComponent(
        id = "session-list",
        height = 560.dp,
        states =
            listOf(
                state("compact"),
                state("classic", classic = true),
                state("project-filter", selected = MOBILE_WORKTREE),
                state(
                    "loading",
                    selected = GALLERY_NEW_PROJECT_WORKTREE,
                    load = GalleryScopeLoadStep.Loading,
                    note = "The gallery's just-added project on its first load",
                ),
                state("empty", selected = MOBILE_WORKTREE, empty = true),
            ),
    )
}

private fun sessionListSkeletonCaptures(): ComponentCaptureComponent {
    fun list(
        id: String,
        rows: Int = 6,
        accent: LetterAccent? = LetterAccent.Teal,
        slow: Boolean = false,
    ) = ComponentCaptureState(id) {
        OpenCodeSessionListSkeleton(
            rowCount = rows,
            accent = accent,
            title = "opencode-mobile",
            caption = if (slow) stringResource(SessionListScopeLoad.SLOW_CAPTION) else null,
        )
    }
    return ComponentCaptureComponent(
        id = "session-list-skeleton",
        states =
            listOf(
                list("list"),
                ComponentCaptureState("row") { OpenCodeSessionListSkeletonRow(accent = LetterAccent.Violet) },
                list("3-rows", rows = 3),
                list("neutral", accent = null),
                list("slow-caption", rows = 3, slow = true),
            ),
    )
}

private fun sessionListDockCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        tabs: List<SessionListFilterTab> = sessionListDockGalleryFilterTabs,
        selected: String = SessionListFilterTab.ALL_ID,
        load: GalleryScopeLoadStep = GalleryScopeLoadStep.None,
        oneProject: Boolean = false,
        error: Boolean = false,
    ) = ComponentCaptureState(id) {
        val filterTabs = galleryTabsWithNewProject(base = tabs, oneProject = oneProject, load = load.load)
        OpenCodeSessionListDock(
            filterTabs = filterTabs,
            selectedFilterId = if (load != GalleryScopeLoadStep.None) GALLERY_NEW_PROJECT_WORKTREE else selected,
            createError = if (error) "Could not create session. Try again." else null,
            onOpenProjectPicker = {},
            onFilterSelected = {},
            onAddProject = {},
            onNewSession = {},
            onNewSessionLongPress = if (oneProject) null else ({}),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val one = sessionListDockGalleryOneProjectFilterTabs
    return ComponentCaptureComponent(
        id = "session-list-dock",
        states =
            listOf(
                state("all-projects"),
                state("project", selected = MOBILE_WORKTREE),
                state("one-project", tabs = one, selected = one.first().id, oneProject = true),
                state("no-projects", tabs = listOf(SessionListFilterTab.all(projectCount = 0, hasSessions = false))),
                state("loading", load = GalleryScopeLoadStep.Loading),
                state("slow", load = GalleryScopeLoadStep.Slow),
                state("load-failed", load = GalleryScopeLoadStep.Failed),
                state("create-error", error = true),
            ),
    )
}

private fun dictationCardCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        variant: DictationCardGalleryVariant,
    ) = ComponentCaptureState(id) { DictationCardGallerySample(variant = variant, level = 0.4f) }
    return ComponentCaptureComponent(
        id = "dictation-card",
        states =
            listOf(
                state("listening", DictationCardGalleryVariant.Listening),
                state("finishing", DictationCardGalleryVariant.Finishing),
                state("stopped-android", DictationCardGalleryVariant.Stopped),
                state("permission-denied", DictationCardGalleryVariant.PermissionDenied),
                state("model-not-installed", DictationCardGalleryVariant.ModelNotInstalled),
                state("downloading", DictationCardGalleryVariant.ModelDownloading),
                state("download-starting", DictationCardGalleryVariant.ModelDownloadStarting),
                state("queued-android", DictationCardGalleryVariant.ModelDownloadQueued),
                state("download-failed", DictationCardGalleryVariant.ModelDownloadFailed),
                state("recognizer-error", DictationCardGalleryVariant.RecognizerError),
            ),
    )
}

private fun dropOverlayCaptures() =
    ComponentCaptureComponent(
        id = "drop-overlay",
        height = 320.dp,
        states =
            listOf(
                ComponentCaptureState("accept-images") { DropOverlayGalleryImages() },
                ComponentCaptureState("accept-mixed") { DropOverlayGalleryMixed() },
                ComponentCaptureState("reject") { DropOverlayGalleryReject() },
            ),
    )

private fun sessionBranchPillCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        sample: SessionBranchPillPreviewCase,
        open: Boolean = false,
    ) = ComponentCaptureState(
        id,
        press = if (open) CapturePress(gesture = CaptureGesture.Tap) else null,
        height = if (open) 480.dp else null,
        overlay = if (open) CaptureOverlay.Top else null,
        note = if (open) "Opened by tapping the pill" else null,
    ) {
        Box(Modifier.testTag(CAPTURE_PRESS_TAG)) {
            OpenCodeSessionBranchPill(summary = sample.summary, onViewAllChanges = {})
        }
    }
    return ComponentCaptureComponent(
        id = "session-branch-pill",
        states =
            listOf(
                state("changes", SessionBranchPillPreviewCase.Changes),
                state("no-changes", SessionBranchPillPreviewCase.NoChanges),
                state("worktree", SessionBranchPillPreviewCase.Worktree),
                state("many-files", SessionBranchPillPreviewCase.ManyFiles),
                state("popover", SessionBranchPillPreviewCase.Changes, open = true),
            ),
    )
}

private fun sessionStatusBarCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        fraction: Float?,
    ) = ComponentCaptureState(id) { OpenCodeSessionStatusBar(contextUsageFraction = fraction, onContextClick = {}) }
    return ComponentCaptureComponent(
        id = "session-status-bar",
        states =
            listOf(
                state("0", 0f),
                state("50", 0.5f),
                state("70-warning", 0.7f),
                state("85-error", 0.85f),
                state("unavailable", null),
            ),
    )
}

private fun backgroundWorkPillCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        compact: Boolean = false,
        work: (Long) -> Pair<List<SessionChatSubagent>, List<AgentShell>>,
    ) = ComponentCaptureState(id) {
        val nowMs = remember { System.currentTimeMillis() }
        val (subagents, shells) = remember(nowMs) { work(nowMs) }
        SessionChatBackgroundWork.pill(subagents, shells)?.let { pill ->
            SessionChatBackgroundWorkPill(pill = pill, compact = compact, onClick = {})
        }
    }

    fun oneWaiting(nowMs: Long) = gallerySubagents(needsInput = true, nowMs = nowMs).take(1)

    fun severalWaiting(nowMs: Long) =
        gallerySubagents(needsInput = true, nowMs = nowMs).mapIndexed { index, subagent ->
            if (index < 2) subagent.copy(status = SessionChatSubagentStatus.WaitingForInput) else subagent
        }
    return ComponentCaptureComponent(
        id = "background-work-pill",
        alignment = Alignment.Center,
        states =
            listOf(
                state("subagents") { gallerySubagents(false, it) to emptyList() },
                state("subagents-shells") { gallerySubagents(false, it) to galleryShells(it) },
                state("shells") { emptyList<SessionChatSubagent>() to galleryShells(it) },
                state("needs-input") { oneWaiting(it) to emptyList() },
                state("needs-input-running") { gallerySubagents(true, it) to emptyList() },
                state("several-need-input") { severalWaiting(it) to emptyList() },
                state("compact", compact = true) { gallerySubagents(false, it) to emptyList() },
                state("compact-needs-input", compact = true) { oneWaiting(it) to emptyList() },
            ),
    )
}

private fun sessionTodoItemCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        status: OpenCodeSessionTodoItemStatus,
        content: String = status.galleryContent,
    ) = ComponentCaptureState(id) {
        // On the raised surface the todo panel gives it, as the gallery shows it.
        OpenCodeSessionTodoItem(
            content = content,
            status = status,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(openCodeColor("surface-raised-base"), RoundedCornerShape(12.dp)),
        )
    }
    return ComponentCaptureComponent(
        id = "session-todo-item",
        states =
            listOf(
                state("pending", OpenCodeSessionTodoItemStatus.Pending),
                state("in-progress", OpenCodeSessionTodoItemStatus.InProgress),
                state("completed", OpenCodeSessionTodoItemStatus.Completed),
                state("cancelled", OpenCodeSessionTodoItemStatus.Cancelled),
                state(
                    "long",
                    OpenCodeSessionTodoItemStatus.InProgress,
                    "Trace every caller of the session store and list the ones that still read the legacy cache before the migration runs",
                ),
            ),
    )
}

private const val COMPOSER_DRAFT = "Summarize the current changes"
private const val COMPOSER_SELECTED_OPTION = "Minimal patch"

private fun sessionComposerCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        style: SessionComposerStyle = SessionComposerStyle.OutlineFade,
        interruptArmed: Boolean = false,
        composerState: () -> OpenCodeSessionComposerState,
    ) = ComponentCaptureState(id) {
        OpenCodeSessionComposer(state = composerState(), onAction = {}, style = style, interruptArmed = interruptArmed)
    }

    fun question(
        id: String,
        selected: List<String> = listOf(COMPOSER_SELECTED_OPTION),
        build: (List<String>) -> OpenCodeSessionComposerState.Question,
    ) = state(id) { build(selected) }
    return ComponentCaptureComponent(
        id = "session-composer",
        states =
            listOf(
                state("empty") { promptState(text = "") },
                state("prompt") { promptState(text = COMPOSER_DRAFT) },
                state("mentions") { promptState(text = MENTION_GALLERY_TEXT, mentionRanges = MentionGalleryRanges) },
                state("active") { promptState(text = COMPOSER_DRAFT, showStop = true) },
                state("attachments") {
                    promptState(
                        text = "",
                        attachments =
                            listOf(
                                OpenCodeAttachmentPresentation(
                                    id = "pdf",
                                    filename = "requirements.pdf",
                                    variant = OpenCodeAttachmentVariant.Pdf,
                                ),
                                OpenCodeAttachmentPresentation(
                                    id = "text",
                                    filename = "notes.md",
                                    variant = OpenCodeAttachmentVariant.Text,
                                ),
                            ),
                    )
                },
                state("revert") { promptState(text = "Add a dark mode toggle to Settings", showPendingRevert = true) },
                state("shell") { "!git status".let { promptState(text = it, shellMode = ComposerShellCommand.isShellMode(it)) } },
                state("btw") { promptState(text = "What did we change so far?", showStop = true, sideQuestionMode = true) },
                state("hint-android") {
                    promptState(text = "/goal ").copy(
                        sendEnabled = false,
                        argumentHint = "Describe what “done” looks like…",
                        placeholder = "Reply, then resume the goal…",
                    )
                },
                state("limit") {
                    promptState(text = "/goal " + "Keep the public API the same. ".repeat(4)).copy(
                        sendEnabled = false,
                        note = OpenCodeSessionComposerNote("4,312 / 4,000 characters · shorten the objective to send", isError = true),
                    )
                },
                state("child") {
                    OpenCodeSessionComposerState.ChildLockout(
                        selectedModelLabel = "Claude Sonnet 4",
                        selectedVariantLabel = "High",
                        showVariant = true,
                    )
                },
                state("permission") { permissionState() },
                state("edit") { permissionState(edit = true) },
                state("deny-note") {
                    permissionState(queueCount = 2, note = "Run the tests with --watch=false instead", otherPendingInSessionCount = 1)
                },
                state("queued") { permissionState(queueCount = 3) },
                state("subagent") {
                    permissionState(
                        queuePosition = 2,
                        queueCount = 3,
                        attribution =
                            OpenCodeSessionComposerState.RequestAttribution(
                                sessionId = "session-gallery-child",
                                letter = "G",
                                summary = "general · subagent wants to run: npm test",
                            ),
                    )
                },
                state("submitting") { permissionState(submitting = true) },
                state("error") { permissionState(errorMessage = "The permission response failed. Try again.") },
                question("question") {
                    questionState(questionIndex = 0, questionCount = 1, selectedLabels = it, submitEnabled = true)
                },
                question("wizard") {
                    questionState(questionIndex = 0, questionCount = 2, selectedLabels = it, nextEnabled = true)
                },
                question("wizard-last") {
                    questionState(
                        questionIndex = 1,
                        questionCount = 2,
                        header = "Tests",
                        question = "Which tests should we prioritize?",
                        selectedLabels = it,
                        backEnabled = true,
                        submitEnabled = true,
                    )
                },
                question("multi-select", selected = listOf(COMPOSER_SELECTED_OPTION, "Investigate first")) {
                    questionState(
                        questionIndex = 0,
                        questionCount = 1,
                        multiple = true,
                        customAllowed = false,
                        selectedLabels = it,
                        submitEnabled = true,
                    )
                },
                question("up-to-2", selected = listOf(COMPOSER_SELECTED_OPTION, "Investigate first")) {
                    questionState(
                        questionIndex = 0,
                        questionCount = 1,
                        multiple = true,
                        maxSelections = 2,
                        customAllowed = false,
                        selectedLabels = it,
                        submitEnabled = true,
                    )
                },
                question("custom", selected = emptyList()) {
                    questionState(
                        questionIndex = 0,
                        questionCount = 1,
                        selectedLabels = it,
                        customSelected = true,
                        customText = "Ship a focused fix tonight",
                        submitEnabled = true,
                    )
                },
                question("q-submit") {
                    questionState(questionIndex = 0, questionCount = 1, selectedLabels = it, submitEnabled = true, submitting = true)
                },
                question("q-error") {
                    questionState(
                        questionIndex = 0,
                        questionCount = 1,
                        selectedLabels = it,
                        submitEnabled = true,
                        errorMessage = "The question response failed. Try again.",
                    )
                },
                question("q-subagent") {
                    questionState(
                        questionIndex = 0,
                        questionCount = 1,
                        selectedLabels = it,
                        submitEnabled = true,
                        requestPosition = 1,
                        requestCount = 2,
                        attribution =
                            OpenCodeSessionComposerState.RequestAttribution(
                                sessionId = "session-gallery-child",
                                letter = "E",
                                summary = "explore · subagent asks",
                            ),
                    )
                },
                state("docked", style = SessionComposerStyle.Docked) { promptState(text = COMPOSER_DRAFT) },
                state("dictation-button") { promptState(text = "").copy(showDictationButton = true) },
                state("interrupt-hint", interruptArmed = true) { promptState(text = COMPOSER_DRAFT, showStop = true) },
                state("docked-permission", style = SessionComposerStyle.Docked) { permissionState() },
                state("docked-child-lockout", style = SessionComposerStyle.Docked) {
                    OpenCodeSessionComposerState.ChildLockout(
                        selectedModelLabel = "Claude Sonnet 4",
                        selectedVariantLabel = "High",
                        showVariant = true,
                    )
                },
            ),
    )
}
