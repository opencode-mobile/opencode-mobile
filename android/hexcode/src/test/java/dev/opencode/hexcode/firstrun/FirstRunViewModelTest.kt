package dev.opencode.hexcode.firstrun

import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.DetectedServer
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.filesystem.DirectoryListing
import dev.opencode.mobile.domain.filesystem.FilesystemRepository
import dev.opencode.mobile.domain.filesystem.ServerPaths
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.project.Project
import dev.opencode.mobile.domain.project.ProjectRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val home = "/Users/example"
    private val opus = ModelSelectionOption("anthropic", "Anthropic", "claude-opus-5-5", "Claude Opus 5.5", listOf("low", "high"))
    private val gemini = ModelSelectionOption("google", "Google", "gemini-2.5-pro", "Gemini 2.5 Pro", listOf("low", "high"))

    private class Env(
        val home: String,
        catalog: ModelSelectionCatalog,
    ) : FirstRunEnvironment {
        val finished = mutableListOf<ModelChoice>()
        val savedProjects = mutableListOf<ProjectChoice>()
        val listedPaths = mutableListOf<String>()
        val servers = mutableListOf<ConnectionProfile>()
        val modelDirectories = mutableListOf<String?>()
        var speechStarts = 0
        var testResults: (ModelChoice) -> ModelTestState = { ModelTestState.Passed(1.0) }
        var testGate: CompletableDeferred<Unit>? = null

        override val pairing =
            PairingConnector(
                probing =
                    object : ConnectionProbing {
                        override suspend fun probe(connection: dev.opencode.mobile.network.ServerConnection) = ServerProtocol.V2

                        override suspend fun probeServer(connection: dev.opencode.mobile.network.ServerConnection) =
                            DetectedServer(ServerProtocol.V2, "2.2.5")
                    },
                redeeming = { _, _ -> "test-password" },
                routes = { emptyList() },
            )
        override val projects =
            object : ProjectRepository {
                override suspend fun probeServer() = Unit

                override suspend fun listProjects() =
                    listOf(
                        Project(
                            id = "p1",
                            worktree = "$home/Projects/app",
                            displayName = "app",
                            activityAtEpochMs = 1L,
                            isRepository = true,
                        ),
                    )

                override suspend fun getCurrentProject(directory: String?) = throw UnsupportedOperationException()
            }
        override val filesystem =
            object : FilesystemRepository {
                override suspend fun getPaths() = ServerPaths(home = home, directory = home, worktree = home)

                override suspend fun listDirectory(path: String): DirectoryListing {
                    listedPaths += path
                    return DirectoryListing(emptyList(), isGitRepository = false)
                }
            }
        override val models =
            object : ModelRepository {
                override suspend fun listModelOptions(directory: String?): ModelSelectionCatalog {
                    modelDirectories += directory
                    return catalog
                }
            }
        override val modelTester =
            ModelTesting { choice, _ ->
                testGate?.await()
                testResults(choice)
            }

        override suspend fun useServer(result: ConnectResult.Connected) {
            servers += result.profile
        }

        var sameComputer = true
        val switched = mutableListOf<ConnectionProfile>()

        override suspend fun isSameComputer(result: ConnectResult.Connected) = sameComputer

        override suspend fun switchServer(result: ConnectResult.Connected) {
            switched += result.profile
        }

        override suspend fun saveProject(project: ProjectChoice) {
            savedProjects += project
        }

        override suspend fun finish(
            model: ModelChoice,
            name: String,
        ) {
            finished += model
        }

        override fun getSpeechModel() {
            speechStarts += 1
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun env() = Env(home, ModelSelectionCatalog(listOf(opus, gemini), serverDefault = opus))

    private fun viewModel(env: Env) = FirstRunViewModel(env, connectedPause = 0L)

    @Test
    fun scanningPairsAndMovesToTheProjects() =
        runTest(dispatcher) {
            val env = env()
            val vm = viewModel(env)
            vm.openScanner()
            assertEquals(FirstRunStep.Scan, vm.uiState.value.step)
            assertEquals(1, env.speechStarts)
            vm.scanned("""{"code":"7Q4K9M","urls":["http://192.168.1.2:4096"]}""")
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals(FirstRunStep.Project, state.step)
            assertEquals(ServerName.of(null, "http://192.168.1.2:4096"), state.server?.name)
            assertEquals(1, env.servers.size)
            val rows = (state.projects as Loadable.Loaded).value
            assertEquals("~/Projects/app", rows.single().shownPath)
        }

    @Test
    fun aFailedScanStaysOnTheScannerUntilScanAgain() =
        runTest(dispatcher) {
            val vm = viewModel(env())
            vm.openScanner()
            val attempt = vm.uiState.value.scanAttempt
            vm.scanned("not a code")
            advanceUntilIdle()
            assertEquals(ScanUi.Failed(ConnectFailure.NotAPairingCode), vm.uiState.value.scan)
            vm.scanned("""{"code":"7Q4K9M","urls":["http://192.168.1.2:4096"]}""")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.scan is ScanUi.Failed)
            vm.scanAgain()
            assertEquals(ScanUi.Scanning, vm.uiState.value.scan)
            assertEquals(attempt + 1, vm.uiState.value.scanAttempt)
        }

    @Test
    fun pickingAProjectSavesItAndLoadsModelsForIt() =
        runTest(dispatcher) {
            val env = env()
            val vm = viewModel(env)
            vm.pickProject(ProjectChoice("app", "$home/Projects/app"))
            advanceUntilIdle()
            assertEquals(listOf(ProjectChoice("app", "$home/Projects/app")), env.savedProjects)
            assertEquals(listOf<String?>("$home/Projects/app"), env.modelDirectories)
            assertEquals(FirstRunStep.Model, vm.uiState.value.step)
            assertEquals(ModelChoice("anthropic", "claude-opus-5-5"), picker(vm).choice)
        }

    @Test
    fun browsingUsesTheFolderTheBarNames() =
        runTest(dispatcher) {
            val env = env()
            val vm = viewModel(env)
            vm.openScanner()
            vm.scanned("""{"code":"7Q4K9M","urls":["http://192.168.1.2:4096"]}""")
            advanceUntilIdle()
            vm.browseFolders()
            advanceUntilIdle()
            vm.useFolder()
            advanceUntilIdle()
            // Home itself is not offered.
            assertTrue(env.savedProjects.isEmpty())
            vm.openFolder("$home/Projects")
            advanceUntilIdle()
            vm.useFolder()
            advanceUntilIdle()
            assertEquals(listOf(ProjectChoice("Projects", "$home/Projects")), env.savedProjects)
        }

    @Test
    fun browsingAgainStartsAtHomeAndKeepsListings() =
        runTest(dispatcher) {
            val env = env()
            val vm = viewModel(env)
            vm.openScanner()
            vm.scanned("""{"code":"7Q4K9M","urls":["http://192.168.1.2:4096"]}""")
            advanceUntilIdle()
            vm.browseFolders()
            advanceUntilIdle()
            vm.openFolder("$home/Projects")
            advanceUntilIdle()
            vm.back()
            vm.browseFolders()
            advanceUntilIdle()
            assertEquals(
                home,
                vm.uiState.value.browser
                    ?.directory,
            )
            // The listings from the first visit are reused.
            assertEquals(1, env.listedPaths.count { it == "$home/Projects" })
        }

    @Test
    fun startIsRefusedUntilTheCurrentChoicePasses() =
        runTest(dispatcher) {
            val env = env()
            val vm = viewModel(env)
            vm.pickProject(ProjectChoice("app", "$home/Projects/app"))
            advanceUntilIdle()
            vm.start()
            advanceUntilIdle()
            assertTrue(env.finished.isEmpty())

            vm.selectLevel("high")
            vm.testModel()
            advanceUntilIdle()
            assertTrue(picker(vm).canStart)
            vm.start()
            advanceUntilIdle()
            assertEquals(listOf(ModelChoice("anthropic", "claude-opus-5-5", "high")), env.finished)
            assertEquals(FirstRunStep.Done, vm.uiState.value.step)
        }

    @Test
    fun aFailingModelCannotStart() =
        runTest(dispatcher) {
            val env = env()
            env.testResults = { ModelTestState.Failed(ModelTestFailure.Server("Google isn’t set up in OpenCode")) }
            val vm = viewModel(env)
            vm.pickProject(ProjectChoice("app", "$home/Projects/app"))
            advanceUntilIdle()
            vm.selectModel(gemini)
            vm.testModel()
            advanceUntilIdle()
            assertEquals(ModelTestState.Failed(ModelTestFailure.Server("Google isn’t set up in OpenCode")), picker(vm).currentTest)
            vm.start()
            advanceUntilIdle()
            assertTrue(env.finished.isEmpty())
        }

    @Test
    fun changingTheModelWhileTestingDropsThatResult() =
        runTest(dispatcher) {
            val env = env()
            env.testGate = CompletableDeferred()
            val vm = viewModel(env)
            vm.pickProject(ProjectChoice("app", "$home/Projects/app"))
            advanceUntilIdle()
            vm.testModel()
            advanceUntilIdle()
            assertTrue(picker(vm).isTesting)
            vm.selectModel(gemini)
            env.testGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(ModelTestState.NotRun, picker(vm).currentTest)
            vm.start()
            advanceUntilIdle()
            assertTrue(env.finished.isEmpty())
        }

    @Test
    fun backFromTheProjectsScansAgain() =
        runTest(dispatcher) {
            val vm = viewModel(env())
            vm.openScanner()
            vm.scanned("""{"code":"7Q4K9M","urls":["http://192.168.1.2:4096"]}""")
            advanceUntilIdle()
            val attempt = vm.uiState.value.scanAttempt
            vm.back()
            assertEquals(FirstRunStep.Scan, vm.uiState.value.step)
            assertEquals(ScanUi.Scanning, vm.uiState.value.scan)
            assertEquals(attempt + 1, vm.uiState.value.scanAttempt)
            assertNull(vm.uiState.value.browser)
            vm.back()
            assertEquals(FirstRunStep.Welcome, vm.uiState.value.step)
        }

    @Test
    fun anEmptyCatalogIsAFailure() =
        runTest(dispatcher) {
            val vm = FirstRunViewModel(Env(home, ModelSelectionCatalog(emptyList(), serverDefault = null)), connectedPause = 0L)
            vm.pickProject(ProjectChoice("app", "$home/Projects/app"))
            advanceUntilIdle()
            assertEquals(Loadable.Failed, vm.uiState.value.model)
        }

    @Test
    fun inSettingsPickingAProjectSavesItAndClosesThePage() =
        runTest(dispatcher) {
            val env = env()
            val vm = FirstRunViewModel(env, connectedPause = 0L, mode = PickerMode.Settings)
            vm.openProjects(ServerLabel("example.local", "2.2.5"))
            advanceUntilIdle()
            assertEquals(FirstRunStep.Project, vm.uiState.value.step)
            vm.pickProject(ProjectChoice("app", "$home/Projects/app"))
            advanceUntilIdle()
            assertEquals(FirstRunStep.Done, vm.uiState.value.step)
            assertEquals(listOf(ProjectChoice("app", "$home/Projects/app")), env.savedProjects)
            assertTrue(env.modelDirectories.isEmpty())
        }

    @Test
    fun inSettingsTheModelPageSavesOnlyAfterAPassAndBackCloses() =
        runTest(dispatcher) {
            val env = env()
            val vm = FirstRunViewModel(env, connectedPause = 0L, mode = PickerMode.Settings)
            vm.openModels(null, ProjectChoice("app", "$home/Projects/app"))
            advanceUntilIdle()
            vm.start()
            advanceUntilIdle()
            assertTrue(env.finished.isEmpty())
            vm.testModel()
            advanceUntilIdle()
            vm.start()
            advanceUntilIdle()
            assertEquals(listOf(ModelChoice("anthropic", "claude-opus-5-5")), env.finished)
            assertEquals(FirstRunStep.Done, vm.uiState.value.step)

            val again = FirstRunViewModel(env, connectedPause = 0L, mode = PickerMode.Settings)
            again.openModels(null, null)
            advanceUntilIdle()
            again.back()
            assertEquals(FirstRunStep.Done, again.uiState.value.step)
        }

    @Test
    fun aCameraFailureDoesNotHideAPairingResult() =
        runTest(dispatcher) {
            val vm = viewModel(env())
            vm.openScanner()
            vm.scanned("not a code")
            advanceUntilIdle()
            vm.cameraUnavailable()
            assertEquals(ScanUi.Failed(ConnectFailure.NotAPairingCode), vm.uiState.value.scan)
            vm.scanAgain()
            vm.cameraUnavailable()
            assertEquals(ScanUi.CameraUnavailable, vm.uiState.value.scan)
        }

    private fun picker(vm: FirstRunViewModel) = (vm.uiState.value.model as Loadable.Loaded).value

    // app-plan.md, Settings and finish (M6) › Server › Pair again.

    private fun pairAgain(env: Env) = FirstRunViewModel(env, connectedPause = 0L, mode = PickerMode.PairAgain)

    @Test
    fun pairingAgainWithTheSameComputerReplacesItsSignInAndCloses() =
        runTest(dispatcher) {
            val env = env()
            val vm = pairAgain(env)
            vm.openScanner()
            vm.scanned("""{"code":"7Q4K9M","urls":["http://192.168.1.2:4096"]}""")
            advanceUntilIdle()
            assertEquals(1, env.servers.size)
            assertEquals("test-password", env.servers.single().password)
            assertEquals(FirstRunStep.Done, vm.uiState.value.step)
            assertTrue(vm.uiState.value.paired is PairedAgain.Same)
            // Project and model stay: no page asks for them.
            assertTrue(env.savedProjects.isEmpty() && env.finished.isEmpty())
        }

    @Test
    fun anotherComputerAsksFirstThenSwitchesAndFirstRunGoesOnAtItsProjects() =
        runTest(dispatcher) {
            val env = env().apply { sameComputer = false }
            val vm = pairAgain(env)
            vm.openScanner()
            vm.scanned("""{"code":"7Q4K9M","urls":["http://192.168.1.2:4096"]}""")
            advanceUntilIdle()
            assertTrue(env.servers.isEmpty())
            assertTrue(vm.uiState.value.switchTo != null)
            vm.confirmSwitch()
            advanceUntilIdle()
            assertEquals(1, env.switched.size)
            assertTrue(vm.uiState.value.paired is PairedAgain.Switched)
            assertEquals(FirstRunStep.Done, vm.uiState.value.step)
            // First run's own model then opens on that computer's project page.
            val next = viewModel(env)
            next.resumeAtProject(ServerLabel("Quiet Lynx"))
            advanceUntilIdle()
            assertEquals(FirstRunStep.Project, next.uiState.value.step)
            assertTrue(next.uiState.value.projects is Loadable.Loaded)
        }

    @Test
    fun cancellingTheSwitchChangesNothingAndScansAgain() =
        runTest(dispatcher) {
            val env = env().apply { sameComputer = false }
            val vm = pairAgain(env)
            vm.openScanner()
            vm.scanned("""{"code":"7Q4K9M","urls":["http://192.168.1.2:4096"]}""")
            advanceUntilIdle()
            vm.cancelSwitch()
            assertEquals(ScanUi.Scanning, vm.uiState.value.scan)
            assertTrue(env.servers.isEmpty() && env.switched.isEmpty())
            vm.back()
            assertEquals(FirstRunStep.Done, vm.uiState.value.step)
            assertEquals(null, vm.uiState.value.paired)
        }
}
