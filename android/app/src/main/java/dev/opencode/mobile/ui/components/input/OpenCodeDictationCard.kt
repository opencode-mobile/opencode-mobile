package dev.opencode.mobile.ui.components

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.sin

enum class OpenCodeDictationCardVariant {
    Listening,
    Finishing,
    Unavailable,
}

enum class OpenCodeDictationUnavailableReason {
    PermissionDenied,
    ModelNotInstalled,
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
)

private const val DictationBarCount = 24
private val DictationOrbCanvasSize = 132.dp
private val DictationOrbSize = 64.dp
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
    onCancel: () -> Unit,
    onInsert: () -> Unit,
    onOpenSettings: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unavailable = variant == OpenCodeDictationCardVariant.Unavailable
    val transcript = committed + volatileTail
    val canInsert =
        !unavailable &&
            variant != OpenCodeDictationCardVariant.Finishing &&
            transcript.trim().isNotEmpty()
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (unavailable) {
            Text(
                text = unavailableMessage(unavailableReason, errorMessage),
                style = MaterialTheme.typography.bodyLarge,
                color = openCodeColor("text-strong"),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            )
        } else {
            DictationOrb(
                level = level,
                finishing = variant == OpenCodeDictationCardVariant.Finishing,
            )
            DictationTranscript(committed = committed, volatileTail = volatileTail)
            DictationStatusRow(
                languageLabel = languageLabel,
                onDevice = onDevice,
                elapsedLabel = elapsedLabel,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
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
                    onClick = {
                        when (recovery) {
                            DictationRecovery.OpenSettings -> onOpenSettings()
                            DictationRecovery.Download -> onDownload()
                            DictationRecovery.Retry -> onRetry()
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
}

@Composable
private fun DictationOrb(
    level: Float,
    finishing: Boolean,
) {
    val clamped = level.coerceIn(0f, 1f)
    val scale = 1f + clamped * 0.08f
    val color = openCodeColor(if (finishing) "success" else "interactive")
    val glyph = openCodeColor("text-on-brand-base")
    Box(
        modifier = Modifier.size(DictationOrbCanvasSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = this.center
            val inner = 40.dp.toPx()
            val minBar = 4.dp.toPx()
            val maxBar = 16.dp.toPx()
            val stroke = 2.5.dp.toPx()
            repeat(DictationBarCount) { index ->
                val angle = (index.toFloat() / DictationBarCount) * (2f * PI.toFloat()) - (PI.toFloat() / 2f)
                val envelope = 0.55f + 0.45f * ((sin(angle * 3f) + 1f) / 2f)
                val length = minBar + (maxBar - minBar) * clamped * envelope
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

@Composable
private fun DictationTranscript(
    committed: String,
    volatileTail: String,
) {
    val strong = openCodeColor("text-strong")
    val weak = openCodeColor("text-weak")
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = DictationTranscriptMaxHeight)
                .verticalScroll(rememberScrollState()),
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
    languageLabel: String,
    onDevice: Boolean,
    elapsedLabel: String,
) {
    val status =
        if (onDevice) {
            "Listening · $languageLabel · on-device"
        } else {
            "Listening · $languageLabel"
        }
    val weak = openCodeColor("text-weak")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(openCodeColor("error")),
        )
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

private fun unavailableMessage(
    reason: OpenCodeDictationUnavailableReason?,
    errorMessage: String?,
): String =
    when (reason) {
        OpenCodeDictationUnavailableReason.PermissionDenied -> "Microphone access is off"
        OpenCodeDictationUnavailableReason.ModelNotInstalled -> "The on-device speech model is not installed"
        OpenCodeDictationUnavailableReason.RecognizerError, null ->
            errorMessage?.takeIf { it.isNotBlank() } ?: "Dictation stopped"
    }

private enum class DictationRecovery(
    val title: String,
) {
    OpenSettings("Open Settings"),
    Download("Download"),
    Retry("Retry"),
}

private fun recoveryAction(reason: OpenCodeDictationUnavailableReason?): DictationRecovery =
    when (reason) {
        OpenCodeDictationUnavailableReason.PermissionDenied -> DictationRecovery.OpenSettings
        OpenCodeDictationUnavailableReason.ModelNotInstalled -> DictationRecovery.Download
        OpenCodeDictationUnavailableReason.RecognizerError, null -> DictationRecovery.Retry
    }
