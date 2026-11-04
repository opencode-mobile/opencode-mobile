package dev.opencode.hexcode

import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        // debug builds: `--es HexCodeSample <file in filesDir>` runs a recorded WAV as a take (emulators have no voice)
        val sample = if (BuildConfig.DEBUG) intent.getStringExtra("HexCodeSample") else null
        setContent { DictationScreen(engine, sampleFile = sample) }
    }

    override fun onDestroy() {
        engine.close()
        super.onDestroy()
    }
}
