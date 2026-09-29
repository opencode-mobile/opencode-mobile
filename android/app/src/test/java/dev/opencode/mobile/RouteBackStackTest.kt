package dev.opencode.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteBackStackTest {
    @Test
    fun popRouteUnlessRoot_leavesTheOnlyEntry() {
        val stack = mutableListOf("home")

        stack.popRouteUnlessRoot()

        assertEquals(listOf("home"), stack)
    }

    @Test
    fun popRouteUnlessRoot_removesTheTopWhenARouteIsAboveRoot() {
        val stack = mutableListOf("home", "settings", "servers")

        stack.popRouteUnlessRoot()

        assertEquals(listOf("home", "settings"), stack)
    }
}
