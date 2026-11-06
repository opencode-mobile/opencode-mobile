package dev.opencode.hexcode.home

/** A button at one end of home's dock. */
enum class DockButton {
    /** Attach (M3): `clip`. */
    Attach,

    /** The agent's Stop (M4): `stop`, red. */
    Stop,

    /** Drops the locked take, with the "Take dropped" notice: `x`, `text2`. */
    DropTake,

    /** Finishes the locked take into the draft, as tapping the talk button does: `check`, `text1`. */
    Done,
}

/** Which buttons sit either side of the talk button (app-plan.md, Dictation › Locked take). */
data class HomeDock(
    val left: DockButton,
    val right: DockButton,
) {
    companion object {
        /** While a take is locked, attach becomes ✕ and the agent's stop becomes ✓; otherwise they're attach and stop. */
        fun of(talk: TalkMode): HomeDock =
            when (talk) {
                TalkMode.Locked -> HomeDock(DockButton.DropTake, DockButton.Done)
                TalkMode.Idle, TalkMode.Holding -> HomeDock(DockButton.Attach, DockButton.Stop)
            }
    }
}
