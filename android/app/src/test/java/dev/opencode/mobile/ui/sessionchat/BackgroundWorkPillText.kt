package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.ui.text.UiText
import dev.opencode.mobile.ui.text.english

/** A pill with fixed text, for tests that pass it around as a value. */
fun verbatimPill(
    label: String,
    needsInput: Boolean,
    accessibilityLabel: String,
): SessionChatBackgroundWorkPill = SessionChatBackgroundWorkPill(UiText.verbatim(label), needsInput, UiText.verbatim(accessibilityLabel))

/** The English text a background work pill shows and reads aloud. */
data class EnglishPill(
    val label: String,
    val needsInput: Boolean,
    val accessibilityLabel: String,
)

fun SessionChatBackgroundWorkPill?.english(): EnglishPill? =
    this?.let { EnglishPill(it.labelText.english(), it.needsInput, it.accessibilityText.english()) }
