package dev.opencode.mobile.lab

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Highlighting Lab home: Data sets, Benchmark, Compare. */
@Serializable data object LabHome : NavKey

@Serializable data object LabDataSets : NavKey

/** Pasted text with a language picker, opened as a custom data set. */
@Serializable data object LabPaste : NavKey

/** One data set highlighted, with its timing strip and an engine picker. */
@Serializable data class LabViewer(
    val dataSetId: String,
) : NavKey

@Serializable data object LabBenchmark : NavKey

/** Two engines on one data set; with no [dataSetId] the screen asks for one first. */
@Serializable data class LabCompare(
    val dataSetId: String? = null,
) : NavKey
