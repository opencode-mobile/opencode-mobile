package dev.opencode.hexcode.home

import dev.opencode.hexcode.designsystem.components.HexDialMode
import dev.opencode.voiceengine.EngineOptions
import dev.opencode.voiceengine.SpeechModel
import dev.opencode.voiceengine.SpeechModels
import dev.opencode.voiceengine.VoiceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * The speech model as its details sheet shows it (app-plan.md, Speech line and dial › Details sheet): the model's name
 * and sizes, from the model list, what runs it, and the pause detector. Never hard-coded.
 */
data class SpeechModelInfo(
    /** "Parakeet V2": the model list's title, before its " · ". */
    val name: String,
    /** "ONNX int8": the rest of the title, if any. */
    val variant: String?,
    val bytes: Long,
    /** "sherpa-onnx": what runs it. */
    val runtime: String,
    /** "ONNX Runtime, XNNPACK". */
    val runtimeDetail: String?,
    /** "Silero VAD v5". */
    val pausesName: String,
    val pausesBytes: Long,
    val pauses: VoiceEngine.PausesState,
    /** Bytes to fetch before it's on the phone: the model, and the pause detector while it's missing. */
    val toDownload: Long = bytes,
    /** Where the download stopped when it failed; null when it failed to load, or hasn't failed. */
    val stopped: SpeechDownload? = null,
) {
    companion object {
        /** What [engine]'s chosen model and pause detector are now, as they change. */
        fun of(engine: VoiceEngine): Flow<SpeechModelInfo> =
            combine(engine.activeModel, engine.pauses, engine.modelState) { model, pauses, state ->
                of(model, engine.options, pauses, state)
            }

        fun of(
            model: SpeechModel,
            options: EngineOptions,
            pauses: VoiceEngine.PausesState,
            state: VoiceEngine.ModelState,
        ): SpeechModelInfo {
            val title = model.title.split(" · ", limit = 2)
            val detector = SpeechModels.PAUSES
            return SpeechModelInfo(
                name = title.first(),
                variant = title.getOrNull(1),
                bytes = model.bytes,
                runtime = model.engine,
                runtimeDetail =
                    when (model.engine) {
                        "sherpa-onnx" -> "ONNX Runtime, ${options.provider.uppercase()}"
                        "litert" -> "LiteRT"
                        else -> null
                    },
                pausesName = detector.title,
                pausesBytes = detector.bytes,
                pauses = pauses,
                toDownload = model.bytes + if (pauses == VoiceEngine.PausesState.NotOnPhone) detector.bytes else 0,
                stopped =
                    (state as? VoiceEngine.ModelState.Failed)
                        ?.takeIf { it.total > 0 && it.done < it.total }
                        ?.let { SpeechDownload(it.done, it.total) },
            )
        }

        /** No model to describe: a stand-in for tests and previews. */
        val None: StateFlow<SpeechModelInfo?> = MutableStateFlow<SpeechModelInfo?>(null).asStateFlow()
    }
}

/** The sheet's top: the dial's title, in its colour (app-plan.md, Speech line and dial › Details sheet). */
enum class SpeechHero {
    NotOnPhone,
    Downloading,
    ConnectionDropped,
    Loading,
    WarmingUp,
    Ready,
    Failed,
    ;

    companion object {
        fun of(status: SpeechStatus): SpeechHero =
            when (status) {
                SpeechStatus.Ready -> Ready
                SpeechStatus.Failed -> Failed
                is SpeechStatus.Loading ->
                    when (status.stage) {
                        SpeechStage.NotOnPhone -> NotOnPhone
                        SpeechStage.Download -> if (status.download?.retryIn != null) ConnectionDropped else Downloading
                        SpeechStage.Load -> Loading
                        SpeechStage.WarmUp -> WarmingUp
                    }
            }
    }
}

/** One of the sheet's phases, Download → Load → Warm up → Ready. */
enum class SpeechPhase {
    /** Not reached yet. */
    Ahead,

    /** Where it is now, highlighted. */
    Now,

    /** Now, while the connection is down: amber. */
    NowDropped,

    /** Done, with a check. */
    Done,

    /** Where it failed: red. */
    Failed,
    ;

    companion object {
        /** The four phases' looks for [status]; a failure counts as the download's when it stopped short ([stopped]). */
        fun of(
            status: SpeechStatus,
            stopped: Boolean,
        ): List<SpeechPhase> {
            val hero = SpeechHero.of(status)
            val current =
                when (hero) {
                    SpeechHero.NotOnPhone -> -1
                    SpeechHero.Downloading, SpeechHero.ConnectionDropped -> 0
                    SpeechHero.Loading -> 1
                    SpeechHero.WarmingUp -> 2
                    SpeechHero.Ready -> 4
                    SpeechHero.Failed -> if (stopped) 0 else 1
                }
            return List(4) { i ->
                when {
                    i < current -> Done
                    i > current -> Ahead
                    hero == SpeechHero.Failed -> Failed
                    hero == SpeechHero.ConnectionDropped -> NowDropped
                    else -> Now
                }
            }
        }
    }
}

/** The dial's look for [status]: its [mode] and the share of the ring its arc fills ([progress]). */
data class SpeechDialLook(
    val mode: HexDialMode,
    val progress: Float,
) {
    companion object {
        fun of(
            status: SpeechStatus,
            stopped: SpeechDownload? = null,
        ): SpeechDialLook =
            when (status) {
                SpeechStatus.Ready -> SpeechDialLook(HexDialMode.Ready, 1f)
                SpeechStatus.Failed -> SpeechDialLook(HexDialMode.Failed, stopped?.fraction ?: 1f)
                is SpeechStatus.Loading ->
                    when (status.stage) {
                        SpeechStage.NotOnPhone -> SpeechDialLook(HexDialMode.Stalled, 0f)
                        SpeechStage.Download -> {
                            val download = status.download
                            val progress = download?.fraction ?: 0f
                            if (download?.retryIn != null) {
                                SpeechDialLook(HexDialMode.Stalled, progress)
                            } else {
                                SpeechDialLook(HexDialMode.Downloading, progress)
                            }
                        }
                        SpeechStage.Load -> SpeechDialLook(HexDialMode.Loading, 1f)
                        SpeechStage.WarmUp -> SpeechDialLook(HexDialMode.WarmingUp, 1f)
                    }
            }
    }
}

/** What the speech line above the dock shows (app-plan.md, Speech line and dial › Speech line). */
enum class SpeechLineShow {
    /** Nothing: the model was ready, or the line has had its moment. */
    Hidden,

    /** The model is getting ready, or failed. */
    GettingReady,

    /** "Speech ready" with the green dial, for [SpeechLineMemory.READY_MS], then gone. */
    Ready,
}

/**
 * The speech line's memory for the whole process: whether it showed the model getting ready, and whether it has gone.
 * Once gone, it doesn't come back in this process, whichever home is on screen.
 */
class SpeechLineMemory {
    var sawGettingReady = false
    var gone = false

    /** What the line shows next for [status]; [Ready], said once per process, is the cue to hide it after [READY_MS]. */
    fun next(status: SpeechStatus): SpeechLineShow =
        when {
            gone -> SpeechLineShow.Hidden
            status != SpeechStatus.Ready -> {
                sawGettingReady = true
                SpeechLineShow.GettingReady
            }
            sawGettingReady -> {
                // Gone from now on, so a home made during the 1.6 s (or after it) doesn't say it again.
                gone = true
                SpeechLineShow.Ready
            }
            else -> {
                // Ready before the line ever showed: nothing to say.
                gone = true
                SpeechLineShow.Hidden
            }
        }

    companion object {
        /** The process's one memory. */
        val Process = SpeechLineMemory()

        /** How long "Speech ready" shows before it fades out. */
        const val READY_MS = 1_600L
    }
}
