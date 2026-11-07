package dev.opencode.hexcode.firstrun

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.hexcode.app.HexServices
import dev.opencode.mobile.domain.filesystem.DirectoryListing
import dev.opencode.mobile.domain.filesystem.DirectoryNotFoundException
import dev.opencode.mobile.domain.filesystem.FilesystemRepository
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.project.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

enum class FirstRunStep { Welcome, Scan, Project, Browse, Model, Done }

/** The scan screen's panel. Server checks stay hidden unless one fails. */
sealed interface ScanUi {
    data object Scanning : ScanUi

    data object Connecting : ScanUi

    data class Connected(
        val name: String,
        val version: String?,
    ) : ScanUi

    data class Failed(
        val failure: ConnectFailure,
    ) : ScanUi

    data object CameraDenied : ScanUi

    data object CameraUnavailable : ScanUi
}

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data class Loaded<T>(
        val value: T,
    ) : Loadable<T>

    data object Failed : Loadable<Nothing>
}

/** How the paired computer is shown: its friendly name, and its own hostname and OpenCode version for details. */
data class ServerLabel(
    val name: String,
    val hostname: String? = null,
    val version: String? = null,
)

data class FirstRunUiState(
    val step: FirstRunStep = FirstRunStep.Welcome,
    val scan: ScanUi = ScanUi.Scanning,
    /** Changes whenever the camera should start over, so the scanner reads a new code. */
    val scanAttempt: Int = 0,
    val server: ServerLabel? = null,
    val projects: Loadable<List<ProjectRow>> = Loadable.Loading,
    val browser: FolderBrowserState? = null,
    val project: ProjectChoice? = null,
    val model: Loadable<ModelPickerState> = Loadable.Loading,
    /** Pair again found another computer: "Switch to <name>?" waits for an answer. */
    val switchTo: ConnectResult.Connected? = null,
    /** Pair again is over ([FirstRunStep.Done]): how it went. */
    val paired: PairedAgain? = null,
)

/** How Pair again ended. */
sealed interface PairedAgain {
    /** The same computer: its password and addresses were replaced; project, model and today's session stay. */
    data class Same(
        val name: String,
    ) : PairedAgain

    /** Another computer, after Switch: first run goes on at its project page there. */
    data class Switched(
        val name: String,
    ) : PairedAgain
}

/** What first run needs from the app; [HexServices] in the app, fakes in tests. */
interface FirstRunEnvironment {
    val pairing: PairingConnector
    val projects: ProjectRepository
    val filesystem: FilesystemRepository
    val models: ModelRepository
    val modelTester: ModelTesting

    suspend fun useServer(result: ConnectResult.Connected)

    suspend fun saveProject(project: ProjectChoice)

    suspend fun finish(
        model: ModelChoice,
        name: String,
    )

    /** Starts the speech model download in the background, once. */
    fun getSpeechModel()

    /** The model and level saved earlier, to start the model page on. */
    suspend fun savedModel(): ModelChoice? = null

    /** Pair again: [result] is the computer already paired (the same hostname). */
    suspend fun isSameComputer(result: ConnectResult.Connected): Boolean = false

    /** Pair again with another computer: the old one and its password go, [result] is saved and used. */
    suspend fun switchServer(result: ConnectResult.Connected) = useServer(result)

    fun now(): Long = System.currentTimeMillis()
}

/**
 * First run walks every step; Settings opens one page (project or model) and closes it once a choice is saved; Pair
 * again is the scan screen alone, which replaces the sign-in of the same computer or, after a confirmation, switches to
 * another.
 */
enum class PickerMode { FirstRun, Settings, PairAgain }

/**
 * First run, the prototype's six steps: the rule and how to connect, scan and connect, pick a project (or browse
 * folders), and pick a model, which must pass a one-line test before Start. Settings reuses the project and model pages
 * ([PickerMode.Settings]): there the model page ends in Save, and [FirstRunStep.Done] means the page closed.
 */
class FirstRunViewModel(
    private val env: FirstRunEnvironment,
    private val connectedPause: Long = CONNECTED_PAUSE_MS,
    private val mode: PickerMode = PickerMode.FirstRun,
    /** Folder listings for the whole app session, so a folder visited once isn't listed again. */
    private val listingCache: MutableMap<String, DirectoryListing> = ConcurrentHashMap(),
) : ViewModel() {
    private val state = MutableStateFlow(FirstRunUiState())
    val uiState: StateFlow<FirstRunUiState> = state.asStateFlow()

    private var connectJob: Job? = null
    private var modelJob: Job? = null
    private var testJob: Job? = null
    private var browseJob: Job? = null
    private var home = ""
    private val summaries = Semaphore(FolderBrowserState.SUMMARY_CONCURRENCY)
    private val summaryRequested = mutableSetOf<String>()

    /** Row summaries that arrived since the last redraw; they are applied together, at most every [SUMMARY_BATCH_MS]. */
    private val pendingSummaries = mutableMapOf<String, FolderListing>()
    private var summaryFlush: Job? = null

    /** Settings › Project: the project page on its own. */
    fun openProjects(server: ServerLabel?) {
        state.update { it.copy(step = FirstRunStep.Project, server = server) }
        loadProjects()
    }

    /** Settings › Model: the model page on its own, starting on the saved model. */
    fun openModels(
        server: ServerLabel?,
        project: ProjectChoice?,
    ) {
        state.update { it.copy(step = FirstRunStep.Model, server = server, project = project) }
        loadModels(project)
    }

    fun openScanner() {
        connectJob?.cancel()
        state.update { it.copy(step = FirstRunStep.Scan, scan = ScanUi.Scanning, scanAttempt = it.scanAttempt + 1) }
        env.getSpeechModel()
    }

    /** The camera read a code. Later frames are ignored until the screen scans again. */
    fun scanned(raw: String) {
        if (state.value.scan !is ScanUi.Scanning) return
        connectJob =
            viewModelScope.launch {
                state.update { it.copy(scan = ScanUi.Connecting) }
                val result = env.pairing.connect(raw)
                when (result) {
                    is ConnectResult.Failed -> state.update { it.copy(scan = ScanUi.Failed(result.failure)) }
                    is ConnectResult.Connected -> {
                        if (mode == PickerMode.PairAgain && !env.isSameComputer(result)) {
                            // Another computer: nothing is saved until Switch.
                            val server = ServerLabel(result.name, result.hostname, result.version)
                            state.update {
                                it.copy(
                                    scan = ScanUi.Connected(result.name, result.version),
                                    server = server,
                                    switchTo = result,
                                )
                            }
                            return@launch
                        }
                        try {
                            env.useServer(result)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            state.update { it.copy(scan = ScanUi.Failed(ConnectFailure.NotSaved)) }
                            return@launch
                        }
                        val server = ServerLabel(result.name, result.hostname, result.version)
                        state.update { it.copy(scan = ScanUi.Connected(result.name, result.version), server = server) }
                        delay(connectedPause)
                        if (mode == PickerMode.PairAgain) {
                            state.update { it.copy(step = FirstRunStep.Done, paired = PairedAgain.Same(result.name)) }
                            return@launch
                        }
                        state.update { it.copy(step = FirstRunStep.Project) }
                        loadProjects()
                    }
                }
            }
    }

    /** "Switch to …?" › Switch: the old computer goes and this one is used; first run goes on at its project page. */
    fun confirmSwitch() {
        val result = state.value.switchTo ?: return
        connectJob =
            viewModelScope.launch {
                try {
                    env.switchServer(result)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    state.update { it.copy(switchTo = null, scan = ScanUi.Failed(ConnectFailure.NotSaved)) }
                    return@launch
                }
                state.update { it.copy(switchTo = null, step = FirstRunStep.Done, paired = PairedAgain.Switched(result.name)) }
            }
    }

    /** "Switch to …?" › Cancel: nothing changed; the camera reads the next code. */
    fun cancelSwitch() {
        state.update { it.copy(switchTo = null) }
        scanAgain()
    }

    /** After Pair again switched computers: first run goes on at the project page of the [server] just paired. */
    fun resumeAtProject(server: ServerLabel) {
        state.update { it.copy(step = FirstRunStep.Project, server = server) }
        loadProjects()
    }

    fun scanAgain() {
        connectJob?.cancel()
        state.update { it.copy(scan = ScanUi.Scanning, scanAttempt = it.scanAttempt + 1) }
    }

    // The camera only stands in for the scanning panel; a connection under way or its result stays on screen.
    fun cameraDenied() = state.update { if (it.scan == ScanUi.Scanning) it.copy(scan = ScanUi.CameraDenied) else it }

    fun cameraUnavailable() = state.update { if (it.scan == ScanUi.Scanning) it.copy(scan = ScanUi.CameraUnavailable) else it }

    fun back() {
        val step = state.value.step
        // In Settings, back from the project or model page closes it.
        if (mode == PickerMode.Settings && (step == FirstRunStep.Project || step == FirstRunStep.Model)) return close()
        // Pair again's Cancel closes the cover, whatever the scan got to.
        if (mode == PickerMode.PairAgain) {
            connectJob?.cancel()
            return close()
        }
        when (step) {
            FirstRunStep.Welcome, FirstRunStep.Done -> Unit
            FirstRunStep.Scan -> {
                connectJob?.cancel()
                state.update { it.copy(step = FirstRunStep.Welcome, scan = ScanUi.Scanning) }
            }
            // The code was used, so back from the projects scans again.
            FirstRunStep.Project -> {
                connectJob?.cancel()
                state.update { it.copy(step = FirstRunStep.Scan, scan = ScanUi.Scanning, scanAttempt = it.scanAttempt + 1, browser = null) }
            }
            FirstRunStep.Browse -> {
                browseJob?.cancel()
                state.update { it.copy(step = FirstRunStep.Project, browser = null) }
            }
            FirstRunStep.Model -> {
                modelJob?.cancel()
                testJob?.cancel()
                state.update { it.copy(step = FirstRunStep.Project) }
            }
        }
    }

    private fun close() {
        modelJob?.cancel()
        testJob?.cancel()
        state.update { it.copy(step = FirstRunStep.Done) }
    }

    fun retryProjects() = loadProjects()

    private fun loadProjects() {
        viewModelScope.launch {
            state.update { it.copy(projects = Loadable.Loading) }
            val loaded =
                attempt {
                    home = env.filesystem.getPaths().home
                    ProjectPicker.rows(env.projects.listProjects(), home, env.now())
                }
            state.update { it.copy(projects = loaded) }
        }
    }

    fun pickProject(project: ProjectChoice) {
        viewModelScope.launch {
            env.saveProject(project)
            if (mode == PickerMode.Settings) {
                state.update { it.copy(project = project, step = FirstRunStep.Done) }
                return@launch
            }
            state.update { it.copy(project = project, step = FirstRunStep.Model) }
            loadModels(project)
        }
    }

    /** Opens the browser at the server home every time; listings already fetched this session are reused. */
    fun browseFolders() {
        val start = FolderBrowserState(home = home, listings = listingCache.mapValues { FolderListing.Loaded(it.value) })
        state.update { it.copy(step = FirstRunStep.Browse, browser = start) }
        list(start.directory)
        if (!ServerPathPolicy.isWindows(home)) listVolumes()
    }

    fun openFolder(path: String) {
        state.update { it.copy(browser = it.browser?.open(path)) }
        list(path)
    }

    fun folderUp() {
        state.value.browser
            ?.upTarget
            ?.let(::openFolder)
    }

    /** A row came on screen: list it once so it can show its folder count and `git` tag. */
    fun folderRowAppeared(path: String) {
        val key = ServerPathPolicy.canonical(path)
        if (!summaryRequested.add(key)) return
        viewModelScope.launch {
            summaries.withPermit {
                if (state.value.browser?.needsListing(path) == true) fetchListing(path, batched = true)
            }
        }
    }

    fun useFolder() {
        val browser = state.value.browser ?: return
        if (browser.canUse) pickProject(browser.choice)
    }

    private fun list(path: String) {
        if (state.value.browser?.needsListing(path) == false) return
        browseJob?.cancel()
        browseJob = viewModelScope.launch { fetchListing(path) }
    }

    /** Lists [path], from the session cache when it can. A row summary ([batched]) waits for the next batch. */
    private suspend fun fetchListing(
        path: String,
        batched: Boolean = false,
    ) {
        val key = ServerPathPolicy.canonical(path)
        val listing =
            listingCache[key]?.let { FolderListing.Loaded(it) }
                ?: try {
                    env.filesystem
                        .listDirectory(path)
                        .also { listingCache[key] = it }
                        .let { FolderListing.Loaded(it) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: DirectoryNotFoundException) {
                    FolderListing.NotFound
                } catch (_: Exception) {
                    FolderListing.Failed
                }
        if (!batched) {
            state.update { it.copy(browser = it.browser?.withListing(path, listing)) }
            return
        }
        pendingSummaries[path] = listing
        if (summaryFlush?.isActive == true) return
        summaryFlush =
            viewModelScope.launch {
                delay(SUMMARY_BATCH_MS)
                val arrived = pendingSummaries.toMap()
                pendingSummaries.clear()
                state.update { current ->
                    current.copy(browser = arrived.entries.fold(current.browser) { browser, (at, l) -> browser?.withListing(at, l) })
                }
            }
    }

    private fun listVolumes() {
        viewModelScope.launch {
            val volumes = runCatching { env.filesystem.listDirectory(FolderBrowserState.VOLUMES_PATH).folders }.getOrNull() ?: return@launch
            state.update { it.copy(browser = it.browser?.copy(volumes = volumes)) }
        }
    }

    fun retryModels() = loadModels(state.value.project)

    private fun loadModels(project: ProjectChoice?) {
        modelJob?.cancel()
        modelJob =
            viewModelScope.launch {
                state.update { it.copy(model = Loadable.Loading) }
                val loaded = attempt { ModelPickerState.create(env.models.listModelOptions(project?.path), saved = env.savedModel()) }
                val usable =
                    if ((loaded as? Loadable.Loaded)
                            ?.value
                            ?.catalog
                            ?.options
                            ?.isEmpty() == true
                    ) {
                        Loadable.Failed
                    } else {
                        loaded
                    }
                state.update { it.copy(model = usable) }
            }
    }

    fun searchModels(query: String) = updatePicker { it.withQuery(query) }

    fun selectModel(option: ModelSelectionOption) = updatePicker { it.select(option) }

    fun selectLevel(level: String) = updatePicker { it.selectLevel(level) }

    fun testModel() {
        val picker = (state.value.model as? Loadable.Loaded)?.value ?: return
        val (testing, choice) = picker.beginTest() ?: return
        state.update { it.copy(model = Loadable.Loaded(testing)) }
        testJob =
            viewModelScope.launch {
                val result = env.modelTester.test(choice, state.value.project?.path)
                updatePicker { it.finishTest(choice, result) }
            }
    }

    /** Start (Save in Settings): only after the current model and level passed their test. */
    fun start() {
        val picker = (state.value.model as? Loadable.Loaded)?.value ?: return
        val choice = picker.choice ?: return
        if (!picker.canStart) return
        val name = picker.selectedOption?.modelLabel ?: choice.modelId
        viewModelScope.launch {
            env.finish(choice, name)
            state.update { it.copy(step = FirstRunStep.Done) }
        }
    }

    private fun updatePicker(change: (ModelPickerState) -> ModelPickerState) {
        state.update { current ->
            val picker = (current.model as? Loadable.Loaded)?.value ?: return@update current
            current.copy(model = Loadable.Loaded(change(picker)))
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Loadable<T> =
        try {
            Loadable.Loaded(block())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Loadable.Failed
        }

    companion object {
        /** How long "Connected" stays before the project page. */
        const val CONNECTED_PAUSE_MS = 900L

        /** Folder summaries arriving within this window are drawn together. */
        const val SUMMARY_BATCH_MS = 100L
    }
}
