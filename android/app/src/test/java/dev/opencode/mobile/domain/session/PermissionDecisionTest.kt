package dev.opencode.mobile.domain.session

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionDecisionTest {
    private val adapter = SessionDomainAdapter()

    @Test
    fun `allow once maps to once`() {
        assertEquals("once", adapter.toPermissionResponse(PermissionDecision.AllowOnce))
    }

    @Test
    fun `deny maps to reject`() {
        assertEquals("reject", adapter.toPermissionResponse(PermissionDecision.Deny))
    }

    @Test
    fun `always maps to always`() {
        assertEquals("always", adapter.toPermissionResponse(PermissionDecision.AlwaysAllow))
    }
}
