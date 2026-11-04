package dev.opencode.hexcode

import android.app.Activity
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.hexcode.app.Appearance
import dev.opencode.hexcode.app.HexFirstRunEnvironment
import dev.opencode.hexcode.app.HexPrefs
import dev.opencode.hexcode.app.HexServices
import dev.opencode.hexcode.firstrun.FirstRunFlow
import dev.opencode.hexcode.firstrun.FirstRunStep
import dev.opencode.hexcode.firstrun.FirstRunViewModel
import dev.opencode.hexcode.firstrun.PickerMode
import dev.opencode.hexcode.firstrun.ProjectPicker
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

/** What is on screen once first run is over. */
private enum class Page { Home, Settings, SettingsProject, SettingsModel }

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
            val vm = viewModel { FirstRunViewModel(HexFirstRunEnvironment(services)) }
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
    when (page) {
        Page.Home -> {
            val home =
                viewModel {
                    HomeViewModel(if (clips) ClipDictation(context, services.voice) else VoiceEngineDictation(services.voice))
                }
            val speech by services.voice.modelState.collectAsStateWithLifecycle()
            HomeScreen(home, speech, onSettings = { page = Page.Settings })
        }
        Page.Settings ->
            SettingsScreen(
                prefs = prefs,
                server = server,
                modelName = prefs.modelName ?: prefs.model?.let { TitleCaseIdentifier.displayName(it.modelId) },
                projectPath = prefs.project?.path?.let { ProjectPicker.shortPath(it, home.orEmpty()) },
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
        Page.SettingsProject, Page.SettingsModel -> {
            val vm =
                viewModel(key = "settings-$visit") {
                    FirstRunViewModel(HexFirstRunEnvironment(services, forSettings = true), mode = PickerMode.Settings)
                }
            val state by vm.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                if (page == Page.SettingsProject) vm.openProjects(server) else vm.openModels(server, prefs.project)
            }
            if (state.step == FirstRunStep.Done) {
                LaunchedEffect(Unit) { page = Page.Settings }
            } else {
                FirstRunFlow(vm, inSettings = true)
            }
        }
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
