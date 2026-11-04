package dev.opencode.hexcode

import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.opencode.voiceengine.EngineOptions
import dev.opencode.voiceengine.SpeechLab
import dev.opencode.voiceengine.VoiceEngine

class MainActivity : ComponentActivity() {
    private lateinit var engine: VoiceEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the app is dark for now, so the system bars keep light icons
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(TRANSPARENT),
        )
        engine = VoiceEngine(this)
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
        setContent {
            var inLab by remember { mutableStateOf(labRun != null) }
            if (inLab) {
                LabScreen(engine, lab, onBack = { inLab = false }, autoRun = labRun, autoOptions = labOptions, autoSet = labSet)
            } else {
                DictationScreen(engine, sampleFile = sample, onOpenLab = { inLab = true })
            }
        }
    }

    override fun onDestroy() {
        engine.close()
        super.onDestroy()
    }
}
