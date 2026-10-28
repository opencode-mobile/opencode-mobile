package dev.opencode.mobile.domain.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeCompactRowPathTest {
    @Test
    fun shortBranch_dropsKnownPrefixes() {
        assertEquals("794-readable-tool-results", HomeCompactRowPath.shortBranch("feature/794-readable-tool-results"))
        assertEquals("skill-image", HomeCompactRowPath.shortBranch("fix/skill-image"))
        assertEquals("bump-deps", HomeCompactRowPath.shortBranch("chore/bump-deps"))
        assertEquals("brave-otter", HomeCompactRowPath.shortBranch("opencode/brave-otter"))
    }

    @Test
    fun shortBranch_keepsOtherNames() {
        assertEquals("main", HomeCompactRowPath.shortBranch("main"))
        assertEquals("release/1.2", HomeCompactRowPath.shortBranch("release/1.2"))
        assertEquals("features/x", HomeCompactRowPath.shortBranch("features/x"))
        // Only the leading prefix goes.
        assertEquals("ope-1/fix/x", HomeCompactRowPath.shortBranch("feature/ope-1/fix/x"))
        assertEquals("feature/", HomeCompactRowPath.shortBranch("feature/"))
    }

    @Test
    fun worktreeLabel_mainCheckoutHasNone() {
        assertNull(HomeCompactRowPath.worktreeLabel(repository(), "/repo"))
    }

    @Test
    fun worktreeLabel_worktreeShowsShortBranch() {
        assertEquals("723-iphone-duo", HomeCompactRowPath.worktreeLabel(repository(), "/repo-wt"))
    }

    @Test
    fun worktreeLabel_detachedOrUnknownBranchShowsFolder() {
        val project = repository(branches = mapOf("/repo" to "main"))

        assertEquals("repo-wt", HomeCompactRowPath.worktreeLabel(project, "/repo-wt"))
        assertEquals("elsewhere", HomeCompactRowPath.worktreeLabel(project, "/tmp/elsewhere"))
    }

    @Test
    fun worktreeLabel_nonGitProjectHasNone() {
        val project =
            HomeProjectGrouping
                .group(
                    addedPaths = listOf("/notes"),
                    resolved = mapOf("/notes" to ResolvedHomePath(HomeProjectGrouping.GLOBAL_PROJECT_ID, "/")),
                ).single()

        assertNull(HomeCompactRowPath.worktreeLabel(project, "/notes"))
        assertNull(HomeCompactRowPath.worktreeLabel(project, "/notes/sub"))
    }

    @Test
    fun worktreeLabel_worktreesOffHasNone() {
        assertNull(HomeCompactRowPath.worktreeLabel(repository(), "/repo-wt", worktreesEnabled = false))
    }

    private fun repository(branches: Map<String, String> = mapOf("/repo" to "main", "/repo-wt" to "feature/723-iphone-duo")): HomeProject =
        HomeProjectGrouping
            .group(
                addedPaths = listOf("/repo", "/repo-wt"),
                resolved =
                    mapOf(
                        "/repo" to ResolvedHomePath("proj", "/repo", isRepository = true),
                        "/repo-wt" to ResolvedHomePath("proj", "/repo", isRepository = true),
                    ),
                branches = branches,
            ).single()
}
