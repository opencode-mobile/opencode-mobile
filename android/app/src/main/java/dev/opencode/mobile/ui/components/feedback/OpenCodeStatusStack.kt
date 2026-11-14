package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class OpenCodeStatusStackStyle {
    /** Neutral ring with a title and subtitle. */
    Labeled,

    /** Ring only, for inline and overlay loading. */
    Spinner,
}

/** When an [OpenCodeStatusStack] shows itself. */
enum class OpenCodeStatusStackReveal(
    val graceMillis: Long,
) {
    /** Visible on the first frame. */
    Immediate(0L),

    /**
     * Blank for a short grace first, so a fast load (a disk cache paint) replaces it before
     * the user ever sees a loader frame.
     */
    AfterGrace(300L),
}

/** Emits `false` until [reveal]'s grace has passed, then `true`; immediate reveals emit `true` only. */
fun statusStackVisibility(reveal: OpenCodeStatusStackReveal): Flow<Boolean> =
    flow {
        if (reveal.graceMillis > 0) {
            emit(false)
            delay(reveal.graceMillis)
        }
        emit(true)
    }

/** Centered mid-well indeterminate status: neutral ring + title + subtitle, or a ring alone. */
@Composable
fun OpenCodeStatusStack(
    title: String = "",
    subtitle: String = "",
    modifier: Modifier = Modifier,
    style: OpenCodeStatusStackStyle = OpenCodeStatusStackStyle.Labeled,
    testTag: String? = null,
    reveal: OpenCodeStatusStackReveal = OpenCodeStatusStackReveal.Immediate,
) {
    if (style == OpenCodeStatusStackStyle.Labeled) {
        require(title.isNotBlank()) { "OpenCodeStatusStack requires a non-blank title" }
        require(subtitle.isNotBlank()) { "OpenCodeStatusStack requires a non-blank subtitle" }
    } else {
        require(title.isNotBlank()) { "OpenCodeStatusStack spinner requires a non-blank accessibility label" }
    }

    val visible by remember(reveal) { statusStackVisibility(reveal) }
        .collectAsState(initial = reveal == OpenCodeStatusStackReveal.Immediate)
    if (!visible) {
        // Holds the layout slot so the reveal does not move surrounding content.
        Box(modifier = modifier.fillMaxWidth())
        return
    }

    val ringColor = openCodeColor("text-base")
    val trackColor = openCodeColor("border-weaker-base")
    val subtitleColor = openCodeColor("text-weaker")

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        if (style == OpenCodeStatusStackStyle.Labeled) {
                            "$title. $subtitle"
                        } else {
                            title
                        }
                    if (testTag != null) {
                        this.testTag = testTag
                    }
                },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = ringColor,
            trackColor = trackColor,
            strokeWidth = 2.5.dp,
        )
        if (style == OpenCodeStatusStackStyle.Labeled) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = subtitleColor,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeStatusStackHomePreview() {
    OpenCodeTheme {
        OpenCodeStatusStack(
            title = "Loading sessions",
            subtitle = "Checking server…",
            modifier = Modifier.height(320.dp).fillMaxSize(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeStatusStackSessionPreview() {
    OpenCodeTheme {
        OpenCodeStatusStack(
            title = "Loading messages",
            subtitle = "Fetching timeline…",
            testTag = "sessionChatLoading",
            modifier = Modifier.height(320.dp).fillMaxSize(),
        )
    }
}
