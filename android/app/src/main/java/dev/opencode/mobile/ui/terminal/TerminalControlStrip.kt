package dev.opencode.mobile.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun TerminalControlStrip(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctrlCLabel = stringResource(R.string.terminal_key_ctrl_c)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeButton(
            variant = OpenCodeButtonVariant.CompactText(CTRL_C_CAP),
            contrast = OpenCodeButtonContrast.Destructive,
            onClick = { onSend("\u0003") },
            modifier = Modifier.clearAndSetSemantics { contentDescription = ctrlCLabel },
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.CompactText(stringResource(R.string.terminal_key_esc)),
            contrast = OpenCodeButtonContrast.Low,
            onClick = { onSend("\u001b") },
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.CompactText(stringResource(R.string.terminal_key_tab)),
            contrast = OpenCodeButtonContrast.Low,
            onClick = { onSend("\t") },
        )
        Spacer(modifier = Modifier.weight(1f))
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.ArrowUpward, stringResource(R.string.terminal_key_up)),
            contrast = OpenCodeButtonContrast.Low,
            onClick = { onSend("\u001b[A") },
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.ArrowDownward, stringResource(R.string.terminal_key_down)),
            contrast = OpenCodeButtonContrast.Low,
            onClick = { onSend("\u001b[B") },
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.ArrowBack, stringResource(R.string.terminal_key_left)),
            contrast = OpenCodeButtonContrast.Low,
            onClick = { onSend("\u001b[D") },
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.ArrowForward, stringResource(R.string.terminal_key_right)),
            contrast = OpenCodeButtonContrast.Low,
            onClick = { onSend("\u001b[C") },
        )
    }
}

/** The Ctrl-C key cap is a keyboard symbol and stays as is; its spoken label is translated. */
private const val CTRL_C_CAP = "^C"
