package dev.opencode.mobile.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * A [SelectionContainer] whose selection does not survive a configuration change
 * or process restore. Use it instead of [SelectionContainer] everywhere in the app.
 *
 * Foundation 1.12 saves a selection with the flag that says whether its handles
 * are crossed, and in one case that flag is wrong: a backwards selection whose
 * end handle was dragged up within one Text, then past that Text's right edge.
 * A live drag repairs the flag before drawing; a restored selection is drawn
 * unrepaired, and SelectionController.draw passes start > end to
 * getPathForRange, which throws on the main thread (#1042). Holding the state in
 * a plain remember means nothing is restored. Remove this once the BOM ships
 * Foundation 1.13 stable, then go back to the plain SelectionContainer.
 */
@Composable
fun OpenCodeSelectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    SelectionContainer(state = remember { SelectionState() }, modifier = modifier, content = content)
}
