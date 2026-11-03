package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

enum class OpenCodeKeyboardHintTone {
    /** Guidance, such as "Press Tab to answer with the keyboard". */
    Neutral,

    /** A pending keyboard action, such as "Esc again to interrupt". */
    Warning,
}

/** Horizontal inset that lines the hint up with composer and prompt content. */
private val KeyboardHintHorizontalInset = 4.dp

/**
 * One short line of hardware-keyboard guidance in caption text. Warning hints are announced
 * politely when they appear. The hint sets its own inset; callers place it in their layout.
 */
@Composable
fun OpenCodeKeyboardHint(
    text: String,
    modifier: Modifier = Modifier,
    tone: OpenCodeKeyboardHintTone = OpenCodeKeyboardHintTone.Neutral,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color =
            when (tone) {
                OpenCodeKeyboardHintTone.Neutral -> openCodeColor("text-weak")
                OpenCodeKeyboardHintTone.Warning -> openCodeColor("warning")
            },
        modifier =
            modifier
                .padding(horizontal = KeyboardHintHorizontalInset)
                .then(
                    if (tone == OpenCodeKeyboardHintTone.Warning) {
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    } else {
                        Modifier
                    },
                ),
    )
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeKeyboardHintPreview() {
    OpenCodeTheme {
        OpenCodeKeyboardHint(text = "Press Tab to answer with the keyboard")
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeKeyboardHintWarningPreview() {
    OpenCodeTheme {
        OpenCodeKeyboardHint(text = "Esc again to interrupt", tone = OpenCodeKeyboardHintTone.Warning)
    }
}
