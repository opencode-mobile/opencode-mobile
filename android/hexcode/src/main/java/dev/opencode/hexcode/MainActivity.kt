package dev.opencode.hexcode

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.hexcode.app.Appearance
import dev.opencode.hexcode.app.ColdStart
import dev.opencode.hexcode.app.ColdStartGate
import dev.opencode.hexcode.app.HexFirstRunEnvironment
import dev.opencode.hexcode.app.HexPrefs
import dev.opencode.hexcode.app.HexServices
import dev.opencode.hexcode.app.ServerAddresses
import dev.opencode.hexcode.designsystem.HexGallery
import dev.opencode.hexcode.designsystem.HexMotion
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexConfirmSheet
import dev.opencode.hexcode.designsystem.components.HexToastMessage
import dev.opencode.hexcode.designsystem.components.HexToastTone
import dev.opencode.hexcode.firstrun.BrowseScreen
import dev.opencode.hexcode.firstrun.FirstRunFlow
import dev.opencode.hexcode.firstrun.FirstRunStep
import dev.opencode.hexcode.firstrun.FirstRunUiState
import dev.opencode.hexcode.firstrun.FirstRunViewModel
import dev.opencode.hexcode.firstrun.ModelScreen
import dev.opencode.hexcode.firstrun.PairedAgain
import dev.opencode.hexcode.firstrun.PickerMode
import dev.opencode.hexcode.firstrun.ProjectPicker
import dev.opencode.hexcode.firstrun.ProjectScreen
import dev.opencode.hexcode.firstrun.ScanScreen
import dev.opencode.hexcode.firstrun.ServerLabel
import dev.opencode.hexcode.home.AndroidFileAttacher
import dev.opencode.hexcode.home.AndroidImagePreparer
import dev.opencode.hexcode.home.ClipDictation
import dev.opencode.hexcode.home.FileMicrophone
import dev.opencode.hexcode.home.FileTakeStore
import dev.opencode.hexcode.home.HomeScreen
import dev.opencode.hexcode.home.HomeViewModel
import dev.opencode.hexcode.home.MicRecorder
import dev.opencode.hexcode.home.VoiceEngineDictation
import dev.opencode.hexcode.session.SessionSetup
import dev.opencode.hexcode.session.TodaySessionDemo
import dev.opencode.hexcode.settings.NewSessionInfo
import dev.opencode.hexcode.settings.SettingsScreen
import dev.opencode.mobile.domain.connection.DeviceNetworkPathStatus
import dev.opencode.mobile.domain.text.TitleCaseIdentifier
import dev.opencode.voiceengine.EngineOptions
import dev.opencode.voiceengine.SpeechLab
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // A cold start keeps the system splash until home's first state is ready, at most 1.5 s, so home appears in one
        // piece (app-plan.md, Home › Cold start). The activity made again (a rotation, a theme change) has no splash.
        val coldStart = ColdStartGate(SystemClock::uptimeMillis)
        val splash = installSplashScreen()
        if (savedInstanceState == null) splash.setKeepOnScreenCondition(coldStart::holding) else coldStart.ready()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
        )
        val services = (application as HexCodeApplication).services
        val lab = SpeechLab(this)
        // debug builds: `--es HexCodeSample <file in filesDir>` runs a recorded WAV as a take (emulators have no voice)
        val sample = if (BuildConfig.DEBUG) intent.getStringExtra("HexCodeSample") else null
        // debug builds: `--es HexCodeLab <model ids, comma separated, or all>` opens the lab and tests those models
        val labRun = if (BuildConfig.DEBUG) intent.getStringExtra("HexCodeLab") else null
        // with `--es HexCodeLabOptions <allCores, cpu|xnnpack|nnapi>` (comma separated) for that run
        val labOptions =
            intent.getStringExtra("HexCodeLabOptions")?.split(",")?.map { it.trim() }?.let { words ->
                EngineOptions(
                    allCores = "allCores" in words,
                    provider =
                        words.firstOrNull { it in listOf("cpu", "xnnpack", "nnapi") } ?: "cpu",
                )
            }
        // and `--es HexCodeLabSet <name>` for a clip set copied into files/lab-sets instead of the bundled one
        val labSet = if (BuildConfig.DEBUG) intent.getStringExtra("HexCodeLabSet") else null
        // debug builds: `--es HexCodePair <pairing link>` pairs as if the camera had read that code
        val pairLink = if (BuildConfig.DEBUG) intent.getStringExtra("HexCodePair") else null
        // debug builds: `--es HexCodeTheme dark|light` pins the theme instead of following the system
        val pinnedTheme = if (BuildConfig.DEBUG) intent.getStringExtra("HexCodeTheme") else null
        // debug builds: `--ez HexCodeClips true` makes each take the next bundled lab clip (an emulator has no voice)
        val clips = BuildConfig.DEBUG && intent.getBooleanExtra("HexCodeClips", false)
        // debug builds: `--es HexCodeDraft "What letters are in this image?"` opens home with that text in the draft, as
        // a tapped Next would put it, to send typed questions from an emulator
        val draftText = if (BuildConfig.DEBUG) intent.getStringExtra("HexCodeDraft") else null
        // debug builds: `--ez HexCodeNoVision true` makes today's model count as unable to see images (app-plan.md,
        // Attach), whatever the server says
        if (BuildConfig.DEBUG) services.noVision = intent.getBooleanExtra("HexCodeNoVision", false)
        // debug builds: `--ei HexCodeSpeechDelay 8` makes the speech model count as ready only 8 s after launch, and
        // `--ez HexCodeSpeechFail true` makes its first load count as failed, to see takes waiting for it
        if (BuildConfig.DEBUG) {
            services.speechHoldMs = intent.getIntExtra("HexCodeSpeechDelay", 0) * 1_000L
            services.speechFailOnce = intent.getBooleanExtra("HexCodeSpeechFail", false)
        }
        // debug builds: `--ei HexCodePressProbe 5` runs 5 press, hold 1 s and release cycles of the talk button through
        // the real code path once home shows, to time the microphone from logcat on a phone nobody is touching
        if (BuildConfig.DEBUG) services.pressProbe = intent.getIntExtra("HexCodePressProbe", 0)
        // debug builds: `--es HexCodeTakeAudio <WAV in filesDir>` plays that file as the microphone in every take, in
        // real time (16 kHz mono), and `--ez HexCodeTakeProbe true` runs one locked take of it once speech is ready, to
        // time how its parts and the wait after release come out from logcat on a phone nobody is touching
        if (BuildConfig.DEBUG) {
            services.takeAudio = intent.getStringExtra("HexCodeTakeAudio")
            services.takeProbe = intent.getBooleanExtra("HexCodeTakeProbe", false)
        }
        // debug builds: `--es HexCodeSampleSession long` opens home on that sample session (Settings › Developer)
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("HexCodeSampleSession")?.let { label ->
                services.sampleSession.value = TodaySessionDemo.entries.firstOrNull { it.label == label }
            }
        }
        // debug builds: `--es HexCodeLastDate 2026-11-04` saves that as the last date a session was opened, before home
        // opens, to try the new-day notice
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("HexCodeLastDate")?.let { date ->
                runBlocking { services.settings.setLastOpenedOn(LocalDate.parse(date)) }
            }
        }
        // debug builds: `--ei HexCodeSignedInAgo 8` saves the sign-in as issued that many days ago, to see it renewed
        // at setup; `--ez HexCodeSignOut true` replaces the saved token with one the server refuses, to see Signed out
        if (BuildConfig.DEBUG) {
            val ago = intent.getIntExtra("HexCodeSignedInAgo", -1)
            val signOut = intent.getBooleanExtra("HexCodeSignOut", false)
            if (ago >= 0 || signOut) {
                runBlocking {
                    if (ago >= 0) services.settings.setSignedInAt(System.currentTimeMillis() - ago * 86_400_000L)
                    if (signOut) services.profiles.activeProfile()?.let { services.profiles.saveProfile(it.copy(password = "0.refused")) }
                }
            }
        }
        setContent {
            val prefs by services.settings.prefs.collectAsStateWithLifecycle(initialValue = null)
            val dark =
                when (pinnedTheme ?: prefs?.appearance?.name?.lowercase()) {
                    "dark" -> true
                    "light" -> false
                    else -> isSystemInDarkTheme()
                }
            HexTheme(dark = dark) {
                var tool by remember {
                    mutableStateOf(
                        if (labRun != null) {
                            Tool.Lab
                        } else if (sample != null) {
                            Tool.Dictation
                        } else {
                            null
                        },
                    )
                }
                // The lab and dictation tools have nothing to wait for.
                if (tool != null) SideEffect { coldStart.ready() }
                when (tool) {
                    Tool.Lab ->
                        LabScreen(
                            services.voice,
                            lab,
                            onBack = { tool = null },
                            autoRun = labRun,
                            autoOptions = labOptions,
                            autoSet = labSet,
                        )
                    Tool.Dictation -> {
                        SystemBarIcons(darkScreen = true)
                        DictationScreen(services.voice, sampleFile = sample, onOpenLab = { tool = Tool.Lab })
                    }
                    null -> prefs?.let { HexCodeRoot(services, lab, it, pairLink, clips, draftText, coldStart) }
                }
            }
        }
    }

    private enum class Tool { Dictation, Lab }
}

/**
 * What is on screen once first run is over: home at the root, Settings on top of it, and Settings' project (with
 * Browse folders) and model pages, and the Developer section's gallery and speech lab, on top of Settings. [depth]
 * orders them on that back stack.
 */
private enum class Page(
    val depth: Int,
) {
    Home(0),
    Settings(1),
    SettingsProject(2),
    SettingsBrowse(3),
    SettingsModel(2),
    SettingsGallery(2),
    SettingsSpeechLab(2),
}

/** First run until Start, then Home, with Settings and its project and model pages over it. */
@Composable
private fun HexCodeRoot(
    services: HexServices,
    lab: SpeechLab,
    prefs: HexPrefs,
    pairLink: String?,
    clips: Boolean,
    draftText: String?,
    coldStart: ColdStartGate,
) {
    LaunchedEffect(Unit) {
        // The saved server's address check can take seconds; nothing on screen waits for it.
        services.appScope.launch { services.loadConnection() }
    }
    val pairedAtLaunch = remember { prefs.firstRunDone }
    Box(
        Modifier
            .fillMaxSize()
            .background(HexTheme.colors.background),
    ) {
        if (prefs.firstRunDone) {
            // The debug pairing link pairs again only when the app opened after first run; in first run it was used there.
            AfterFirstRun(services, lab, prefs, clips, draftText, pairLink.takeIf { pairedAtLaunch }, coldStart)
        } else {
            // Forget and a switch to another computer start first run afresh.
            val generation by services.setupGeneration.collectAsStateWithLifecycle()
            val vm =
                viewModel(key = "first-run-$generation") {
                    FirstRunViewModel(HexFirstRunEnvironment(services), listingCache = services.folderListings).also { fresh ->
                        // Pair again switched computers: first run goes on at that computer's project page.
                        services.resumeFirstRunAt?.let { server ->
                            services.resumeFirstRunAt = null
                            fresh.resumeAtProject(server)
                        }
                    }
                }
            val state by vm.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(pairLink) {
                if (pairLink != null) {
                    vm.openScanner()
                    vm.scanned(pairLink)
                }
            }
            // The camera view is dark in both themes.
            SystemBarIcons(darkScreen = HexTheme.isDark || state.step == FirstRunStep.Scan)
            // First run's screens are ready as they come.
            SideEffect { coldStart.ready() }
            if (state.step != FirstRunStep.Done) FirstRunFlow(vm)
        }
    }
}

@Composable
private fun AfterFirstRun(
    services: HexServices,
    lab: SpeechLab,
    prefs: HexPrefs,
    clips: Boolean,
    draftText: String?,
    pairLink: String?,
    coldStart: ColdStartGate,
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(Page.Home) }
    // Pair again's scan screen, a full-screen cover over Settings or home.
    var pairingAgain by remember { mutableStateOf(false) }
    // Read the server's label again after Pair again.
    var serverRead by remember { mutableIntStateOf(0) }
    // Each visit to a settings page starts it afresh.
    var visit by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<HexToastMessage?>(null) }
    val scope = rememberCoroutineScope()
    // The gallery's own Light / Dark switch, which starts from the app's theme.
    var galleryDark by remember { mutableStateOf(false) }
    val appDark = HexTheme.isDark
    SystemBarIcons(
        darkScreen =
            when (page) {
                Page.SettingsGallery -> galleryDark
                // The lab is dark in both themes.
                Page.SettingsSpeechLab -> true
                else -> appDark
            },
    )
    val server by produceState<ServerLabel?>(null, page, serverRead) {
        value = services.serverLabel()?.let { it.copy(version = it.version ?: prefs.serverVersion) }
    }
    val home by produceState<String?>(prefs.serverHome) {
        if (value == null) {
            services.connectionLoaded.await()
            value = runCatching { services.filesystem.getPaths().home }.getOrNull()
        }
    }
    val sample by services.sampleSession.collectAsStateWithLifecycle()
    val takeFile = remember { services.takeAudio?.let { File(it).takeIf(File::isAbsolute) ?: File(context.filesDir, it) } }
    // Where link tiles for `localhost` open: the paired computer, as the phone reaches it.
    val computer by produceState<String?>(null) { value = services.computerAddress() }
    // Today's session reads the latest choices when it opens, so a change made in Settings reaches the next open.
    val latest by rememberUpdatedState(prefs)
    // A new sample (or Off) is a new home: the session starts again from opening.
    val homeModel =
        viewModel(key = "home-${sample?.label ?: "live"}-${services.setupGeneration.value}") {
            HomeViewModel(
                if (clips) {
                    ClipDictation(context, services.voice, services::reloadSpeech, services.speechHoldMs, services.speechFailOnce)
                } else {
                    val app = context.applicationContext
                    VoiceEngineDictation(
                        services.voice,
                        takeFile?.let(::FileMicrophone) ?: MicRecorder {
                            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        },
                        services::reloadSpeech,
                        services.speechHoldMs,
                        services.speechFailOnce,
                    )
                },
                takes = FileTakeStore(File(context.filesDir, "takes")),
                images = AndroidImagePreparer(context.applicationContext),
                imageInput = { services.imageInput(latest.project?.path, latest.model, latest.modelName) },
                files = AndroidFileAttacher(context.applicationContext, services.uploads, directory = { latest.project?.path.orEmpty() }),
                makeSession = { scope ->
                    sample?.start(scope)
                        ?: services.todaySession(scope, directory = { latest.project?.path.orEmpty() }, model = { latest.model })
                },
            )
        }
    // The splash goes once today's session is found and its summary read, or setup failed or was signed out.
    LaunchedEffect(homeModel) {
        homeModel.uiState.first { ColdStart.settled(it.today) }
        coldStart.ready()
    }
    // In front, the date is checked and the stream reconnects; in the background it closes after a grace.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { homeModel.enteredForeground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { homeModel.enteredBackground() }
    LaunchedEffect(homeModel) { draftText?.let(homeModel::addToDraft) }
    LaunchedEffect(homeModel) {
        val cycles = services.pressProbe
        services.pressProbe = 0
        if (cycles > 0) homeModel.pressProbe(cycles)
    }
    LaunchedEffect(homeModel) {
        val probe = services.takeProbe
        services.takeProbe = false
        if (probe && takeFile != null) homeModel.takeProbe(FileMicrophone(takeFile).seconds)
    }
    LaunchedEffect(homeModel) {
        services.networkPath.updates
            .drop(1)
            .filter { it == DeviceNetworkPathStatus.Ready }
            .collect { homeModel.networkReturned() }
    }
    // Settings › Model and Project: the session follows a new model, and a new project opens a session there.
    var seenModel by remember { mutableStateOf(prefs.model) }
    var seenProject by remember { mutableStateOf(prefs.project?.path) }
    LaunchedEffect(prefs.model, prefs.project?.path) {
        val model = prefs.model
        if (model != null && model != seenModel) homeModel.modelChanged(model)
        if (prefs.project?.path != seenProject) homeModel.projectChanged()
        seenModel = model
        seenProject = prefs.project?.path
    }
    val pages =
        viewModel(key = "settings-$visit") {
            FirstRunViewModel(
                HexFirstRunEnvironment(services, forSettings = true),
                mode = PickerMode.Settings,
                listingCache = services.folderListings,
            )
        }
    val pageState by pages.uiState.collectAsStateWithLifecycle()
    // A visit opens its page once; the pages on the stack recompose as they slide, so this stays out of them.
    LaunchedEffect(pages) {
        when (page) {
            Page.SettingsProject -> pages.openProjects(server)
            Page.SettingsModel -> pages.openModels(server, prefs.project)
            else -> Unit
        }
    }
    // Settings' project page shows Browse folders as a page of its own on the stack.
    val shown =
        when {
            page == Page.SettingsProject && pageState.step == FirstRunStep.Browse -> Page.SettingsBrowse
            else -> page
        }
    val projectChanged =
        pageState.project?.let {
            HexToastMessage(stringResource(R.string.settings_project_changed, it.name), HexToastTone.Steer)
        }
    val pairedTitle = stringResource(R.string.server_paired)
    val pairedDetail = stringResource(R.string.server_paired_sub, server?.name.orEmpty())
    val addresses by produceState<ServerAddresses?>(null, page, serverRead) { value = services.serverAddresses() }
    val homeUi by homeModel.uiState.collectAsStateWithLifecycle()
    // Debug builds: `--es HexCodePair <link>` with first run done pairs again with that code.
    var debugPair by remember { mutableStateOf(pairLink) }
    LaunchedEffect(pageState.step) {
        if (pageState.step == FirstRunStep.Done && (page == Page.SettingsProject || page == Page.SettingsModel)) {
            if (page == Page.SettingsProject) notice = projectChanged
            page = Page.Settings
        }
    }
    val reduced = HexMotion.reduced
    AnimatedContent(
        targetState = shown,
        transitionSpec = {
            // With Reduce Motion pages fade instead of sliding (app-plan.md, Motion and Reduce Motion).
            if (reduced) {
                return@AnimatedContent (fadeIn(tween(200)) togetherWith fadeOut(tween(200))).apply {
                    targetContentZIndex =
                        targetState.depth.toFloat()
                }
            }
            // Deeper pages push in from the side, as a navigation stack does; going back slides them out again.
            // The deeper page is always on top: pushing slides it in over the page below, which drifts a little to the
            // left; going back slides it off to the right, uncovering the page below.
            val forward = targetState.depth >= initialState.depth
            val motion =
                if (forward) {
                    slideInHorizontally(tween(280)) { width -> width } togetherWith slideOutHorizontally(tween(280)) { width -> -width / 4 }
                } else {
                    slideInHorizontally(tween(280)) { width -> -width / 4 } togetherWith slideOutHorizontally(tween(280)) { width -> width }
                }
            motion.apply { targetContentZIndex = targetState.depth.toFloat() }
        },
        label = "pages",
        modifier = Modifier.background(HexTheme.colors.background),
    ) { current ->
        Box(Modifier.fillMaxSize().background(HexTheme.colors.background)) {
            when (current) {
                Page.Home -> {
                    HomeScreen(
                        homeModel,
                        onSettings = { page = Page.Settings },
                        computer = computer,
                        serverName = server?.name,
                        onPairAgain = { pairingAgain = true },
                    )
                }
                Page.Settings ->
                    SettingsScreen(
                        prefs = prefs,
                        server = server,
                        modelName = prefs.modelName ?: prefs.model?.let { TitleCaseIdentifier.displayName(it.modelId) },
                        projectPath = prefs.project?.path?.let { ProjectPicker.shortPath(it, home.orEmpty()) },
                        notice = notice,
                        onNoticeShown = { notice = null },
                        onProject = {
                            visit += 1
                            page = Page.SettingsProject
                        },
                        onModel = {
                            visit += 1
                            page = Page.SettingsModel
                        },
                        onAppearance = {
                            val next = Appearance.entries[(prefs.appearance.ordinal + 1) % Appearance.entries.size]
                            scope.launch { services.settings.setAppearance(next) }
                        },
                        onGallery = {
                            galleryDark = appDark
                            page = Page.SettingsGallery
                        },
                        onSpeechLab = { page = Page.SettingsSpeechLab },
                        sampleSession = sample?.label ?: "Off",
                        onSampleSession = {
                            // Off, then each preset in turn, then Off again. Not saved: the app always starts on the real session.
                            val presets = TodaySessionDemo.entries
                            services.sampleSession.value =
                                sample.let { if (it == null) presets.first() else presets.getOrNull(it.ordinal + 1) }
                        },
                        onDropStream = if (BuildConfig.DEBUG) homeModel::dropStream else null,
                        onBack = { page = Page.Home },
                        newSession =
                            prefs.project
                                ?.let { project ->
                                    NewSessionInfo(project.name, busy = homeUi.busy, waiting = homeUi.today.waiting)
                                }?.takeIf { homeUi.today.setup is SessionSetup.Ready },
                        onStartNewSession = homeModel::startNewSession,
                        addresses = addresses,
                        onPairAgain = { pairingAgain = true },
                        onForget = {
                            homeModel.shutDown()
                            services.forget()
                        },
                    )
                Page.SettingsProject, Page.SettingsBrowse, Page.SettingsModel -> SettingsPage(pages, pageState, current)
                Page.SettingsGallery -> HexGallery(galleryDark, { galleryDark = it }, onBack = { page = Page.Settings })
                Page.SettingsSpeechLab -> LabScreen(services.voice, lab, onBack = { page = Page.Settings })
            }
        }
    }
    if (pairingAgain || debugPair != null) {
        PairAgainCover(
            services,
            debugLink = debugPair,
            onClose = { paired ->
                pairingAgain = false
                debugPair = null
                if (paired is PairedAgain.Same) {
                    serverRead += 1
                    homeModel.pairedAgain(paired.name)
                    if (page != Page.Home) notice = HexToastMessage(pairedTitle, HexToastTone.Steer, pairedDetail, durationMs = 2_600)
                }
                // A switch ends first run's way: the app goes on at the new computer's project page.
                if (paired is PairedAgain.Switched) homeModel.shutDown()
            },
        )
    }
}

/**
 * Pair again (app-plan.md, Settings and finish (M6) › Server): first run's scan screen and its checks as a full-screen
 * cover, with Cancel. The same computer gets its new sign-in; another one asks "Switch to …?" first.
 */
@Composable
private fun PairAgainCover(
    services: HexServices,
    debugLink: String?,
    onClose: (PairedAgain?) -> Unit,
) {
    val vm =
        viewModel(key = "pair-again-${System.identityHashCode(services)}-${remember { System.nanoTime() }}") {
            FirstRunViewModel(HexFirstRunEnvironment(services), mode = PickerMode.PairAgain, listingCache = services.folderListings)
        }
    val state by vm.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.openScanner()
        if (debugLink != null) vm.scanned(debugLink)
    }
    LaunchedEffect(state.step) { if (state.step == FirstRunStep.Done) onClose(state.paired) }
    SystemBarIcons(darkScreen = true)
    Box(Modifier.fillMaxSize()) {
        if (state.step == FirstRunStep.Scan) {
            ScanScreen(
                scan = state.scan,
                scanAttempt = state.scanAttempt,
                onScanned = vm::scanned,
                onScanAgain = vm::scanAgain,
                onCameraDenied = vm::cameraDenied,
                onCameraUnavailable = vm::cameraUnavailable,
                onClose = vm::back,
                closeLabel = stringResource(R.string.common_cancel),
            )
        }
        val other = state.switchTo
        HexTheme(dark = true) {
            HexConfirmSheet(
                visible = other != null,
                title = stringResource(R.string.server_switch_title, other?.name.orEmpty()),
                paragraphs = listOf(stringResource(R.string.server_switch_body, other?.name.orEmpty())),
                cancelLabel = stringResource(R.string.common_cancel),
                confirmLabel = stringResource(R.string.server_switch_confirm),
                onCancel = vm::cancelSwitch,
                onConfirm = vm::confirmSwitch,
            )
        }
    }
}

/** One of Settings' pages, the first-run screens reused as destinations on Settings' back stack. */
@Composable
private fun SettingsPage(
    vm: FirstRunViewModel,
    state: FirstRunUiState,
    page: Page,
) {
    when (page) {
        Page.SettingsProject ->
            ProjectScreen(
                host = state.server?.name,
                projects = state.projects,
                onPick = vm::pickProject,
                onBrowse = vm::browseFolders,
                onRetry = vm::retryProjects,
                onBack = vm::back,
                title = stringResource(R.string.settings_project_title),
                note = stringResource(R.string.settings_project_note),
            )
        Page.SettingsBrowse ->
            state.browser?.let { browser ->
                BrowseScreen(
                    browser = browser,
                    onOpen = vm::openFolder,
                    onUp = vm::folderUp,
                    onRowAppeared = vm::folderRowAppeared,
                    onUse = vm::useFolder,
                    onBack = vm::back,
                )
            }
        Page.SettingsModel ->
            ModelScreen(
                host = state.server?.name,
                model = state.model,
                onSearch = vm::searchModels,
                onSelect = vm::selectModel,
                onLevel = vm::selectLevel,
                onTest = vm::testModel,
                onStart = vm::start,
                onRetry = vm::retryModels,
                onBack = vm::back,
                title = stringResource(R.string.settings_model_title),
                startLabel = stringResource(R.string.settings_model_save),
            )
        else -> Unit
    }
}

/**
 * Light status and navigation bar icons over a dark screen, dark ones over a light screen. Applied again on every
 * resume, since a system dialog (the camera permission) can put the window's own setting back.
 */
@Composable
private fun SystemBarIcons(darkScreen: Boolean) {
    val view = LocalView.current
    val apply = {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkScreen
                isAppearanceLightNavigationBars = !darkScreen
            }
        }
    }
    SideEffect { apply() }
    LifecycleResumeEffect(darkScreen) {
        apply()
        onPauseOrDispose {}
    }
}
