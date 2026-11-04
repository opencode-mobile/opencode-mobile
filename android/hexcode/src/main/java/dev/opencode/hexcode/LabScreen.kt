package dev.opencode.hexcode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.voiceengine.EngineOptions
import dev.opencode.voiceengine.LabSummary
import dev.opencode.voiceengine.SpeechLab
import dev.opencode.voiceengine.SpeechModel
import dev.opencode.voiceengine.SpeechModels
import dev.opencode.voiceengine.VoiceEngine
import dev.opencode.voiceengine.Wer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Background = Color(0xFF0B0C0E)
private val Panel = Color(0xFF151618)
private val Line = Color(0xFF26282C)
private val Dim = Color(0xFF8A8F98)
private val Faint = Color(0xFF62666D)
private val Amber = Color(0xFFF2994A)
private val Green = Color(0xFF6FCF97)

/**
 * The speech model lab: download models, run the technical-English test clips through them on this phone,
 * compare how long you wait and how many words come out wrong, and pick the one dictation uses.
 * [autoRun] (debug builds) tests those model ids, or every downloaded one for "all", without a tap, with
 * [autoOptions] and the [autoSet] clip set if given.
 */
@Composable
fun LabScreen(
    engine: VoiceEngine,
    lab: SpeechLab,
    onBack: () -> Unit,
    autoRun: String? = null,
    autoOptions: EngineOptions? = null,
    autoSet: String? = null,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by engine.activeModel.collectAsState()
    val modelState by engine.modelState.collectAsState()
    var revision by remember { mutableIntStateOf(0) }
    var set by remember { mutableStateOf(autoSet ?: SpeechLab.TECHNICAL) }
    val summaries = remember(revision, set) { lab.summaries(set) }
    val installed = remember(revision) { engine.models.associate { it.id to engine.store.isInstalled(it) } }
    val downloads = remember { mutableStateMapOf<String, Double>() }
    var running by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf(autoOptions ?: engine.options) }

    suspend fun test(models: List<SpeechModel>) {
        for (m in models) {
            running = "${m.title} · loading"
            runCatching {
                engine.withModel(m, options) { e, loadMs -> lab.run(m, options, e, loadMs, set) { running = "${m.title} · $it" } }
            }.onFailure {
                message = "${m.title}: ${it.message}"
                Log.w("HexCodeLab", "${m.id} failed", it)
            }
            revision++
        }
        running = null
    }

    fun download(m: SpeechModel) {
        downloads[m.id] = 0.0
        scope.launch {
            runCatching {
                withContext(
                    Dispatchers.IO,
                ) { engine.store.download(m) { done, all -> downloads[m.id] = done.toDouble() / all } }
            }.onFailure { message = it.message }
            downloads.remove(m.id)
            revision++
        }
    }

    // a locked screen throttles the app and spoils the timings, so the screen stays on while tests run
    val view = LocalView.current
    DisposableEffect(running != null) {
        view.keepScreenOn = running != null
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(autoRun) {
        if (autoRun == null) return@LaunchedEffect
        val ids = autoRun.split(",")
        test(engine.models.filter { (autoRun == "all" || it.id in ids) && (engine.store.isInstalled(it) || it.files.isEmpty()) })
        message = listOfNotNull("Lab run finished", message).joinToString(" · ")
    }

    Column(Modifier.fillMaxSize().background(Background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Color.White, fontSize = 30.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
            Column(Modifier.weight(1f)) {
                Text("Speech models", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("${lab.device} · ${engine.deviceDetail}", color = Dim, fontSize = 12.5.sp, maxLines = 1)
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column {
                    Label("Dictation uses")
                    val status =
                        when (val s = modelState) {
                            is VoiceEngine.ModelState.Preparing -> " · ${s.detail}"
                            is VoiceEngine.ModelState.Failed -> " · ${s.message}"
                            else -> ""
                        }
                    Text("${active.title} · ${engine.options.label(active)}$status", color = Color.White, fontSize = 15.sp)
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("Run models on")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Fast cores", !options.allCores) { options = options.copy(allCores = false) }
                        Chip("All cores", options.allCores) { options = options.copy(allCores = true) }
                    }
                    Label("Test clips")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (name in lab.clipSets()) Chip(name, set == name) { set = name }
                    }
                    Label("ONNX Runtime provider (sherpa-onnx models)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (p in listOf("cpu", "xnnpack", "nnapi")) {
                            Chip(p.uppercase(), options.provider == p) {
                                options =
                                    options.copy(provider = p)
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button("Test all downloaded", enabled = running == null) {
                        scope.launch { test(engine.models.filter { installed[it.id] == true || it.files.isEmpty() }) }
                    }
                    Button("Share results", enabled = summaries.isNotEmpty(), filled = false) {
                        val send =
                            Intent(Intent.ACTION_SEND)
                                .setType("text/csv")
                                .putExtra(Intent.EXTRA_SUBJECT, "Hex Code speech model results")
                                .putExtra(Intent.EXTRA_TEXT, lab.csv())
                        context.startActivity(Intent.createChooser(send, "Share results"))
                    }
                }
                val line = running ?: message
                if (line != null) {
                    Text(line, color = if (running != null) Dim else Amber, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }

            if (summaries.isNotEmpty()) {
                item {
                    Column {
                        Label(
                            "Results on this phone · ${lab.clips.size} clips, ${"%.0f".format(lab.clips.sumOf { it.seconds })} s of speech",
                        )
                        ResultsHeader()
                    }
                }
                items(summaries, key = { it.modelId + it.options }) { s ->
                    val model = engine.models.first { it.id == s.modelId }
                    ResultRow(s, model, expanded == s.modelId + s.options) {
                        expanded = if (expanded == s.modelId + s.options) null else s.modelId + s.options
                    }
                }
                item {
                    Text(
                        "Wait: time from the end of speech to the text, per 10 s of speech. Errors: words wrong against the " +
                            "reference text. vs Hex: words that differ from ${engine.models.first {
                                it.id == SpeechModels.HEX_DEFAULT
                            }.title}.",
                        color = Faint,
                        fontSize = 12.sp,
                    )
                }
            }

            item { VoiceTest(engine, lab, options) { revision++ } }

            item { Label("Models") }
            items(engine.models, key = { it.id }) { m ->
                ModelCard(
                    model = m,
                    installed = installed[m.id] == true || m.files.isEmpty(),
                    downloading = downloads[m.id],
                    inUse = m.id == active.id,
                    busy = running != null,
                    onDownload = { download(m) },
                    onDelete = {
                        engine.store.delete(m)
                        revision++
                    },
                    onUse = { scope.launch { engine.use(m, options) } },
                    onTest = { scope.launch { test(listOf(m)) } },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ResultsHeader() {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
        Cell("Model", Modifier.weight(1f), Faint)
        Cell("Wait", Modifier.width(56.dp), Faint, end = true)
        Cell("Errors", Modifier.width(56.dp), Faint, end = true)
        Cell("vs Hex", Modifier.width(56.dp), Faint, end = true)
    }
}

@Composable
private fun ResultRow(
    s: LabSummary,
    model: SpeechModel,
    open: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.modelTitle, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("${s.engine} · ${s.options} · ${megabytes(model.bytes)}", color = Faint, fontSize = 11.5.sp)
            }
            Cell("%.2f s".format(s.waitPer10s), Modifier.width(56.dp), Color.White, end = true)
            Cell("%.1f%%".format(s.wer), Modifier.width(56.dp), Color.White, end = true)
            Cell(
                s.vsHex?.let { "%.1f%%".format(it) } ?: "–",
                Modifier.width(56.dp),
                if ((s.vsHex ?: 99.0) <
                    3
                ) {
                    Green
                } else {
                    Color.White
                },
                end = true,
            )
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Load %.1f s · first run %.2f s · longest clip (%.0f s) %.2f s · memory %d MB".format(
                    s.loadMs / 1000,
                    s.firstMs / 1000,
                    s.longestSeconds,
                    s.longestWait,
                    s.memoryMb,
                ),
                color = Dim,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            for (r in s.results) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${r.clipId} · %.2f s · %d of %d words wrong".format(r.waitMs / 1000, r.wordErrors, r.referenceWords),
                    color = Faint,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(r.text, color = if (r.wordErrors == 0) Color.White else Amber, fontSize = 13.sp)
            }
        }
    }
}

/** Read a test sentence aloud with the model dictation uses, and see the wait and the errors for a real voice. */
@Composable
private fun VoiceTest(
    engine: VoiceEngine,
    lab: SpeechLab,
    options: EngineOptions,
    onRecorded: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelState by engine.modelState.collectAsState()
    var index by remember { mutableIntStateOf(0) }
    var listening by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val sentence = lab.clips[index % lab.clips.size].reference
    val ready = modelState == VoiceEngine.ModelState.Ready
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Read this aloud", Modifier.weight(1f))
            Text("Next", color = Dim, fontSize = 13.sp, modifier = Modifier.clickable { index++ })
        }
        Text(sentence, color = Color.White, fontSize = 16.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .alpha(if (ready) 1f else 0.35f)
                .background(if (listening) Background else Color.White, CircleShape)
                .border(1.dp, if (listening) Color.White.copy(alpha = 0.2f) else Color.Transparent, CircleShape)
                .pointerInput(ready) {
                    if (!ready) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                permission.launch(Manifest.permission.RECORD_AUDIO)
                                return@detectTapGestures
                            }
                            val started = System.currentTimeMillis()
                            runCatching { engine.startRecording() }.onFailure {
                                outcome = it.message
                                return@detectTapGestures
                            }
                            listening = true
                            tryAwaitRelease()
                            listening = false
                            val seconds = (System.currentTimeMillis() - started) / 1000.0
                            val released = System.nanoTime()
                            scope.launch {
                                runCatching { engine.stopAndTranscribe() }
                                    .onSuccess { text ->
                                        val waitMs = (System.nanoTime() - released) / 1e6
                                        lab.recordVoice(engine.activeModel.value, options, sentence, seconds, text, waitMs)
                                        outcome =
                                            "$text\n%.2f s wait · %d of %d words wrong".format(
                                                waitMs / 1000,
                                                Wer.errors(sentence, text),
                                                Wer.words(sentence).size,
                                            )
                                        onRecorded()
                                    }.onFailure { outcome = it.message }
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (listening) "Let go to transcribe" else "Hold to talk · ${engine.activeModel.value.title}",
                color = if (listening) Color.White else Background,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        outcome?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Dim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ModelCard(
    model: SpeechModel,
    installed: Boolean,
    downloading: Double?,
    inUse: Boolean,
    busy: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onUse: () -> Unit,
    onTest: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, if (inUse) Color.White.copy(alpha = 0.5f) else Line, RoundedCornerShape(14.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (inUse) Text("In use", color = Green, fontSize = 12.sp)
        }
        val published =
            listOfNotNull(
                model.librispeechWer?.let { "LibriSpeech %.2f%%".format(it) },
                model.fleursWer?.let { "FLEURS %.2f%%".format(it) },
            ).joinToString(" · ").ifEmpty { "no published error rate" }
        Text(
            "${model.engine} · ${if (model.bytes > 0) megabytes(model.bytes) else "built in"} · $published",
            color = Faint,
            fontSize = 11.5.sp,
        )
        Text(model.note, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                downloading != null -> Text("Downloading · ${(downloading * 100).toInt()}%", color = Dim, fontSize = 13.sp)
                !installed -> Button("Download ${megabytes(model.bytes)}", enabled = true, onClick = onDownload)
                else -> {
                    Button("Test", enabled = !busy, onClick = onTest)
                    if (!inUse) Button("Use for dictation", enabled = !busy, filled = false, onClick = onUse)
                    if (model.files.isNotEmpty() && !inUse) Button("Delete", enabled = !busy, filled = false, onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun Label(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text.uppercase(),
        color = Faint,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun Cell(
    text: String,
    modifier: Modifier,
    color: Color,
    end: Boolean = false,
) {
    Text(
        text,
        color = color,
        fontSize = 12.5.sp,
        fontFamily = FontFamily.Monospace,
        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else null,
        modifier = modifier,
    )
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = if (selected) Background else Color.White,
        fontSize = 13.sp,
        modifier =
            Modifier
                .background(if (selected) Color.White else Panel, CircleShape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun Button(
    text: String,
    enabled: Boolean,
    filled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = if (filled) Background else Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .alpha(if (enabled) 1f else 0.35f)
                .background(if (filled) Color.White else Panel, CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun megabytes(bytes: Long) = "${bytes / 1_000_000} MB"
