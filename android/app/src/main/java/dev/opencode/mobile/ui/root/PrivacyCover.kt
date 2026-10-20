package dev.opencode.mobile.ui.root

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** What the cover shows: nothing, a plain cover, or a cover with an Unlock action. */
data class PrivacyCoverState(
    val covered: Boolean = false,
    val locked: Boolean = false,
)

/**
 * Opaque surface over the whole window while the app is not in the foreground or the app
 * lock is engaged (#755). Swallows touches so nothing underneath reacts, and while locked
 * takes focus so a text field underneath cannot keep receiving keyboard input.
 */
@Composable
fun PrivacyCover(
    state: PrivacyCoverState,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.covered && !state.locked) return
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Keyed on the lock, so a cover already up from onPause still takes focus when it locks; moving
    // focus off a text field closes its input connection, so the IME cannot type underneath.
    LaunchedEffect(state.locked) {
        if (state.locked) {
            focusRequester.requestFocus()
            keyboard?.hide()
        }
    }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Being a pointer-input target stops hit testing from reaching the content
                // underneath; consuming in the Main pass still lets the Unlock button click first.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                }.focusRequester(focusRequester)
                .focusable()
                .semantics { contentDescription = if (state.locked) "OpenCode Mobile is locked" else "OpenCode Mobile" },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = OpenCodeIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "OpenCode Mobile",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (state.locked) {
                Spacer(Modifier.height(24.dp))
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.FullWidthText("Unlock"),
                    onClick = onUnlock,
                )
            }
        }
    }
}
