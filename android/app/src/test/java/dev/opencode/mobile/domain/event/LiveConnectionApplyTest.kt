package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.connection.ServerProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveConnectionApplyTest {
    @Test
    fun startDecision_occupancyGone_isIgnore() {
        assertEquals(
            LiveConnectionApply.StartDecision.Ignore,
            LiveConnectionApply.startDecision(
                occupancyCurrent = false,
                applied = sample(),
                requested = sample(directories = listOf("/b")),
                generation = 3,
            ),
        )
    }

    @Test
    fun startDecision_noAppliedConfig_isStashPending() {
        assertEquals(
            LiveConnectionApply.StartDecision.StashPending,
            LiveConnectionApply.startDecision(
                occupancyCurrent = true,
                applied = null,
                requested = sample(),
                generation = 3,
            ),
        )
    }

    @Test
    fun startDecision_sameConfig_isIdempotent() {
        assertEquals(
            LiveConnectionApply.StartDecision.Idempotent,
            LiveConnectionApply.startDecision(
                occupancyCurrent = true,
                applied = sample(),
                requested = sample(),
                generation = 3,
            ),
        )
    }

    @Test
    fun startDecision_directoriesChanged_isReconcile() {
        val requested = sample(directories = listOf("/a", "/b"))
        assertEquals(
            LiveConnectionApply.StartDecision.Reconcile(3, requested),
            LiveConnectionApply.startDecision(
                occupancyCurrent = true,
                applied = sample(),
                requested = requested,
                generation = 3,
            ),
        )
    }

    @Test
    fun startDecision_protocolChanged_isRetarget() {
        val requested = sample(protocol = ServerProtocol.V2)
        assertEquals(
            LiveConnectionApply.StartDecision.Retarget(3, requested),
            LiveConnectionApply.startDecision(
                occupancyCurrent = true,
                applied = sample(),
                requested = requested,
                generation = 3,
            ),
        )
    }

    @Test
    fun change_profileIdentity_isIdentity() {
        assertEquals(
            LiveConnectionApply.Change.Identity,
            LiveConnectionApply.change(
                sample(),
                sample(connectionIdentity = "http://other|opencode"),
            ),
        )
    }

    private fun sample(
        protocol: ServerProtocol = ServerProtocol.V1,
        directories: List<String> = listOf("/a"),
        connectionIdentity: String = "http://host|opencode",
    ) = LiveConnectionConfig(
        profileId = "p1",
        connectionIdentity = connectionIdentity,
        protocol = protocol,
        directories = directories,
    )
}
