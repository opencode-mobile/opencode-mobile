package dev.opencode.mobile.ui.components

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

enum class OpenCodeDictationCardVariant {
    Listening,

    /** Listening ended on its own; the orb rests and the transcript is ready to insert. */
    Stopped,
    Finishing,
    Unavailable,
}

enum class OpenCodeDictationUnavailableReason {
    PermissionDenied,
    ModelNotInstalled,

    /** Android is fetching the speech model; [OpenCodeDictationCard] shows its progress. */
    ModelDownloading,

    /** Android accepted the download but runs it later, for example on Wi-Fi. */
    ModelDownloadQueued,
    ModelDownloadFailed,
    RecognizerError,
}

data class ComposerDictationCardModel(
    val variant: OpenCodeDictationCardVariant,
    val committed: String,
    val volatileTail: String,
    val level: Float,
    val languageLabel: String,
    val onDevice: Boolean,
    val elapsedLabel: String,
    val unavailableReason: OpenCodeDictationUnavailableReason? = null,
    val errorMessage: String? = null,
    val downloadProgress: Float? = null,
)

private const val DictationBarCount = 24
private const val DictationOrbGrowth = 0.18f
private const val DictationHaloGrowth = 0.6f
private const val DictationHaloAlpha = 0.22f
private const val DictationAttackSeconds = 0.05f
private const val DictationReleaseSeconds = 0.22f
private val DictationOrbCanvasSize = 148.dp
private val DictationOrbSize = 64.dp
private val DictationBarOrbit = 40.dp
private val DictationBarMin = 4.dp
private val DictationBarMax = 28.dp
private val DictationTranscriptMaxHeight = 78.dp

@Composable
fun OpenCodeDictationCard(
    variant: OpenCodeDictationCardVariant,
    committed: String,
    volatileTail: String,
    level: Float,
    languageLabel: String,
    onDevice: Boolean,
    elapsedLabel: String,
    unavailableReason: OpenCodeDictationUnavailableReason? = null,
    errorMessage: String? = null,
    downloadProgress: Float? = null,
    onCancel: () -> Unit,
    onInsert: () -> Unit,
    onOpenSettings: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    showsActions: Boolean = true,
) {
    val unavailable = variant == OpenCodeDictationCardVariant.Unavailable
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (unavailable) {
            DictationUnavailableBody(
                reason = unavailableReason,
                errorMessage = errorMessage,
                languageLabel = languageLabel,
                downloadProgress = downloadProgress,
            )
        } else {
            DictationOrb(
                level = level,
                finishing = variant == OpenCodeDictationCardVariant.Finishing,
                stopped = variant == OpenCodeDictationCardVariant.Stopped,
            )
            DictationTranscript(committed = committed, volatileTail = volatileTail)
            DictationStatusRow(
                listening = variant != OpenCodeDictationCardVariant.Stopped,
                languageLabel = languageLabel,
                onDevice = onDevice,
                elapsedLabel = elapsedLabel,
            )
        }
        if (showsActions) {
            OpenCodeDictationCardActions(
                variant = variant,
                committed = committed,
                volatileTail = volatileTail,
                unavailableReason = unavailableReason,
                onCancel = onCancel,
                onInsert = onInsert,
                onOpenSettings = onOpenSettings,
                onDownload = onDownload,
                onRetry = onRetry,
            )
        }
    }
}

/**
 * Cancel plus Insert or the recovery action. [OpenCodeDictationCard] shows these itself;
 * a host with a pinned bottom bar passes `showsActions = false` and places them there,
 * so a long transcript never pushes Insert out of reach.
 */
@Composable
fun OpenCodeDictationCardActions(
    variant: OpenCodeDictationCardVariant,
    committed: String,
    volatileTail: String,
    unavailableReason: OpenCodeDictationUnavailableReason?,
    onCancel: () -> Unit,
    onInsert: () -> Unit,
    onOpenSettings: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unavailable = variant == OpenCodeDictationCardVariant.Unavailable
    val canInsert =
        !unavailable &&
            variant != OpenCodeDictationCardVariant.Finishing &&
            (committed + volatileTail).trim().isNotEmpty()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Cancel"),
            contrast = OpenCodeButtonContrast.Low,
            onClick = onCancel,
            modifier = Modifier.semantics { contentDescription = "Cancel dictation" },
        )
        if (unavailable) {
            val recovery = recoveryAction(unavailableReason)
            OpenCodeButton(
                variant = OpenCodeButtonVariant.FullWidthText(recovery.title),
                enabled = recovery != DictationRecovery.Downloading,
                onClick = {
                    when (recovery) {
                        DictationRecovery.OpenSettings -> onOpenSettings()
                        DictationRecovery.Download, DictationRecovery.DownloadAgain -> onDownload()
                        DictationRecovery.Retry, DictationRecovery.CheckAgain -> onRetry()
                        DictationRecovery.Downloading -> Unit
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.FullWidthText("Insert"),
                enabled = canInsert,
                onClick = onInsert,
                modifier = Modifier.semantics { contentDescription = "Insert transcript" },
            )
        }
    }
}

@Composable
private fun DictationOrb(
    level: Float,
    finishing: Boolean,
    stopped: Boolean,
) {
    val target by rememberUpdatedState(if (finishing || stopped) 0f else level.coerceIn(0f, 1f))
    // Match iOS Reduce Motion: with system animations off, the bars hold still.
    val stillBars = rememberAnimationsDisabled() || stopped
    var displayed by remember { mutableFloatStateOf(0f) }
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastFrameNanos = now
                seconds += dt
                displayed = smoothDictationLevel(displayed, target, dt)
            }
        }
    }
    val color = openCodeColor(if (finishing) "success" else "interactive")
    val glyph = openCodeColor("text-on-brand-base")
    Box(
        modifier = Modifier.size(DictationOrbCanvasSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = this.center
            val current = displayed
            val time = if (stillBars) 0f else seconds
            val orbRadius = DictationOrbSize.toPx() / 2f
            drawCircle(
                color = color.copy(alpha = DictationHaloAlpha * current),
                radius = orbRadius * (1f + DictationHaloGrowth * current),
                center = center,
            )
            val inner = DictationBarOrbit.toPx()
            val minBar = DictationBarMin.toPx()
            val maxBar = DictationBarMax.toPx()
            val stroke = 3.dp.toPx()
            repeat(DictationBarCount) { index ->
                val angle = (index.toFloat() / DictationBarCount) * (2f * PI.toFloat()) - (PI.toFloat() / 2f)
                val length = minBar + (maxBar - minBar) * dictationBarExtent(index, current, time)
                val cosAngle = cos(angle)
                val sinAngle = sin(angle)
                drawLine(
                    color = color,
                    start = Offset(center.x + cosAngle * inner, center.y + sinAngle * inner),
                    end = Offset(center.x + cosAngle * (inner + length), center.y + sinAngle * (inner + length)),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .size(DictationOrbSize)
                    .graphicsLayer {
                        val scale = 1f + DictationOrbGrowth * displayed
                        scaleX = scale
                        scaleY = scale
                    }.clip(CircleShape)
                    .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = OpenCodeIcons.Mic,
                contentDescription = null,
                tint = glyph,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** True when the user turned system animations off (animator duration scale 0). */
@Composable
private fun rememberAnimationsDisabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Fast attack, slower release, so speech pops and pauses settle. */
internal fun smoothDictationLevel(
    current: Float,
    target: Float,
    dtSeconds: Float,
): Float {
    if (dtSeconds <= 0f) return current
    val tau = if (target > current) DictationAttackSeconds else DictationReleaseSeconds
    return current + (target - current) * (1f - exp(-dtSeconds / tau))
}

/** Bar length as a fraction of its range: a small idle breath plus a per-bar wave scaled by level. */
internal fun dictationBarExtent(
    index: Int,
    level: Float,
    seconds: Float,
): Float {
    val wave = 0.5f + 0.25f * sin(seconds * 7f + index * 0.9f) + 0.25f * sin(seconds * 11f - index * 1.3f)
    val idle = 0.06f * (0.5f + 0.5f * sin(seconds * 2.4f + index * 0.5f))
    return (idle + level * (0.35f + 0.65f * wave)).coerceIn(0f, 1f)
}

@Composable
private fun DictationTranscript(
    committed: String,
    volatileTail: String,
) {
    val strong = openCodeColor("text-strong")
    val weak = openCodeColor("text-weak")
    val scrollState = rememberScrollState()
    // Follow the newest words as the transcript grows past the visible lines.
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.maxValue }.collect { scrollState.animateScrollTo(it) }
    }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = DictationTranscriptMaxHeight)
                .verticalScroll(scrollState),
        contentAlignment = Alignment.Center,
    ) {
        if (committed.isEmpty() && volatileTail.isEmpty()) {
            Text(
                text = "Listening…",
                style = MaterialTheme.typography.bodyLarge,
                color = weak,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text =
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = strong)) { append(committed) }
                        withStyle(SpanStyle(color = weak)) { append(volatileTail) }
                    },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DictationStatusRow(
    listening: Boolean,
    languageLabel: String,
    onDevice: Boolean,
    elapsedLabel: String,
) {
    val state = if (listening) "Listening" else "Ready to insert"
    val status =
        if (onDevice) {
            "$state · $languageLabel · on-device"
        } else {
            "$state · $languageLabel"
        }
    val weak = openCodeColor("text-weak")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (listening) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(openCodeColor("error")),
            )
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = weak,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = elapsedLabel,
            style = MaterialTheme.typography.bodySmall,
            color = weak,
        )
    }
}

@Composable
private fun DictationUnavailableBody(
    reason: OpenCodeDictationUnavailableReason?,
    errorMessage: String?,
    languageLabel: String,
    downloadProgress: Float?,
) {
    val copy = unavailableCopy(reason, errorMessage, languageLabel)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = copy.title,
            style = MaterialTheme.typography.bodyLarge,
            color = openCodeColor("text-strong"),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (reason == OpenCodeDictationUnavailableReason.ModelDownloading) {
            DictationDownloadProgress(progress = downloadProgress)
        }
        copy.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = openCodeColor("text-weak"),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DictationDownloadProgress(progress: Float?) {
    val track = openCodeColor("border-weak-base")
    val indicator = openCodeColor("interactive")
    val label = progress?.let { "${(it.coerceIn(0f, 1f) * 100).roundToInt()}%" } ?: "Starting…"
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (progress == null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Downloading speech model" },
                color = indicator,
                trackColor = track,
            )
        } else {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Downloading speech model, $label" },
                color = indicator,
                trackColor = track,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-weak"),
        )
    }
}

internal data class DictationUnavailableCopy(
    val title: String,
    val detail: String?,
)

/** Model copy names Android, so a slow or deferred system download is not read as an app fault. */
internal fun unavailableCopy(
    reason: OpenCodeDictationUnavailableReason?,
    errorMessage: String?,
    languageLabel: String,
): DictationUnavailableCopy {
    val model = if (languageLabel.isBlank()) "speech model" else "$languageLabel speech model"
    return when (reason) {
        OpenCodeDictationUnavailableReason.PermissionDenied ->
            DictationUnavailableCopy("Microphone access is off", null)
        OpenCodeDictationUnavailableReason.ModelNotInstalled ->
            DictationUnavailableCopy(
                title = "Android needs its $model",
                detail =
                    "Dictation runs on this phone, so your voice is never uploaded. " +
                        "Android downloads the model once and keeps it for every app.",
            )
        OpenCodeDictationUnavailableReason.ModelDownloading ->
            DictationUnavailableCopy(
                title = "Android is downloading the $model",
                detail = "This is a system download. It keeps going if you close this card.",
            )
        OpenCodeDictationUnavailableReason.ModelDownloadQueued ->
            DictationUnavailableCopy(
                title = "Android will download the $model later",
                detail =
                    "Android queued the download. It may wait for Wi-Fi, charging, or free storage. " +
                        "Check again in a few minutes.",
            )
        OpenCodeDictationUnavailableReason.ModelDownloadFailed ->
            DictationUnavailableCopy(
                title = "Android couldn't download the $model",
                detail = "Check the connection and free storage on this phone, then try again.",
            )
        OpenCodeDictationUnavailableReason.RecognizerError, null ->
            DictationUnavailableCopy(errorMessage?.takeIf { it.isNotBlank() } ?: "Dictation stopped", null)
    }
}

private enum class DictationRecovery(
    val title: String,
) {
    OpenSettings("Open Settings"),
    Download("Download"),
    Downloading("Downloading…"),
    CheckAgain("Check Again"),
    DownloadAgain("Try Again"),
    Retry("Retry"),
}

private fun recoveryAction(reason: OpenCodeDictationUnavailableReason?): DictationRecovery =
    when (reason) {
        OpenCodeDictationUnavailableReason.PermissionDenied -> DictationRecovery.OpenSettings
        OpenCodeDictationUnavailableReason.ModelNotInstalled -> DictationRecovery.Download
        OpenCodeDictationUnavailableReason.ModelDownloading -> DictationRecovery.Downloading
        OpenCodeDictationUnavailableReason.ModelDownloadQueued -> DictationRecovery.CheckAgain
        OpenCodeDictationUnavailableReason.ModelDownloadFailed -> DictationRecovery.DownloadAgain
        OpenCodeDictationUnavailableReason.RecognizerError, null -> DictationRecovery.Retry
    }
