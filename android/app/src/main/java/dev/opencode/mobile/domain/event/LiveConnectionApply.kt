package dev.opencode.mobile.domain.event

internal object LiveConnectionApply {
    enum class Change {
        None,
        Directories,
        Identity,
    }

    sealed class StartDecision {
        data object Ignore : StartDecision()

        data object StashPending : StartDecision()

        data object Idempotent : StartDecision()

        data class Reconcile(
            val generation: Long,
            val config: LiveConnectionConfig,
        ) : StartDecision()

        data class Retarget(
            val generation: Long,
            val config: LiveConnectionConfig,
        ) : StartDecision()
    }

    fun change(
        applied: LiveConnectionConfig,
        requested: LiveConnectionConfig,
    ): Change {
        if (applied.identity != requested.identity) return Change.Identity
        if (applied.directories != requested.directories) return Change.Directories
        return Change.None
    }

    fun startDecision(
        occupancyCurrent: Boolean,
        applied: LiveConnectionConfig?,
        requested: LiveConnectionConfig,
        generation: Long,
    ): StartDecision {
        if (!occupancyCurrent) return StartDecision.Ignore
        if (applied == null) return StartDecision.StashPending
        return when (change(applied, requested)) {
            Change.None -> StartDecision.Idempotent
            Change.Directories -> StartDecision.Reconcile(generation, requested)
            Change.Identity -> StartDecision.Retarget(generation, requested)
        }
    }
}
