package dev.opencode.mobile.lab

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.opencode.mobile.RouteSurface

/** The Highlighting Lab's routes; [push] and [navigateBack] act on the stack that shows them. */
internal fun EntryProviderScope<NavKey>.labEntries(
    push: (NavKey) -> Unit,
    navigateBack: () -> Unit,
) {
    entry<LabHome> {
        RouteSurface {
            LabHomeScreen(
                onNavigateBack = navigateBack,
                onDataSets = { push(LabDataSets) },
                onBenchmark = { push(LabBenchmark) },
                onCompare = { push(LabCompare()) },
            )
        }
    }
    entry<LabDataSets> {
        RouteSurface {
            val viewModel: LabDataSetsViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            // Pasted data sets join the list on return.
            LaunchedEffect(Unit) { viewModel.refresh() }
            LabDataSetsScreen(
                state = state,
                onNavigateBack = navigateBack,
                onSelectLanguage = viewModel::selectLanguage,
                onSelectSizeClass = viewModel::selectSizeClass,
                onSelectKind = viewModel::selectKind,
                onOpen = { id -> push(LabViewer(id)) },
                onPaste = { push(LabPaste) },
            )
        }
    }
    entry<LabPaste> {
        RouteSurface {
            val viewModel: LabPasteViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            LabPasteScreen(
                state = state,
                onNavigateBack = navigateBack,
                onTextChange = viewModel::setText,
                onSelectLanguage = viewModel::selectLanguage,
                onOpen = {
                    viewModel.open()?.let { id ->
                        navigateBack()
                        push(LabViewer(id))
                    }
                },
            )
        }
    }
    entry<LabViewer> { key ->
        RouteSurface {
            val viewModel: LabViewerViewModel = hiltViewModel(key = "lab-viewer-${key.dataSetId}")
            LaunchedEffect(key.dataSetId) { viewModel.bind(key.dataSetId) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            LabViewerScreen(
                state = state,
                onNavigateBack = navigateBack,
                onRender = viewModel::render,
                onCancel = viewModel::cancel,
                onCompare = { push(LabCompare(key.dataSetId)) },
            )
        }
    }
    entry<LabBenchmark> {
        RouteSurface {
            val viewModel: LabBenchmarkViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            LabBenchmarkScreen(
                state = state,
                onNavigateBack = navigateBack,
                onSelectScope = viewModel::selectScope,
                onToggleDataSet = viewModel::toggleDataSet,
                onToggleEngine = viewModel::toggleEngine,
                onRun = viewModel::run,
                onStop = viewModel::stop,
            )
        }
    }
    entry<LabCompare> { key ->
        RouteSurface {
            val viewModel: LabCompareViewModel = hiltViewModel(key = "lab-compare-${key.dataSetId}")
            LaunchedEffect(key.dataSetId) { viewModel.bind(key.dataSetId) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            LabCompareScreen(
                state = state,
                onNavigateBack = navigateBack,
                onSelectDataSet = viewModel::selectDataSet,
                onChangeDataSet = viewModel::changeDataSet,
                onSelectLeftEngine = viewModel::selectLeftEngine,
                onSelectRightEngine = viewModel::selectRightEngine,
            )
        }
    }
}
