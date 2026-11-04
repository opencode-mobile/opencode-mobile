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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.hexcode.R
import dev.opencode.mobile.theme.openCodeColor

/** The first-run steps, one at a time, with a short cross-fade between them. [inSettings] retitles the pages. */
@Composable
fun FirstRunFlow(
    vm: FirstRunViewModel,
    inSettings: Boolean = false,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    Box(
        Modifier
            .fillMaxSize()
            .background(openCodeColor("background-base")),
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
                        title = stringResource(if (inSettings) R.string.settings_change_project else R.string.first_run_project_title),
                        note = stringResource(if (inSettings) R.string.settings_change_project_note else R.string.first_run_project_note),
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
                        title = stringResource(if (inSettings) R.string.settings_model else R.string.first_run_model_title),
                        startLabel = stringResource(if (inSettings) R.string.settings_model_save else R.string.first_run_start),
                    )
                FirstRunStep.Done -> Unit
            }
        }
    }
}
