package dev.opencode.hexcode.home

import dev.opencode.hexcode.designsystem.components.HexDialMode
import dev.opencode.voiceengine.EngineOptions
import dev.opencode.voiceengine.ModelFile
import dev.opencode.voiceengine.SpeechModel
import dev.opencode.voiceengine.SpeechModels
import dev.opencode.voiceengine.VoiceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** app-plan.md, Speech line and dial: the dial, the sheet's top, its phases and what it says about the model. */
class SpeechReadyTest {
    private val dropped = SpeechStatus.Loading(SpeechDownload(done = 160, total = 640, retryIn = 4))

    @Test
    fun theDialFollowsTheModel() {
        assertEquals(SpeechDialLook(HexDialMode.Downloading, 0.25f), SpeechDialLook.of(SpeechStatus.Loading(SpeechDownload(160, 640))))
        // Grey while the connection is down, and while the download hasn't started (no arc at all).
        assertEquals(SpeechDialLook(HexDialMode.Stalled, 0.25f), SpeechDialLook.of(dropped))
        assertEquals(SpeechDialLook(HexDialMode.Stalled, 0f), SpeechDialLook.of(SpeechStatus.Loading(stage = SpeechStage.NotOnPhone)))
        assertEquals(HexDialMode.Loading, SpeechDialLook.of(SpeechStatus.Loading()).mode)
        assertEquals(HexDialMode.WarmingUp, SpeechDialLook.of(SpeechStatus.Loading(stage = SpeechStage.WarmUp)).mode)
        assertEquals(SpeechDialLook(HexDialMode.Ready, 1f), SpeechDialLook.of(SpeechStatus.Ready))
        // Failed shows where the download stopped, or the full ring when it failed to load.
        assertEquals(SpeechDialLook(HexDialMode.Failed, 0.5f), SpeechDialLook.of(SpeechStatus.Failed, SpeechDownload(320, 640)))
        assertEquals(SpeechDialLook(HexDialMode.Failed, 1f), SpeechDialLook.of(SpeechStatus.Failed))
    }

    @Test
    fun thePhases() {
        val a = SpeechPhase.Ahead
        val n = SpeechPhase.Now
        val d = SpeechPhase.Done
        assertEquals(listOf(a, a, a, a), SpeechPhase.of(SpeechStatus.Loading(stage = SpeechStage.NotOnPhone), stopped = false))
        assertEquals(listOf(n, a, a, a), SpeechPhase.of(SpeechStatus.Loading(SpeechDownload(1, 2)), stopped = false))
        assertEquals(listOf(SpeechPhase.NowDropped, a, a, a), SpeechPhase.of(dropped, stopped = false))
        assertEquals(listOf(d, n, a, a), SpeechPhase.of(SpeechStatus.Loading(), stopped = false))
        assertEquals(listOf(d, d, n, a), SpeechPhase.of(SpeechStatus.Loading(stage = SpeechStage.WarmUp), stopped = false))
        assertEquals(listOf(d, d, d, d), SpeechPhase.of(SpeechStatus.Ready, stopped = false))
        // Red where it failed: in the download when it stopped short, else in the load.
        assertEquals(listOf(SpeechPhase.Failed, a, a, a), SpeechPhase.of(SpeechStatus.Failed, stopped = true))
        assertEquals(listOf(d, SpeechPhase.Failed, a, a), SpeechPhase.of(SpeechStatus.Failed, stopped = false))
    }

    @Test
    fun theHero() {
        assertEquals(SpeechHero.ConnectionDropped, SpeechHero.of(dropped))
        assertEquals(SpeechHero.Downloading, SpeechHero.of(SpeechStatus.Loading(SpeechDownload(1, 2))))
        assertEquals(SpeechHero.WarmingUp, SpeechHero.of(SpeechStatus.Loading(stage = SpeechStage.WarmUp)))
        assertEquals(SpeechHero.NotOnPhone, SpeechHero.of(SpeechStatus.Loading(stage = SpeechStage.NotOnPhone)))
    }

    @Test
    fun theModelComesFromTheModelList() {
        val model =
            SpeechModel(
                id = "parakeet-v2-onnx-int8",
                title = "Parakeet V2 · ONNX int8",
                engine = "sherpa-onnx",
                kind = "nemo_transducer",
                params = 0,
                license = "",
                source = "",
                note = "",
                librispeechWer = null,
                fleursWer = null,
                files = listOf(ModelFile("a", "", 400_000_000, ""), ModelFile("b", "", 261_190_513, "")),
            )
        val failed = VoiceEngine.ModelState.Failed("gone", done = 120_000_000, total = model.bytes + SpeechModels.PAUSES.bytes)
        val info = SpeechModelInfo.of(model, EngineOptions(), VoiceEngine.PausesState.NotOnPhone, failed)
        assertEquals("Parakeet V2", info.name)
        assertEquals("ONNX int8", info.variant)
        assertEquals(661_190_513L, info.bytes)
        assertEquals("ONNX Runtime, XNNPACK", info.runtimeDetail)
        assertEquals("Silero VAD v5", info.pausesName)
        // The pause detector comes with the first download.
        assertEquals(model.bytes + SpeechModels.PAUSES.bytes, info.toDownload)
        assertEquals(SpeechDownload(120_000_000, failed.total), info.stopped)
        // A model that failed to load didn't stop short.
        val loadFailed = VoiceEngine.ModelState.Failed("no memory", done = model.bytes, total = model.bytes)
        assertNull(SpeechModelInfo.of(model, EngineOptions(), VoiceEngine.PausesState.Downloaded, loadFailed).stopped)
    }
}
