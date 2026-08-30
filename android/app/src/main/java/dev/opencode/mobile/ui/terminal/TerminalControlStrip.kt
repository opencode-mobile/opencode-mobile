package dev.opencode.mobile.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun TerminalControlStrip(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeButton(
            variant = OpenCodeButtonVariant.CompactText("Ctrl-C"),
            contrast = OpenCodeButtonContrast.Destructive,
            onClick = { onSend("\u0003") },
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.CompactText("Esc"),
            contrast = OpenCodeButtonContrast.Low,
            onClick = { onSend("\u001b") },
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.CompactText("Tab"),
            contrast = OpenCodeButtonContrast.Low,
            onClick = { onSend("\t") },
        )
        Spacer(modifier = Modifier.weight(1f))
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.ArrowUpward, "Up"),
            contrast = OpenCodeButtonContrast.Low,
            onClick = { onSend("\u001b[A") },
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.ArrowDownward, "Down"),
            contrast = OpenCodeButtonContrast.Low,
            onClick = { onSend("\u001b[B") },
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.ArrowBack, "Left"),
            contrast = OpenCodeButtonContrast.Low,
            onClick = { onSend("\u001b[D") },
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.ChevronRight, "Right"),
            contrast = OpenCodeButtonContrast.Low,
            onClick = { onSend("\u001b[C") },
        )
    }
}
