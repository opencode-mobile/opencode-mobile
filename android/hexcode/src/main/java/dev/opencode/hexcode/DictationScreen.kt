package dev.opencode.hexcode

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.voiceengine.VoiceEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val Background = Color(0xFF0B0C0E)
private val Panel = Color(0xFF151618)
private val Dim = Color(0xFF8A8F98)
private val Faint = Color(0xFF62666D)
private val Amber = Color(0xFFF2994A)

private data class Take(
    val text: String,
    val seconds: Double,
    val latency: Double,
)

private enum class Phase { Idle, Listening, Transcribing }

/**
 * First working piece of Hex Code: hold to talk, let go, and Hex's engine turns the take into text on the phone.
 * Each take shows how long the text took to appear, to measure the engine on real devices.
 */
@Composable
fun DictationScreen(
    engine: VoiceEngine,
    sampleFile: String? = null,
    onOpenLab: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelState by engine.modelState.collectAsState()
    val takes = remember { mutableStateListOf<Take>() }
    var phase by remember { mutableStateOf(Phase.Idle) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    val ready = modelState == VoiceEngine.ModelState.Ready && phase != Phase.Transcribing
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) errorText = "Microphone access is off. Turn it on in Settings."
        }

    val active by engine.activeModel.collectAsState()
    LaunchedEffect(Unit) {
        engine.prepare()
        if (sampleFile != null && engine.modelState.value == VoiceEngine.ModelState.Ready) {
            phase = Phase.Transcribing
            val started = System.nanoTime()
            runCatching { engine.transcribe(VoiceEngine.readWav(File(context.filesDir, sampleFile))) }
                .onSuccess { takes += Take(it, 0.0, (System.nanoTime() - started) / 1e9) }
                .onFailure { errorText = it.message }
            phase = Phase.Idle
        }
    }

    Column(Modifier.fillMaxSize().background(Background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            HexCodeMark(Modifier.size(26.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Dictation", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                val status =
                    when (val s = modelState) {
                        is VoiceEngine.ModelState.Preparing -> "Getting speech ready · ${(s.fraction * 100).toInt()}% · ${s.detail}"
                        is VoiceEngine.ModelState.Failed -> s.message
                        else -> "${active.title} · on this phone"
                    }
                Text(status, color = if (modelState is VoiceEngine.ModelState.Failed) Amber else Dim, fontSize = 12.5.sp, maxLines = 1)
            }
            if (modelState is VoiceEngine.ModelState.Failed) {
                Text(
                    "Retry",
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier =
                        Modifier.pointerInput(Unit) {
                            detectTapGestures { scope.launch { engine.prepare() } }
                        },
                )
            } else if (takes.isNotEmpty()) {
                Text("Clear", color = Dim, fontSize = 15.sp, modifier = Modifier.pointerInput(Unit) { detectTapGestures { takes.clear() } })
            }
            Spacer(Modifier.size(18.dp))
            // the speech model lab: try other models and engines, and pick the one dictation uses
            Text("Models", color = Dim, fontSize = 15.sp, modifier = Modifier.pointerInput(Unit) { detectTapGestures { onOpenLab() } })
        }

        val listState = rememberLazyListState()
        LaunchedEffect(takes.size) { if (takes.isNotEmpty()) listState.animateScrollToItem(takes.size) }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(takes) { take ->
                Column {
                    Text(take.text, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 29.sp)
                    val what = if (take.seconds == 0.0) "sample file" else "${clock(take.seconds)} take"
                    Text(
                        "$what · text in ${"%.2f".format(take.latency)} s",
                        color = Faint,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            item {
                when (phase) {
                    Phase.Listening -> {
                        Column {
                            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                            LaunchedEffect(Unit) {
                                while (true) {
                                    now = System.currentTimeMillis()
                                    delay(250)
                                }
                            }
                            Text(
                                "Listening… ${clock((now - startedAt) / 1000.0)}",
                                color = Faint,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(10.dp))
                            LevelMeter(engine, Modifier.fillMaxWidth().height(28.dp))
                        }
                    }
                    Phase.Transcribing ->
                        Text(
                            "Transcribing on this phone…",
                            color = Faint,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    Phase.Idle -> {
                        val hint =
                            errorText ?: if (takes.isNotEmpty()) {
                                null
                            } else if (modelState == VoiceEngine.ModelState.Ready) {
                                "Hold the button and talk. Text appears when you let go."
                            } else {
                                "The speech model downloads once, then everything stays on the phone."
                            }
                        if (hint != null) Text(hint, color = if (errorText != null) Amber else Dim, fontSize = 15.sp)
                    }
                }
            }
        }

        val listening = phase == Phase.Listening
        Box(
            Modifier
                .padding(horizontal = 22.dp, vertical = 10.dp)
                .fillMaxWidth()
                .height(56.dp)
                .alpha(if (ready) 1f else 0.35f)
                .background(if (listening) Panel else Color.White, CircleShape)
                .border(1.dp, if (listening) Color.White.copy(alpha = 0.2f) else Color.Transparent, CircleShape)
                .pointerInput(ready) {
                    if (!ready) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                permission.launch(Manifest.permission.RECORD_AUDIO)
                                return@detectTapGestures
                            }
                            errorText = null
                            startedAt = System.currentTimeMillis()
                            runCatching { engine.startRecording() }.onFailure {
                                errorText = it.message
                                return@detectTapGestures
                            }
                            phase = Phase.Listening
                            tryAwaitRelease()
                            val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
                            phase = Phase.Transcribing
                            val released = System.nanoTime()
                            scope.launch {
                                runCatching { engine.stopAndTranscribe() }
                                    .onSuccess { takes += Take(it, seconds, (System.nanoTime() - released) / 1e9) }
                                    .onFailure { errorText = it.message }
                                phase = Phase.Idle
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (listening) "Let go to transcribe" else "Hold to talk",
                color = if (listening) Color.White else Background,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Thin bars driven by the take's loudness. */
@Composable
private fun LevelMeter(
    engine: VoiceEngine,
    modifier: Modifier = Modifier,
) {
    val history = remember { mutableStateListOf<Float>().apply { repeat(40) { add(0f) } } }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            history.removeAt(0)
            history.add(engine.level)
            delay(33)
        }
    }
    Canvas(modifier) {
        val w = size.width / history.size
        history.forEachIndexed { i, v ->
            val h = maxOf(3f, v * size.height)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.85f),
                topLeft = Offset(i * w + w * 0.3f, (size.height - h) / 2),
                size = Size(w * 0.4f, h),
                cornerRadius = CornerRadius(w * 0.2f),
            )
        }
    }
}

private fun clock(seconds: Double): String {
    val s = seconds.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
