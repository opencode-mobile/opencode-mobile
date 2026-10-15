package dev.opencode.mobile.domain.filesystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPathPolicyTest {
    @Test
    fun parentPath_posix_returnsParentUntilRoot() {
        assertEquals("/Users/ivan", ServerPathPolicy.parentPath("/Users/ivan/Projects"))
        assertEquals("/Users", ServerPathPolicy.parentPath("/Users/ivan"))
        assertEquals("/", ServerPathPolicy.parentPath("/Users"))
        assertEquals("/Users", ServerPathPolicy.parentPath("/Users/ivan/"))
        assertNull(ServerPathPolicy.parentPath("/"))
        assertNull(ServerPathPolicy.parentPath(""))
    }

    @Test
    fun parentPath_posix_keepsBackslashesInNames() {
        assertEquals("/srv", ServerPathPolicy.parentPath("/srv/odd\\name"))
    }

    @Test
    fun parentPath_windowsDrive_stopsAtDriveRoot() {
        assertEquals("C:\\Users", ServerPathPolicy.parentPath("C:\\Users\\me"))
        assertEquals("C:\\", ServerPathPolicy.parentPath("C:\\Users"))
        assertEquals("C:\\Users", ServerPathPolicy.parentPath("C:/Users/me"))
        assertNull(ServerPathPolicy.parentPath("C:\\"))
        assertNull(ServerPathPolicy.parentPath("C:"))
        assertNull(ServerPathPolicy.parentPath("d:/"))
    }

    @Test
    fun parentPath_unc_stopsAtShareRoot() {
        assertEquals("\\\\server\\share", ServerPathPolicy.parentPath("\\\\server\\share\\code"))
        assertEquals("\\\\server\\share\\code", ServerPathPolicy.parentPath("//server/share/code/app"))
        assertNull(ServerPathPolicy.parentPath("\\\\server\\share"))
        assertNull(ServerPathPolicy.parentPath("\\\\server"))
    }

    @Test
    fun parentPath_relative_isNull() {
        assertNull(ServerPathPolicy.parentPath("code\\app"))
        assertNull(ServerPathPolicy.parentPath("code/app"))
    }

    @Test
    fun name_takesLastComponentForEitherSeparator() {
        assertEquals("proj", ServerPathPolicy.name("/Users/me/proj"))
        assertEquals("proj", ServerPathPolicy.name("/Users/me/proj/"))
        assertEquals("proj", ServerPathPolicy.name("C:\\Users\\me\\proj"))
        assertEquals("\\\\server\\share", ServerPathPolicy.name("\\\\server\\share"))
        assertEquals("C:\\", ServerPathPolicy.name("C:\\"))
        assertEquals("/", ServerPathPolicy.name("/"))
    }

    @Test
    fun isAbsolute_recognizesDrivesUncAndPosix() {
        assertTrue(ServerPathPolicy.isAbsolute("/Users"))
        assertTrue(ServerPathPolicy.isAbsolute("C:\\Users"))
        assertTrue(ServerPathPolicy.isAbsolute("D:"))
        assertTrue(ServerPathPolicy.isAbsolute("\\\\server\\share"))
        assertTrue(ServerPathPolicy.isAbsolute("//server/share"))
        assertFalse(ServerPathPolicy.isAbsolute("code"))
        assertFalse(ServerPathPolicy.isAbsolute(""))
    }

    @Test
    fun resolve_joinsInTheBaseSeparators() {
        assertEquals("/repo/src", ServerPathPolicy.resolve("/repo", "src/"))
        assertEquals("/etc", ServerPathPolicy.resolve("/", "etc/"))
        assertEquals("/repo", ServerPathPolicy.resolve("/repo", "."))
        assertEquals("/srv/odd\\name", ServerPathPolicy.resolve("/srv", "odd\\name"))
        assertEquals("/srv/C:odd", ServerPathPolicy.resolve("/srv", "C:odd/"))
        assertEquals("C:\\Users\\me\\code", ServerPathPolicy.resolve("C:\\Users\\me", "code\\"))
        assertEquals("C:\\Users\\sibling", ServerPathPolicy.resolve("C:\\Users\\me", "..\\sibling\\"))
        assertEquals("D:\\archive", ServerPathPolicy.resolve("C:\\Users\\me", "D:\\archive\\"))
        assertEquals("C:\\code", ServerPathPolicy.resolve("C:\\", "code\\"))
    }

    @Test
    fun resolveInput_absolutePaths() {
        assertEquals("/Volumes/Disk", ServerPathPolicy.resolveInput("/Volumes/Disk/", "/Users/me", "/Users/me"))
        assertEquals("D:\\", ServerPathPolicy.resolveInput("D:", "C:\\Users\\me", "C:\\Users\\me"))
        assertEquals("D:\\", ServerPathPolicy.resolveInput("d:/", "C:\\Users\\me", "C:\\Users\\me"))
        assertEquals("D:\\code\\app", ServerPathPolicy.resolveInput("D:/code//app/", "C:\\Users\\me", ""))
        assertEquals("\\\\nas\\share", ServerPathPolicy.resolveInput("//nas/share", "C:\\Users\\me", ""))
        assertEquals("\\\\nas\\share\\x", ServerPathPolicy.resolveInput("\\\\nas\\share\\x", "C:\\", ""))
        assertEquals("C:\\Users\\me", ServerPathPolicy.resolveInput("  \"C:\\Users\\me\"  ", "/", ""))
    }

    @Test
    fun resolveInput_homeAndRelative() {
        assertEquals("/Users/me", ServerPathPolicy.resolveInput("~", "/", "/Users/me"))
        assertEquals("/Users/me/code", ServerPathPolicy.resolveInput("~/code", "/", "/Users/me"))
        assertEquals("C:\\Users\\me\\code", ServerPathPolicy.resolveInput("~\\code", "D:\\", "C:\\Users\\me"))
        assertEquals("/srv/code", ServerPathPolicy.resolveInput("~/code", "/srv", ""))
        assertEquals("/Users/me/code", ServerPathPolicy.resolveInput("code", "/Users/me", "/Users/me"))
        assertEquals("/Users", ServerPathPolicy.resolveInput("..", "/Users/me", "/Users/me"))
        assertEquals("C:\\Users\\code", ServerPathPolicy.resolveInput("../code", "C:\\Users\\me", ""))
    }

    @Test
    fun resolveInput_rejectsBlankAndUnanchored() {
        assertNull(ServerPathPolicy.resolveInput("   ", "/Users/me", "/Users/me"))
        assertNull(ServerPathPolicy.resolveInput("\"\"", "/Users/me", "/Users/me"))
        assertNull(ServerPathPolicy.resolveInput("code", "", ""))
        assertNull(ServerPathPolicy.resolveInput("~", "", ""))
    }
}
