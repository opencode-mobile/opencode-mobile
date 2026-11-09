package dev.opencode.mobile.domain.session

import dev.opencode.mobile.domain.composer.ComposerDraft
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeWorktree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewSessionDraftStoreTest {
    private val repo = NewSessionDraftStart("proj", "/repo")
    private val play = NewSessionDraftStart("/play", "/play")

    @Test
    fun open_keepsOneDraftPerProfileAndTheExistingDraftWins() {
        val store = NewSessionDraftStore()
        store.open("a", repo)
        store.update("a") { it.copy(composer = ComposerDraft("for a")) }

        assertEquals("/repo", store.open("a", play).projectDirectory)
        assertEquals("for a", store.draft("a")?.composer?.text)
        // Another profile never sees it.
        assertEquals("/play", store.open("b", play).projectDirectory)
        assertEquals("", store.draft("b")?.composer?.text)
    }

    @Test
    fun openAt_keepsTheTextAndTakesThePlace() {
        val store = NewSessionDraftStore()
        store.open("a", repo)
        store.update("a") { it.copy(composer = ComposerDraft("keep")) }

        val moved = store.openAt("a", NewSessionDraftStart("proj", "/repo", NewSessionPlace.Worktree("/wt/x", "x")))

        assertEquals("keep", moved.composer.text)
        assertEquals(NewSessionPlace.Worktree("/wt/x", "x"), moved.place)
        assertEquals("/wt/x", moved.directory)
    }

    @Test
    fun discardIfEmpty_dropsOnlyADraftWithoutTextOrAttachments() {
        val store = NewSessionDraftStore()
        val owner = Any()
        store.claim("a", owner, repo)
        store.save("a", owner, store.draft("a")!!.copy(place = NewSessionPlace.NewWorktree()))
        // A place choice alone is not kept.
        assertTrue(store.discardIfEmpty("a", owner))
        assertNull(store.draft("a"))

        store.claim("a", owner, repo)
        store.save("a", owner, store.draft("a")!!.copy(composer = ComposerDraft("text")))
        assertFalse(store.discardIfEmpty("a", owner))
        assertEquals("text", store.draft("a")?.composer?.text)
    }

    @Test
    fun onlyTheScreenThatClaimedTheDraftWritesOrDropsIt() {
        val store = NewSessionDraftStore()
        val old = Any()
        val new = Any()
        store.claim("a", old, repo)
        store.claim("a", new, repo)

        store.save("a", old, store.draft("a")!!.copy(composer = ComposerDraft("stale")))
        assertEquals("", store.draft("a")?.composer?.text)
        assertFalse(store.discardIfEmpty("a", old))
        store.release("a", old)
        assertEquals("/repo", store.draft("a")?.projectDirectory)

        store.release("a", new)
        assertNull(store.draft("a"))
    }

    @Test
    fun homeOpeningTheDraftTakesItOverFromTheScreen() {
        val store = NewSessionDraftStore()
        val screen = Any()
        store.claim("a", screen, repo)
        store.openAt("a", play)

        store.save("a", screen, NewSessionDraft.from(repo))

        assertEquals("/play", store.draft("a")?.projectDirectory)
    }

    @Test
    fun addProjectRequest_isConsumedOnceAndDroppedOnCancel() {
        val store = NewSessionDraftStore()
        store.requestAddProject("a")
        assertTrue(store.consumeAddProjectRequest("a"))
        assertFalse(store.consumeAddProjectRequest("a"))

        store.requestAddProject("a")
        store.cancelAddProject("a")
        assertFalse(store.consumeAddProjectRequest("a"))
    }

    @Test
    fun movedTo_dropsWhatTheFirstSendMadeForTheOldPlace() {
        val draft =
            NewSessionDraft
                .from(repo)
                .copy(
                    composer = ComposerDraft("keep"),
                    createdWorktree =
                        dev.opencode.mobile.domain.worktree
                            .CreatedWorktree("/wt/n", "n", null),
                    createdSession = NewSessionCreatedSession("s", "t", "/wt/n"),
                )
        assertEquals("/wt/n", draft.directory)

        val moved = draft.movedTo("proj", "/repo", NewSessionPlace.Local)

        assertEquals("keep", moved.composer.text)
        assertNull(moved.createdWorktree)
        assertNull(moved.createdSession)
        assertEquals("/repo", moved.directory)
    }

    @Test
    fun startForDirectory_mapsACheckoutToItsProjectAndPlace() {
        val project =
            HomeProject(
                key = "proj",
                projectId = "proj",
                displayName = "repo",
                memberPaths = listOf("/repo"),
                isRepository = true,
                worktrees = listOf(HomeWorktree("/repo", "main", isMain = true), HomeWorktree("/wt/x", "x")),
            )
        assertEquals(NewSessionDraftStart("proj", "/repo"), NewSessionDraftStart.forDirectory(listOf(project), "/repo"))
        assertEquals(
            NewSessionDraftStart("proj", "/repo", NewSessionPlace.Worktree("/wt/x", "x")),
            NewSessionDraftStart.forDirectory(listOf(project), "/wt/x"),
        )
        // Unknown to Home: a project of its own.
        assertEquals(NewSessionDraftStart("/elsewhere", "/elsewhere"), NewSessionDraftStart.forDirectory(listOf(project), "/elsewhere"))
    }
}
