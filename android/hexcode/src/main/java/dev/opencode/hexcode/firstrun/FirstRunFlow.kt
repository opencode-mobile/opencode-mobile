package dev.opencode.hexcode.firstrun

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.hexcode.theme.HexTheme

/** The first-run steps, one at a time, with a short cross-fade between them. */
@Composable
fun FirstRunFlow(vm: FirstRunViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    Box(
        Modifier
            .fillMaxSize()
            .background(HexTheme.colors.background),
    ) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "first-run",
        ) { step ->
            when (step) {
                FirstRunStep.Welcome -> WelcomeScreen(onScan = vm::openScanner)
                FirstRunStep.Scan ->
                    ScanScreen(
                        scan = state.scan,
                        scanAttempt = state.scanAttempt,
                        onScanned = vm::scanned,
                        onScanAgain = vm::scanAgain,
                        onCameraDenied = vm::cameraDenied,
                        onCameraUnavailable = vm::cameraUnavailable,
                        onClose = vm::back,
                    )
                FirstRunStep.Project ->
                    ProjectScreen(
                        host = state.server?.host,
                        projects = state.projects,
                        onPick = vm::pickProject,
                        onBrowse = vm::browseFolders,
                        onRetry = vm::retryProjects,
                        onBack = vm::back,
                    )
                FirstRunStep.Browse ->
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
                FirstRunStep.Model ->
                    ModelScreen(
                        host = state.server?.host,
                        model = state.model,
                        onSearch = vm::searchModels,
                        onSelect = vm::selectModel,
                        onLevel = vm::selectLevel,
                        onTest = vm::testModel,
                        onStart = vm::start,
                        onRetry = vm::retryModels,
                        onBack = vm::back,
                    )
                FirstRunStep.Done -> Unit
            }
        }
    }
}
