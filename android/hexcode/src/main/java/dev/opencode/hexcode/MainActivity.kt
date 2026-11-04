package dev.opencode.hexcode

import android.app.Activity
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.hexcode.app.Appearance
import dev.opencode.hexcode.app.HexFirstRunEnvironment
import dev.opencode.hexcode.app.HexPrefs
import dev.opencode.hexcode.app.HexServices
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
import dev.opencode.hexcode.home.ClipDictation
import dev.opencode.hexcode.home.HomeScreen
import dev.opencode.hexcode.home.HomeViewModel
import dev.opencode.hexcode.home.VoiceEngineDictation
import dev.opencode.hexcode.settings.SettingsScreen
import dev.opencode.hexcode.theme.HexTheme
import dev.opencode.mobile.domain.text.TitleCaseIdentifier
import dev.opencode.mobile.theme.LocalOpenCodeDarkTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.voiceengine.EngineOptions
import dev.opencode.voiceengine.SpeechLab
import kotlinx.coroutines.launch

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
                    null -> prefs?.let { HexCodeRoot(services, it, pairLink, clips) }
                }
            }
        }
    }

    private enum class Tool { Dictation, Lab }
}

/**
 * What is on screen once first run is over: home at the root, Settings on top of it, and Settings' project (with
 * Browse folders) and model pages on top of Settings. [depth] orders them on that back stack.
 */
private enum class Page(
    val depth: Int,
) {
    Home(0),
    Settings(1),
    SettingsProject(2),
    SettingsBrowse(3),
    SettingsModel(2),
}

/** First run until Start, then Home, with Settings and its project and model pages over it. */
@Composable
private fun HexCodeRoot(
    services: HexServices,
    prefs: HexPrefs,
    pairLink: String?,
    clips: Boolean,
) {
    LaunchedEffect(Unit) {
        // The saved server's address check can take seconds; nothing on screen waits for it.
        services.appScope.launch { services.loadConnection() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(openCodeColor("background-base")),
    ) {
        if (prefs.firstRunDone) {
            AfterFirstRun(services, prefs, clips)
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
            SystemBarIcons(darkScreen = LocalOpenCodeDarkTheme.current || state.step == FirstRunStep.Scan)
            if (state.step != FirstRunStep.Done) FirstRunFlow(vm)
        }
    }
}

@Composable
private fun AfterFirstRun(
    services: HexServices,
    prefs: HexPrefs,
    clips: Boolean,
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(Page.Home) }
    // Each visit to a settings page starts it afresh.
    var visit by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SystemBarIcons(darkScreen = LocalOpenCodeDarkTheme.current)
    LaunchedEffect(Unit) { services.voice.prepare() }
    val server by produceState<ServerLabel?>(null, page) {
        value = services.serverLabel()?.let { it.copy(version = it.version ?: prefs.serverVersion) }
    }
    val home by produceState<String?>(prefs.serverHome) {
        if (value == null) {
            services.connectionLoaded.await()
            value = runCatching { services.filesystem.getPaths().home }.getOrNull()
        }
    }
    val homeModel =
        viewModel {
            HomeViewModel(if (clips) ClipDictation(context, services.voice) else VoiceEngineDictation(services.voice))
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
    LaunchedEffect(pageState.step) {
        if (pageState.step == FirstRunStep.Done && (page == Page.SettingsProject || page == Page.SettingsModel)) {
            if (page == Page.SettingsProject) notice = projectChanged
            page = Page.Settings
        }
    }
    AnimatedContent(
        targetState = shown,
        transitionSpec = {
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
        modifier = Modifier.background(openCodeColor("background-base")),
    ) { current ->
        Box(Modifier.fillMaxSize().background(openCodeColor("background-base"))) {
            when (current) {
                Page.Home -> {
                    val speech by services.voice.modelState.collectAsStateWithLifecycle()
                    HomeScreen(homeModel, speech, onSettings = { page = Page.Settings })
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
                        onBack = { page = Page.Home },
                    )
                Page.SettingsProject, Page.SettingsBrowse, Page.SettingsModel -> SettingsPage(pages, pageState, current)
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
