package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.command.ComposerShellCommand
import dev.opencode.mobile.ui.components.OpenCodeAttachmentPresentation
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeSessionComposer
import dev.opencode.mobile.ui.components.OpenCodeSessionComposerAction
import dev.opencode.mobile.ui.components.OpenCodeSessionComposerState
import dev.opencode.mobile.ui.components.rememberOpenCodeSessionComposerFocus

private enum class SessionComposerGalleryScenario(
    val label: String,
) {
    Empty("Empty"),
    Prompt("Prompt"),
    Mentions("Mentions"),
    Active("Active"),
    Attachments("Attachments"),
    Revert("Revert"),
    Shell("Shell"),
    SideQuestion("/btw"),
    ChildLockout("Child"),
    Permission("Permission"),
    PermissionEdit("Edit"),
    Queued("Queued"),
    Submitting("Submitting"),
    Error("Error"),
    Question("Question"),
    QuestionWizard("Wizard"),
    MultiSelect("Multi-select"),
    MultiSelectBounded("Up to 2"),
    Custom("Custom"),
    QuestionSubmitting("Q Submit"),
    QuestionError("Q Error"),
}

private const val MentionGalleryText = "Ask @explore about this"
private val MentionGalleryRanges = listOf(4 until 12)

private val SampleQuestionOptions =
    listOf(
        OpenCodeSessionComposerState.QuestionOption(
            label = "Minimal patch",
            description = "Touch only the failing path",
        ),
        OpenCodeSessionComposerState.QuestionOption(
            label = "Broader refactor",
            description = "Clean up nearby callers too",
        ),
        OpenCodeSessionComposerState.QuestionOption(
            label = "Investigate first",
            description = "Gather more evidence before changing code",
        ),
    )

@Composable
fun SessionComposerGalleryScreen(modifier: Modifier = Modifier) {
    var scenarioName by rememberSaveable { mutableStateOf(SessionComposerGalleryScenario.Empty.name) }
    var draftText by rememberSaveable { mutableStateOf("Summarize the current changes") }
    var mentionText by rememberSaveable { mutableStateOf(MentionGalleryText) }
    var mentionsCleared by rememberSaveable { mutableStateOf(false) }
    var shellText by rememberSaveable { mutableStateOf("!git status") }
    var sideQuestionText by rememberSaveable { mutableStateOf("What did we change so far?") }
    var sideQuestionMode by rememberSaveable { mutableStateOf(true) }
    var lastAction by rememberSaveable { mutableStateOf("None") }
    val composerFocus = rememberOpenCodeSessionComposerFocus()
    var selectedLabels by rememberSaveable { mutableStateOf(listOf("Minimal patch")) }
    var customSelected by rememberSaveable { mutableStateOf(false) }
    var customText by rememberSaveable { mutableStateOf("") }
    var questionIndex by rememberSaveable { mutableIntStateOf(0) }
    val scenario =
        runCatching { SessionComposerGalleryScenario.valueOf(scenarioName) }
            .getOrDefault(SessionComposerGalleryScenario.Empty)
    val questionAnswered =
        if (customSelected) {
            customText.isNotBlank()
        } else {
            selectedLabels.isNotEmpty()
        }
    val state =
        when (scenario) {
            SessionComposerGalleryScenario.Empty -> promptState(text = "")
            SessionComposerGalleryScenario.Prompt -> promptState(text = draftText)
            SessionComposerGalleryScenario.Mentions ->
                promptState(
                    text = mentionText,
                    mentionRanges = if (mentionsCleared) emptyList() else MentionGalleryRanges,
                )
            SessionComposerGalleryScenario.Active ->
                promptState(
                    text = draftText,
                    showStop = true,
                )
            SessionComposerGalleryScenario.Attachments ->
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
            SessionComposerGalleryScenario.Revert ->
                promptState(
                    text = "Add a dark mode toggle to Settings",
                    showPendingRevert = true,
                )
            SessionComposerGalleryScenario.Shell ->
                promptState(text = shellText, shellMode = ComposerShellCommand.isShellMode(shellText))
            SessionComposerGalleryScenario.SideQuestion ->
                promptState(text = sideQuestionText, showStop = true, sideQuestionMode = sideQuestionMode)
            SessionComposerGalleryScenario.ChildLockout ->
                OpenCodeSessionComposerState.ChildLockout(
                    selectedModelLabel = "Claude Sonnet 4",
                    selectedVariantLabel = "High",
                    showVariant = true,
                )
            SessionComposerGalleryScenario.Permission -> permissionState()
            SessionComposerGalleryScenario.PermissionEdit -> permissionState(edit = true)
            SessionComposerGalleryScenario.Queued -> permissionState(queueCount = 3)
            SessionComposerGalleryScenario.Submitting -> permissionState(submitting = true)
            SessionComposerGalleryScenario.Error -> permissionState(errorMessage = "The permission response failed. Try again.")
            SessionComposerGalleryScenario.Question ->
                questionState(
                    questionIndex = 0,
                    questionCount = 1,
                    selectedLabels = selectedLabels,
                    customSelected = customSelected,
                    customText = customText,
                    submitEnabled = questionAnswered,
                )
            SessionComposerGalleryScenario.QuestionWizard ->
                questionState(
                    questionIndex = questionIndex,
                    questionCount = 2,
                    header = if (questionIndex == 0) "Approach" else "Tests",
                    question =
                        if (questionIndex == 0) {
                            "How should we implement this change?"
                        } else {
                            "Which tests should we prioritize?"
                        },
                    selectedLabels = selectedLabels,
                    customSelected = customSelected,
                    customText = customText,
                    backEnabled = questionIndex > 0,
                    nextEnabled = questionAnswered && questionIndex < 1,
                    submitEnabled = questionAnswered && questionIndex == 1,
                )
            SessionComposerGalleryScenario.MultiSelect ->
                questionState(
                    questionIndex = 0,
                    questionCount = 1,
                    multiple = true,
                    selectedLabels = selectedLabels,
                    customAllowed = false,
                    submitEnabled = selectedLabels.isNotEmpty(),
                )
            SessionComposerGalleryScenario.MultiSelectBounded ->
                questionState(
                    questionIndex = 0,
                    questionCount = 1,
                    multiple = true,
                    maxSelections = 2,
                    selectedLabels = selectedLabels.take(2),
                    customAllowed = false,
                    submitEnabled = selectedLabels.isNotEmpty(),
                )
            SessionComposerGalleryScenario.Custom ->
                questionState(
                    questionIndex = 0,
                    questionCount = 1,
                    selectedLabels = selectedLabels,
                    customSelected = true,
                    customText = customText.ifBlank { "Ship a focused fix tonight" },
                    submitEnabled = true,
                )
            SessionComposerGalleryScenario.QuestionSubmitting ->
                questionState(
                    questionIndex = 0,
                    questionCount = 1,
                    selectedLabels = listOf("Minimal patch"),
                    submitEnabled = true,
                    submitting = true,
                )
            SessionComposerGalleryScenario.QuestionError ->
                questionState(
                    questionIndex = 0,
                    questionCount = 1,
                    selectedLabels = listOf("Minimal patch"),
                    submitEnabled = true,
                    errorMessage = "The question response failed. Try again.",
                )
        }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("State", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SessionComposerGalleryScenario.entries.forEach { option ->
                    OpenCodeChoiceBadge(
                        label = option.label,
                        selected = option == scenario,
                        onClick = {
                            scenarioName = option.name
                            when (option) {
                                SessionComposerGalleryScenario.MultiSelect -> {
                                    selectedLabels = listOf("Minimal patch", "Investigate first")
                                    customSelected = false
                                    customText = ""
                                }
                                SessionComposerGalleryScenario.Custom -> {
                                    selectedLabels = emptyList()
                                    customSelected = true
                                    customText = "Ship a focused fix tonight"
                                }
                                SessionComposerGalleryScenario.QuestionWizard -> {
                                    questionIndex = 0
                                    selectedLabels = listOf("Minimal patch")
                                    customSelected = false
                                    customText = ""
                                }
                                SessionComposerGalleryScenario.Question -> {
                                    selectedLabels = listOf("Minimal patch")
                                    customSelected = false
                                    customText = ""
                                }
                                else -> Unit
                            }
                        },
                    )
                }
            }
            Text(
                "Last action: $lastAction",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.CompactText("Focus input"),
                    contrast = OpenCodeButtonContrast.Low,
                    enabled = composerFocus.canRequestFocus,
                    onClick = { composerFocus.requestFocus() },
                )
                Text(
                    if (composerFocus.isFocused) "Input focused" else "Input not focused",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        OpenCodeSessionComposer(
            state = state,
            focus = composerFocus,
            onAction = { action ->
                when (action) {
                    is OpenCodeSessionComposerAction.TextChanged ->
                        when (scenario) {
                            SessionComposerGalleryScenario.Mentions -> {
                                mentionText = action.text
                                mentionsCleared = true
                            }
                            SessionComposerGalleryScenario.Shell -> shellText = action.text
                            SessionComposerGalleryScenario.SideQuestion -> sideQuestionText = action.text
                            else -> draftText = action.text
                        }
                    OpenCodeSessionComposerAction.ExitShellMode -> shellText = ComposerShellCommand.exitShellMode(shellText)
                    OpenCodeSessionComposerAction.ExitSideQuestionMode -> sideQuestionMode = false
                    is OpenCodeSessionComposerAction.ToggleQuestionOption -> {
                        selectedLabels =
                            if (scenario == SessionComposerGalleryScenario.MultiSelect) {
                                if (action.label in selectedLabels) {
                                    selectedLabels - action.label
                                } else {
                                    selectedLabels + action.label
                                }
                            } else {
                                listOf(action.label)
                            }
                        customSelected = false
                    }
                    is OpenCodeSessionComposerAction.ToggleQuestionCustom -> {
                        customSelected = !customSelected
                        if (customSelected) {
                            selectedLabels = emptyList()
                        }
                    }
                    is OpenCodeSessionComposerAction.QuestionCustomTextChanged -> customText = action.text
                    is OpenCodeSessionComposerAction.QuestionBack ->
                        questionIndex = (questionIndex - 1).coerceAtLeast(0)
                    is OpenCodeSessionComposerAction.QuestionNext ->
                        questionIndex = (questionIndex + 1).coerceAtMost(1)
                    else -> Unit
                }
                lastAction = action.label
            },
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

private fun promptState(
    text: String,
    mentionRanges: List<IntRange> = emptyList(),
    attachments: List<OpenCodeAttachmentPresentation> = emptyList(),
    showPendingRevert: Boolean = false,
    showStop: Boolean = false,
    shellMode: Boolean = false,
    sideQuestionMode: Boolean = false,
) = OpenCodeSessionComposerState.Prompt(
    text = text,
    mentionRanges = mentionRanges,
    attachments = attachments,
    showPendingRevert = showPendingRevert,
    selectedModelLabel = "Claude Sonnet 4",
    selectedModeLabel = "Build",
    selectedVariantLabel = "Default",
    showVariant = true,
    sendEnabled =
        if (shellMode) ComposerShellCommand.command(text) != null else text.isNotBlank() || attachments.isNotEmpty(),
    attachEnabled = !shellMode && !sideQuestionMode,
    showStop = showStop,
    stopEnabled = showStop,
    shellMode = shellMode,
    sideQuestionMode = sideQuestionMode,
)

private fun permissionState(
    queueCount: Int = 1,
    submitting: Boolean = false,
    errorMessage: String? = null,
    edit: Boolean = false,
) = OpenCodeSessionComposerState.Permission(
    requestId = "permission-gallery",
    sessionId = "session-gallery",
    permission = if (edit) "edit" else "bash",
    patterns = if (edit) listOf("README.md", "notes/todo.md") else listOf("git status --short", "git diff --stat"),
    files =
        if (edit) {
            listOf(
                OpenCodeSessionComposerState.PermissionFile("README.md", additions = 1, deletions = 1),
                OpenCodeSessionComposerState.PermissionFile("notes/todo.md", additions = 4, deletions = 0),
            )
        } else {
            emptyList()
        },
    alwaysPatterns = if (edit) listOf("*") else listOf("git status *", "git diff *"),
    queueCount = queueCount,
    submitting = submitting,
    errorMessage = errorMessage,
)

private fun questionState(
    questionIndex: Int,
    questionCount: Int,
    header: String = "Approach",
    question: String = "How should we implement this change?",
    multiple: Boolean = false,
    maxSelections: Int? = null,
    customAllowed: Boolean = true,
    selectedLabels: List<String> = emptyList(),
    customText: String = "",
    customSelected: Boolean = false,
    backEnabled: Boolean = false,
    nextEnabled: Boolean = false,
    submitEnabled: Boolean = false,
    submitting: Boolean = false,
    errorMessage: String? = null,
) = OpenCodeSessionComposerState.Question(
    requestId = "question-gallery",
    sessionId = "session-gallery",
    questionIndex = questionIndex,
    questionCount = questionCount,
    header = header,
    question = question,
    options = SampleQuestionOptions,
    multiple = multiple,
    maxSelections = maxSelections,
    customAllowed = customAllowed,
    selectedLabels = selectedLabels,
    customText = customText,
    customSelected = customSelected,
    backEnabled = backEnabled,
    nextEnabled = nextEnabled,
    submitEnabled = submitEnabled,
    submitting = submitting,
    errorMessage = errorMessage,
)

private val OpenCodeSessionComposerAction.label: String
    get() =
        when (this) {
            is OpenCodeSessionComposerAction.TextChanged -> "Text changed"
            OpenCodeSessionComposerAction.SelectModel -> "Select model"
            OpenCodeSessionComposerAction.SelectMode -> "Select mode"
            OpenCodeSessionComposerAction.SelectVariant -> "Select variant"
            OpenCodeSessionComposerAction.SelectRuntimeMode -> "Select runtime mode"
            OpenCodeSessionComposerAction.Attach -> "Attach"
            OpenCodeSessionComposerAction.OpenCommands -> "Open commands"
            is OpenCodeSessionComposerAction.RemoveAttachment -> "Remove attachment"
            is OpenCodeSessionComposerAction.PasteContent -> "Paste attachment"
            OpenCodeSessionComposerAction.CancelRevert -> "Cancel revert"
            OpenCodeSessionComposerAction.ExitShellMode -> "Exit shell mode"
            OpenCodeSessionComposerAction.ExitSideQuestionMode -> "Exit side question mode"
            OpenCodeSessionComposerAction.Send -> "Send"
            OpenCodeSessionComposerAction.ToggleFollowUp -> "Toggle follow-up"
            OpenCodeSessionComposerAction.Stop -> "Stop"
            is OpenCodeSessionComposerAction.AllowPermissionOnce -> "Allow once"
            is OpenCodeSessionComposerAction.DenyPermission -> "Deny"
            is OpenCodeSessionComposerAction.AlwaysAllowPermission -> "Always"
            is OpenCodeSessionComposerAction.HidePermission -> "Hide permission"
            is OpenCodeSessionComposerAction.ToggleQuestionOption -> "Toggle option"
            is OpenCodeSessionComposerAction.ToggleQuestionCustom -> "Toggle custom"
            is OpenCodeSessionComposerAction.QuestionCustomTextChanged -> "Custom text changed"
            is OpenCodeSessionComposerAction.QuestionBack -> "Back"
            is OpenCodeSessionComposerAction.QuestionNext -> "Next"
            is OpenCodeSessionComposerAction.SubmitQuestion -> "Submit"
            is OpenCodeSessionComposerAction.DismissQuestion -> "Dismiss"
            OpenCodeSessionComposerAction.BackToMainSession -> "Back to main session"
            OpenCodeSessionComposerAction.BeginDictation -> "Dictate"
            OpenCodeSessionComposerAction.DictationCancel -> "Cancel dictation"
            OpenCodeSessionComposerAction.DictationInsert -> "Insert transcript"
            OpenCodeSessionComposerAction.DictationOpenSettings -> "Open Settings"
            OpenCodeSessionComposerAction.DictationDownload -> "Download"
            OpenCodeSessionComposerAction.DictationRetry -> "Retry"
        }
