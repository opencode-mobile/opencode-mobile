package dev.opencode.mobile.ui.worktree

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.worktree.CreateWorktreeRequest
import dev.opencode.mobile.domain.worktree.CreatedWorktree
import dev.opencode.mobile.domain.worktree.WorktreeCreateError
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import dev.opencode.mobile.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

/** Where the New worktree form was opened from, which decides what happens after a create. */
sealed class WorktreeCreateOrigin {
    /** Home's Run in list of project [projectKey]: start a new session in the new checkout. */
    data class RunIn(
        val projectKey: String,
    ) : WorktreeCreateOrigin()

    /** Session Chat's Move to worktree sheet: move session [sessionId] to the new checkout. */
    data class Move(
        val sessionId: String,
    ) : WorktreeCreateOrigin()
}

/** V2 branches the new checkout can start from. */
sealed class WorktreeBaseOptions {
    data object Loading : WorktreeBaseOptions()

    data class Loaded(
        val refs: List<String>,
    ) : WorktreeBaseOptions()

    data object Failed : WorktreeBaseOptions()
}

/** One row of the base list; [ref] null is the default (the source checkout's HEAD). */
data class WorktreeBaseRow(
    val ref: String?,
    /** The branch, verbatim; the default row is "<branch> (current)" or "Current checkout". */
    val title: UiText,
    val checked: Boolean,
) {
    val testTag: String get() = WorktreeCreateTags.baseOption(ref)
}

/** Stable test tags of New worktree (UI tests and real-app evidence); identical on iOS. */
object WorktreeCreateTags {
    const val RUN_IN_ROW = "newWorktreeRow"
    const val MOVE_ROW = "moveToNewWorktreeRow"
    const val NAME = "worktreeCreateName"
    const val BRANCH = "worktreeCreateBranch"
    const val BASE = "worktreeCreateBase"
    const val SUBMIT = "worktreeCreateSubmit"
    const val ERROR = "worktreeCreateError"
    const val BACK = "worktreeCreateBack"

    /** `worktreeCreateBaseOption:<ref>`; the default row has an empty ref. */
    fun baseOption(ref: String?): String = "worktreeCreateBaseOption:" + ref.orEmpty()
}

/** Name rules and the prefilled random name. */
object WorktreeName {
    const val MAX_LENGTH = 64
    private val allowed = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    val adjectives =
        "brave calm eager fuzzy gentle happy jolly lucky mighty nimble proud quiet swift tidy witty zesty".split(' ')
    val nouns =
        "otter falcon maple comet river panda cedar harbor lynx meadow pebble quartz robin sparrow tulip willow".split(' ')

    /** `<adjective>-<noun>`. */
    fun random(random: Random): String = adjectives[random.nextInt(adjectives.size)] + "-" + nouns[random.nextInt(nouns.size)]

    /** V1 branch: `opencode/<name>`, or `opencode/…` while the name is empty. A branch name, not app text. */
    fun v1Branch(name: String): String = "opencode/" + name.trim().ifEmpty { "\u2026" }

    /** The default base: the source checkout's branch marked current, else "Current checkout". */
    fun defaultBase(sourceBranch: String?): UiText =
        sourceBranch?.let { UiText.resource(R.string.worktree_current_branch, it) }
            ?: UiText.resource(R.string.worktree_current_checkout)

    /** Why [name] (trimmed) cannot be used, or null. */
    fun validate(name: String): UiText? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> UiText.resource(R.string.worktree_name_empty)
            !allowed.matches(trimmed) -> UiText.resource(R.string.worktree_name_chars)
            trimmed.length > MAX_LENGTH -> UiText.resource(R.string.worktree_name_long, MAX_LENGTH)
            else -> null
        }
    }
}

/** The New worktree form: one immutable state; [WorktreeCreateForm] owns the actions. */
data class WorktreeCreateFormState(
    val origin: WorktreeCreateOrigin,
    val protocol: ServerProtocol,
    val projectId: String?,
    /** Run in: the project's main checkout. Move: the session's current checkout. */
    val sourceDirectory: String,
    /** Branch of [sourceDirectory]; null when unknown or detached. */
    val sourceBranch: String?,
    val name: String,
    /** The name error shows after an edit or a submit attempt. */
    val nameErrorVisible: Boolean = false,
    /** V2 start ref; null is the default (the source checkout's HEAD). */
    val selectedBaseRef: String? = null,
    val baseOptions: WorktreeBaseOptions =
        if (protocol == ServerProtocol.V2) WorktreeBaseOptions.Loading else WorktreeBaseOptions.Loaded(emptyList()),
    /** V2 only: the sheet shows the base list instead of the form. */
    val basePickerVisible: Boolean = false,
    val submitting: Boolean = false,
    /** "Couldn't create the worktree: <server message>" after a failed create; the values stay. */
    val errorMessage: UiText? = null,
) {
    private val validationError: UiText? get() = WorktreeName.validate(name)

    val nameError: UiText? get() = if (nameErrorVisible) validationError else null

    val canSubmit: Boolean get() = validationError == null && !submitting

    /** V1: the branch name the server will use, verbatim. */
    val branchValue: UiText
        get() =
            if (protocol == ServerProtocol.V1) {
                UiText.verbatim(WorktreeName.v1Branch(name))
            } else {
                UiText.resource(R.string.worktree_v2_no_branch)
            }

    val branchNote: UiText
        get() =
            UiText.resource(
                if (protocol == ServerProtocol.V1) R.string.worktree_v1_branch_note else R.string.worktree_v2_branch_note,
            )

    /** V2 picks a base; v1 always creates from the project's checkout. */
    val baseSelectable: Boolean get() = protocol == ServerProtocol.V2

    val baseValue: UiText
        get() =
            if (protocol == ServerProtocol.V1) {
                sourceBranch?.let(UiText::verbatim) ?: UiText.resource(R.string.worktree_current_checkout)
            } else {
                selectedBaseRef?.let(UiText::verbatim) ?: WorktreeName.defaultBase(sourceBranch)
            }

    val baseNote: UiText
        get() = UiText.resource(if (protocol == ServerProtocol.V1) R.string.worktree_v1_base_note else R.string.worktree_base_pick_hint)

    /** The base list: the default first (checked while nothing else is picked), then the other branches. */
    val baseRows: List<WorktreeBaseRow>
        get() {
            val default = WorktreeBaseRow(null, WorktreeName.defaultBase(sourceBranch), checked = selectedBaseRef == null)
            val refs = (baseOptions as? WorktreeBaseOptions.Loaded)?.refs.orEmpty()
            return listOf(default) +
                refs.filter { it != sourceBranch }.map { ref -> WorktreeBaseRow(ref, UiText.verbatim(ref), checked = selectedBaseRef == ref) }
        }

    /** "Loading branches…" / "Couldn't load branches" under the default row while the list is not there. */
    val baseStatus: UiText?
        get() =
            when (baseOptions) {
                WorktreeBaseOptions.Loading -> UiText.resource(R.string.worktree_base_loading)
                WorktreeBaseOptions.Failed -> UiText.resource(R.string.worktree_base_failed)
                is WorktreeBaseOptions.Loaded -> null
            }

    val submitLabel: UiText
        get() =
            UiText.resource(
                when {
                    submitting -> R.string.worktree_submitting
                    origin is WorktreeCreateOrigin.Move -> R.string.worktree_submit_move
                    else -> R.string.worktree_submit_run_in
                },
            )

    val request: CreateWorktreeRequest
        get() =
            CreateWorktreeRequest(
                projectId = projectId,
                sourceDirectory = sourceDirectory,
                name = name.trim(),
                baseRef = if (protocol == ServerProtocol.V2) selectedBaseRef else null,
            )
}

/**
 * Screen Model of the New worktree form shared by Home's Run in sheet and Session Chat's Move
 * sheet. Null state means the form is closed (the sheet shows its list).
 */
class WorktreeCreateForm(
    private val repository: WorktreeRepository,
    private val scope: CoroutineScope,
    /** A create finished while the form was open: start or move there. */
    private val onCreated: (WorktreeCreateOrigin, CreatedWorktree) -> Unit,
    /** Any finished create, open or not: the inventory of the request's project changed. */
    private val onInventoryChanged: (WorktreeCreateOrigin, CreateWorktreeRequest) -> Unit = { _, _ -> },
    private val random: Random = Random.Default,
) {
    /** Where repository calls run; the caller's scope by default (the calls suspend, never block). */
    var ioDispatcher: CoroutineContext = EmptyCoroutineContext

    private val _state = MutableStateFlow<WorktreeCreateFormState?>(null)
    val state: StateFlow<WorktreeCreateFormState?> = _state.asStateFlow()

    private var baseJob: Job? = null

    fun open(
        origin: WorktreeCreateOrigin,
        protocol: ServerProtocol,
        projectId: String?,
        sourceDirectory: String,
        sourceBranch: String?,
    ) {
        baseJob?.cancel()
        val opened =
            WorktreeCreateFormState(
                origin = origin,
                protocol = protocol,
                projectId = projectId,
                sourceDirectory = sourceDirectory,
                sourceBranch = sourceBranch,
                name = WorktreeName.random(random),
            )
        _state.value = opened
        if (protocol == ServerProtocol.V2) loadBaseOptions(opened)
    }

    fun updateName(name: String) {
        _state.update { it?.copy(name = name, nameErrorVisible = true) }
    }

    fun showBasePicker() {
        _state.update { if (it == null || !it.baseSelectable || it.submitting) it else it.copy(basePickerVisible = true) }
    }

    fun hideBasePicker() {
        _state.update { it?.copy(basePickerVisible = false) }
    }

    /** The source checkout's own branch is the default, so it picks null. */
    fun selectBase(ref: String?) {
        _state.update { state ->
            state ?: return@update null
            val picked = ref?.takeIf { it.isNotBlank() && it != state.sourceBranch }
            state.copy(selectedBaseRef = picked, basePickerVisible = false)
        }
    }

    fun submit() {
        val current = _state.value ?: return
        if (current.submitting) return
        if (!current.canSubmit) {
            _state.update { it?.copy(nameErrorVisible = true) }
            return
        }
        _state.value = current.copy(submitting = true, nameErrorVisible = true, errorMessage = null)
        scope.launch {
            try {
                val created = withContext(ioDispatcher) { repository.create(current.request) }
                onInventoryChanged(current.origin, current.request)
                val stillOpen = _state.value?.let { it.origin == current.origin && it.submitting } == true
                if (!stillOpen) return@launch
                baseJob?.cancel()
                _state.value = null
                onCreated(current.origin, created)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The server's own text stays verbatim.
                val message =
                    ((error as? WorktreeCreateError)?.message ?: error.message?.takeIf { it.isNotBlank() })
                        ?.let(UiText::verbatim)
                        ?: UiText.resource(R.string.worktree_unknown_error)
                _state.update { state ->
                    if (state == null || state.origin != current.origin) {
                        state
                    } else {
                        state.copy(submitting = false, errorMessage = UiText.resource(R.string.worktree_create_failed, message))
                    }
                }
            }
        }
    }

    /** Back to the list (or the sheet closed). A create in flight still finishes on the server. */
    fun dismiss() {
        baseJob?.cancel()
        baseJob = null
        _state.value = null
    }

    private fun loadBaseOptions(opened: WorktreeCreateFormState) {
        baseJob =
            scope.launch {
                val options =
                    try {
                        WorktreeBaseOptions.Loaded(withContext(ioDispatcher) { repository.baseBranches(opened.sourceDirectory) })
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        WorktreeBaseOptions.Failed
                    }
                _state.update { state ->
                    if (state?.origin == opened.origin && state.sourceDirectory == opened.sourceDirectory) {
                        state.copy(baseOptions = options)
                    } else {
                        state
                    }
                }
            }
    }
}
