package dev.opencode.mobile.ui.worktree

import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.worktree.CreateWorktreeRequest
import dev.opencode.mobile.domain.worktree.CreatedWorktree
import dev.opencode.mobile.domain.worktree.WorktreeCreateCopy
import dev.opencode.mobile.domain.worktree.WorktreeCreateError
import dev.opencode.mobile.domain.worktree.WorktreeRepository
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
    val title: String,
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

    /** Why [name] (trimmed) cannot be used, or null. */
    fun validate(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> WorktreeCreateCopy.NAME_EMPTY
            !allowed.matches(trimmed) -> WorktreeCreateCopy.NAME_CHARS
            trimmed.length > MAX_LENGTH -> WorktreeCreateCopy.NAME_LONG
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
    /** [WorktreeCreateCopy.failed] after a failed create; the values stay. */
    val errorMessage: String? = null,
) {
    private val validationError: String? get() = WorktreeName.validate(name)

    val nameError: String? get() = if (nameErrorVisible) validationError else null

    val canSubmit: Boolean get() = validationError == null && !submitting

    val branchValue: String
        get() = if (protocol == ServerProtocol.V1) WorktreeCreateCopy.v1Branch(name) else WorktreeCreateCopy.V2_NO_BRANCH

    val branchNote: String
        get() = if (protocol == ServerProtocol.V1) WorktreeCreateCopy.V1_BRANCH_NOTE else WorktreeCreateCopy.V2_BRANCH_NOTE

    /** V2 picks a base; v1 always creates from the project's checkout. */
    val baseSelectable: Boolean get() = protocol == ServerProtocol.V2

    val baseValue: String
        get() =
            if (protocol == ServerProtocol.V1) {
                sourceBranch ?: WorktreeCreateCopy.CURRENT_CHECKOUT
            } else {
                selectedBaseRef ?: WorktreeCreateCopy.defaultBase(sourceBranch)
            }

    val baseNote: String
        get() = if (protocol == ServerProtocol.V1) WorktreeCreateCopy.V1_BASE_NOTE else WorktreeCreateCopy.BASE_PICK_HINT

    /** The base list: the default first (checked while nothing else is picked), then the other branches. */
    val baseRows: List<WorktreeBaseRow>
        get() {
            val default = WorktreeBaseRow(null, WorktreeCreateCopy.defaultBase(sourceBranch), checked = selectedBaseRef == null)
            val refs = (baseOptions as? WorktreeBaseOptions.Loaded)?.refs.orEmpty()
            return listOf(default) +
                refs.filter { it != sourceBranch }.map { ref -> WorktreeBaseRow(ref, ref, checked = selectedBaseRef == ref) }
        }

    /** BASE_LOADING / BASE_FAILED under the default row while the list is not there. */
    val baseStatus: String?
        get() =
            when (baseOptions) {
                WorktreeBaseOptions.Loading -> WorktreeCreateCopy.BASE_LOADING
                WorktreeBaseOptions.Failed -> WorktreeCreateCopy.BASE_FAILED
                is WorktreeBaseOptions.Loaded -> null
            }

    val submitLabel: String
        get() =
            when {
                submitting -> WorktreeCreateCopy.SUBMITTING
                origin is WorktreeCreateOrigin.Move -> WorktreeCreateCopy.SUBMIT_MOVE
                else -> WorktreeCreateCopy.SUBMIT_RUN_IN
            }

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
                val message = (error as? WorktreeCreateError)?.message ?: error.message?.takeIf { it.isNotBlank() } ?: "Unknown error"
                _state.update { state ->
                    if (state == null || state.origin != current.origin) {
                        state
                    } else {
                        state.copy(submitting = false, errorMessage = WorktreeCreateCopy.failed(message))
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
