package dev.opencode.mobile.domain.session

import dev.opencode.mobile.domain.composer.ComposerDraft
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeWorktree
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentKind
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
    fun heldFirstSend_isNotOfferedAgainAndFinishDropsIt() {
        val store = NewSessionDraftStore()
        val owner = Any()
        store.claim("a", owner, repo)
        store.save("a", owner, store.draft("a")!!.copy(composer = ComposerDraft("sent")))

        val hold = store.holdForFirstSend("a", owner)

        assertNull(store.draft("a"))
        assertEquals("", store.open("a", repo).composer.text)
        store.discard("a")
        hold?.finish()
        hold?.restore()
        assertNull(store.draft("a"))
    }

    @Test
    fun heldFirstSend_restoresOnlyWhenTheProfileHasNoNewerDraft() {
        val store = NewSessionDraftStore()
        val owner = Any()
        store.claim("a", owner, repo)
        store.save("a", owner, store.draft("a")!!.copy(composer = ComposerDraft("sent")))
        val hold = store.holdForFirstSend("a", owner)
        store.open("a", repo)
        store.update("a") { it.copy(composer = ComposerDraft("newer")) }

        hold?.restore()
        assertEquals("newer", store.draft("a")?.composer?.text)

        val other = Any()
        store.discard("a")
        store.claim("a", other, repo)
        store.save("a", other, store.draft("a")!!.copy(composer = ComposerDraft("sent again")))
        val second = store.holdForFirstSend("a", other)
        second?.restore()
        assertEquals("sent again", store.draft("a")?.composer?.text)
    }

    @Test
    fun heldFirstSend_replacesAnEmptyDraft_andItsOpenScreenCannotEraseIt() {
        val store = NewSessionDraftStore()
        val sender = Any()
        val emptyScreen = Any()
        store.claim("a", sender, repo)
        store.save("a", sender, store.draft("a")!!.copy(composer = ComposerDraft("sent")))
        val hold = store.holdForFirstSend("a", sender)
        val empty = store.claim("a", emptyScreen, repo)

        hold?.restore()
        assertEquals("sent", store.draft("a")?.composer?.text)

        // The open screen still holds its empty copy: a sync, Discard or leaving must not erase the prompt.
        store.save("a", emptyScreen, empty.copy(modelSelection = null))
        assertEquals("sent", store.draft("a")?.composer?.text)
        store.release("a", emptyScreen)
        assertEquals("sent", store.draft("a")?.composer?.text)
        assertFalse(store.discardIfEmpty("a", emptyScreen))
        assertEquals("sent", store.draft("a")?.composer?.text)
    }

    @Test
    fun heldFirstSend_typingInTheOpenScreenKeepsBothPrompts() {
        val store = NewSessionDraftStore()
        val sender = Any()
        val emptyScreen = Any()
        store.claim("a", sender, repo)
        store.save("a", sender, store.draft("a")!!.copy(composer = ComposerDraft("sent")))
        val hold = store.holdForFirstSend("a", sender)
        val empty = store.claim("a", emptyScreen, repo)
        hold?.restore()

        store.save("a", emptyScreen, empty.copy(composer = ComposerDraft("typed")))
        assertEquals("typed", store.draft("a")?.composer?.text)

        store.release("a", emptyScreen)
        assertEquals("sent", store.draft("a")?.composer?.text)
    }

    @Test
    fun heldFirstSend_behindADraftWithContent_returnsWhenThatDraftEnds() {
        val store = NewSessionDraftStore()
        val owner = Any()
        store.claim("a", owner, repo)
        val attachment =
            Attachment(
                id = "att-1",
                filename = "photo.png",
                mime = "image/png",
                dataUrl = "data:image/png;base64,AA==",
                kind = AttachmentKind.Image,
                byteCount = 1,
            )
        store.save("a", owner, store.draft("a")!!.copy(composer = ComposerDraft("sent"), attachments = listOf(attachment)))
        val hold = store.holdForFirstSend("a", owner)
        store.open("a", repo)
        store.update("a") { it.copy(composer = ComposerDraft("newer")) }

        hold?.restore()
        assertEquals("newer", store.draft("a")?.composer?.text)

        store.discard("a")
        assertEquals("sent", store.draft("a")?.composer?.text)
        assertEquals(listOf(attachment), store.draft("a")?.attachments)
    }

    @Test
    fun heldFirstSend_parkedBehindANewerDraft_goesWithItsProfile() {
        val store = NewSessionDraftStore()
        val owner = Any()
        store.claim("a", owner, repo)
        store.save("a", owner, store.draft("a")!!.copy(composer = ComposerDraft("sent")))
        val hold = store.holdForFirstSend("a", owner)
        store.open("a", repo)
        store.update("a") { it.copy(composer = ComposerDraft("newer")) }
        hold?.restore()

        store.forgetProfile("a")

        assertNull(store.draft("a"))
        assertEquals("", store.open("a", repo).composer.text)
    }

    @Test
    fun heldFirstSend_doesNotComeBackAfterItsProfileIsRemovedOrCleared() {
        val store = NewSessionDraftStore()
        val owner = Any()
        store.claim("a", owner, repo)
        store.save("a", owner, store.draft("a")!!.copy(composer = ComposerDraft("sent")))
        val removed = store.holdForFirstSend("a", owner)
        store.forgetProfile("a")
        removed?.restore()
        assertNull(store.draft("a"))

        store.claim("b", owner, play)
        store.save("b", owner, store.draft("b")!!.copy(composer = ComposerDraft("sent")))
        val cleared = store.holdForFirstSend("b", owner)
        store.clear()
        cleared?.restore()
        assertNull(store.draft("b"))
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
