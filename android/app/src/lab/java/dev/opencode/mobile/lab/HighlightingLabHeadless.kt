package dev.opencode.mobile.lab

import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.opencode.mobile.highlight.bench.HighlightBenchHeadless
import dev.opencode.mobile.highlight.bench.HighlightBenchThreadGuard
import dev.opencode.mobile.markdown.HighlightBenchEngines
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unattended benchmark runs (#872):
 * `adb shell am start -n dev.opencode.mobile/.MainActivity --es labRun quick:engines=textmate,semantic-fallback`
 * (value format in [HighlightBenchHeadless]). The run happens off the main thread in the process
 * scope; the activity shows progress, writes `latest.md`/`latest.json` plus timestamped copies to
 * `files/highlight-lab/` (mirrored to external app storage for `adb pull` on benchmark builds),
 * logs each path under [LOG_TAG], and finishes.
 */
object HighlightingLabHeadless {
    const val LOG_TAG = "HighlightingLab"

    private val status = mutableStateOf("Starting")

    @Volatile
    private var running = false

    /** True when [intent] asked for a run: this activity then belongs to it. */
    fun start(
        activity: ComponentActivity,
        intent: Intent?,
    ): Boolean {
        val value = intent?.getStringExtra(HighlightBenchHeadless.ARGUMENT_NAME) ?: return false
        // The extra stays on the intent, so a recreated activity only shows the run in progress.
        intent.removeExtra(HighlightBenchHeadless.ARGUMENT_NAME)
        activity.setContent {
            val text by status
            OpenCodeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    OpenCodeStatusStack(title = "Highlighting Lab run", subtitle = text, modifier = Modifier.fillMaxSize())
                }
            }
        }
        if (running) {
            Log.w(LOG_TAG, "labRun ignored, a run is in progress: $value")
            return true
        }
        running = true
        status.value = value
        val context = activity.applicationContext
        val store = labReportStore(context, mirrorExternally = true)
        Log.i(LOG_TAG, "labRun started: $value")
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            val outcome =
                withContext(Dispatchers.Default) {
                    runCatching {
                        val result =
                            HighlightBenchHeadless.run(
                                value = value,
                                available = HighlightBenchEngines.available(),
                                callGuard = HighlightBenchThreadGuard(),
                                environment = LabDeviceInfo.current().environment + "Run: labRun $value",
                            ) { progress ->
                                val line = "${progress.index + 1}/${progress.total} ${progress.case.id} on ${progress.engineId}"
                                if (progress.result == null) {
                                    status.value = line
                                } else {
                                    Log.i(LOG_TAG, "$line: ${progress.result?.status}")
                                }
                            }
                        store.save(result.markdown, result.json) to result.report.isComplete
                    }
                }
            outcome
                .onSuccess { (saved, complete) ->
                    store.directories.forEach { dir -> Log.i(LOG_TAG, "labRun wrote ${dir.resolve("latest.md")} and latest.json") }
                    Log.i(LOG_TAG, "labRun report: ${saved.markdownFile} ${saved.jsonFile}")
                    Log.i(LOG_TAG, "labRun finished: complete=$complete")
                }.onFailure { failure ->
                    Log.e(LOG_TAG, "labRun failed: ${failure.message}", failure)
                }
            running = false
            activity.finish()
        }
        return true
    }
}
