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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.hexcode.app.HexFirstRunEnvironment
import dev.opencode.hexcode.app.HexPrefs
import dev.opencode.hexcode.firstrun.FirstRunFlow
import dev.opencode.hexcode.firstrun.FirstRunStep
import dev.opencode.hexcode.firstrun.FirstRunViewModel
import dev.opencode.hexcode.theme.HexTheme
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
        setContent {
            HexTheme(dark = pinnedTheme?.let { it == "dark" } ?: isSystemInDarkTheme()) {
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
                    null -> HexCodeRoot(pairLink = pairLink, onOpenDictation = { tool = Tool.Dictation })
                }
            }
        }
    }

    private enum class Tool { Dictation, Lab }
}

/** First run until Start, then the main screen. */
@Composable
private fun HexCodeRoot(
    pairLink: String?,
    onOpenDictation: () -> Unit,
) {
    val services = (LocalContext.current.applicationContext as HexCodeApplication).services
    val loaded by produceState<HexPrefs?>(null) {
        value = services.settings.current()
        // The saved server's address check can take seconds; nothing on screen waits for it.
        services.appScope.launch { services.loadConnection() }
    }
    val prefs by services.settings.prefs.collectAsStateWithLifecycle(initialValue = loaded ?: HexPrefs())
    Box(
        Modifier
            .fillMaxSize()
            .background(HexTheme.colors.background),
    ) {
        val start = loaded ?: return@Box
        if (start.firstRunDone && prefs.firstRunDone) {
            SystemBarIcons(darkScreen = HexTheme.colors.isDark)
            LaunchedEffect(Unit) { services.voice.prepare() }
            MainPlaceholderScreen(prefs, services.voice, onOpenDictation)
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
            SystemBarIcons(darkScreen = HexTheme.colors.isDark || state.step == FirstRunStep.Scan)
            if (state.step == FirstRunStep.Done) {
                MainPlaceholderScreen(prefs, services.voice, onOpenDictation)
            } else {
                FirstRunFlow(vm)
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
