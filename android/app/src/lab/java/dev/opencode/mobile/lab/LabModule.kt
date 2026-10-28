package dev.opencode.mobile.lab

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.BuildConfig
import dev.opencode.mobile.highlight.bench.HighlightBenchThreadGuard
import dev.opencode.mobile.markdown.HighlightBenchEngines
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.inject.Singleton

/** Report header lines: device, OS, and build, so on-device numbers say where they came from. */
data class LabDeviceInfo(
    val environment: List<String>,
) {
    companion object {
        fun current(): LabDeviceInfo =
            LabDeviceInfo(
                listOf(
                    // Escaped: these lines are Markdown, and model names like sdk_gphone64 have underscores.
                    "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()})".replace("_", "\\_"),
                    "OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    "Build: ${BuildConfig.BUILD_TYPE} ${BuildConfig.VERSION_NAME}" +
                        if (BuildConfig.DEBUG) " (debuggable: timings are not representative)" else "",
                    "Cores: ${Runtime.getRuntime().availableProcessors()}",
                ),
            )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object LabModule {
    @Provides
    @Singleton
    fun provideLabEngines(): LabEngines =
        LabEngines(
            engines = HighlightBenchEngines.available(),
            newCallGuard = { HighlightBenchThreadGuard() },
            dispatcher = Dispatchers.Default,
            onCancelledCallReturned = { engine, afterCancelMs ->
                Log.i(HighlightingLabHeadless.LOG_TAG, "Cancelled $engine call returned $afterCancelMs ms after Cancel")
            },
        )

    @Provides
    fun provideLabDataSetStore(): LabDataSetStore = LabDataSetStore.shared

    @Provides
    @Singleton
    fun provideLabReportStore(
        @ApplicationContext context: Context,
    ): LabReportStore = labReportStore(context)

    @Provides
    fun provideLabDeviceInfo(): LabDeviceInfo = LabDeviceInfo.current()
}

/** `files/highlight-lab/`, mirrored to external app storage when [mirrorExternally]. */
internal fun labReportStore(
    context: Context,
    mirrorExternally: Boolean = false,
): LabReportStore =
    LabReportStore(
        directory = File(context.filesDir, LabReportStore.DIRECTORY),
        mirrors =
            if (mirrorExternally) {
                listOfNotNull(context.getExternalFilesDir(null)?.let { File(it, LabReportStore.DIRECTORY) })
            } else {
                emptyList()
            },
    )
