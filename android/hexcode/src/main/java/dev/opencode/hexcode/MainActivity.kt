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
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.hexcode.app.Appearance
import dev.opencode.hexcode.app.HexFirstRunEnvironment
import dev.opencode.hexcode.app.HexPrefs
import dev.opencode.hexcode.app.HexServices
import dev.opencode.hexcode.designsystem.HexGallery
import dev.opencode.hexcode.designsystem.HexMotion
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.firstrun.BrowseScreen
import dev.opencode.hexcode.firstrun.FirstRunFlow
import dev.opencode.hexcode.firstrun.FirstRunStep
import dev.opencode.hexcode.firstrun.FirstRunUiState
import dev.opencode.hexcode.firstrun.FirstRunViewModel
import dev.opencode.hexcode.firstrun.ModelScreen
import dev.opencode.hexcode.firstrun.PickerMode
import dev.opencode.hexcode.firstrun.ProjectPicker
import dev.opencode.hexcode.firstrun.ProjectScreen
import dev.opencode.hexcode.firstrun.ServerLabel
import dev.opencode.hexcode.home.AndroidImagePreparer
import dev.opencode.hexcode.home.ClipDictation
import dev.opencode.hexcode.home.FileMicrophone
import dev.opencode.hexcode.home.FileTakeStore
import dev.opencode.hexcode.home.HomeScreen
import dev.opencode.hexcode.home.HomeViewModel
import dev.opencode.hexcode.home.MicRecorder
import dev.opencode.hexcode.home.VoiceEngineDictation
import dev.opencode.hexcode.session.TodaySessionDemo
import dev.opencode.hexcode.settings.SettingsScreen
import dev.opencode.mobile.domain.connection.DeviceNetworkPathStatus
import dev.opencode.mobile.domain.text.TitleCaseIdentifier
import dev.opencode.voiceengine.EngineOptions
import dev.opencode.voiceengine.SpeechLab
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
                    null -> prefs?.let { HexCodeRoot(services, lab, it, pairLink, clips, draftText) }
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
) {
    LaunchedEffect(Unit) {
        // The saved server's address check can take seconds; nothing on screen waits for it.
        services.appScope.launch { services.loadConnection() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(HexTheme.colors.background),
    ) {
        if (prefs.firstRunDone) {
            AfterFirstRun(services, lab, prefs, clips, draftText)
        } else {
            val vm = viewModel { FirstRunViewModel(HexFirstRunEnvironment(services), listingCache = services.folderListings) }
            val state by vm.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(pairLink) {
                if (pairLink != null) {
                    vm.openScanner()
                    vm.scanned(pairLink)
                }
            }
            // The camera view is dark in both themes.
            SystemBarIcons(darkScreen = HexTheme.isDark || state.step == FirstRunStep.Scan)
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
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(Page.Home) }
    // Each visit to a settings page starts it afresh.
    var visit by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
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
    val server by produceState<ServerLabel?>(null, page) {
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
        viewModel(key = "home-${sample?.label ?: "live"}") {
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
                makeSession = { scope ->
                    sample?.start(scope)
                        ?: services.todaySession(scope, directory = { latest.project?.path.orEmpty() }, model = { latest.model })
                },
            )
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
    val projectChanged = pageState.project?.let { stringResource(R.string.settings_project_changed, it.name) }
    val developerOn = stringResource(R.string.settings_developer_on)
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
                    HomeScreen(homeModel, onSettings = { page = Page.Settings }, computer = computer)
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
                        onVersion = {
                            val now = SystemClock.elapsedRealtime()
                            scope.launch { if (services.developer.tapVersion(now)) notice = developerOn }
                        },
                        developerSettings = prefs.developerSettings,
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
                        onHideDeveloperSettings = { scope.launch { services.developer.hide() } },
                        onBack = { page = Page.Home },
                    )
                Page.SettingsProject, Page.SettingsBrowse, Page.SettingsModel -> SettingsPage(pages, pageState, current)
                Page.SettingsGallery -> HexGallery(galleryDark, { galleryDark = it }, onBack = { page = Page.Settings })
                Page.SettingsSpeechLab -> LabScreen(services.voice, lab, onBack = { page = Page.Settings })
            }
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
