package dev.opencode.mobile.domain.session

import dev.opencode.mobile.domain.composer.ComposerDraft
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.worktree.CreatedWorktree
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Where a new session runs in its project. */
sealed interface NewSessionPlace {
    /** The project's main checkout. */
    data object Local : NewSessionPlace

    /** An existing worktree of the project. */
    data class Worktree(
        val directory: String,
        val branch: String?,
    ) : NewSessionPlace

    /** A worktree the first send creates; the server names it. [baseBranch] null starts from the main checkout's HEAD (v2 only). */
    data class NewWorktree(
        val baseBranch: String? = null,
    ) : NewSessionPlace

    companion object {
        /** [directory] of [project] as a place: its main checkout is Local, another checkout a worktree. */
        fun of(
            project: HomeProject,
            directory: String,
        ): NewSessionPlace {
            val worktree = project.worktree(directory) ?: return Local
            if (worktree.isMain || worktree.directory == ServerPathPolicy.canonical(project.mainCheckoutDirectory)) return Local
            return Worktree(worktree.directory, worktree.branch)
        }
    }
}

/** Where a fresh draft starts: a project and a place in it. */
data class NewSessionDraftStart(
    /** Home project key. */
    val projectKey: String,
    /** The project's main checkout (Local's directory). */
    val projectDirectory: String,
    val place: NewSessionPlace = NewSessionPlace.Local,
) {
    companion object {
        /** [directory] in [project], or Local when [directory] is not one of its checkouts. */
        fun of(
            project: HomeProject,
            directory: String? = null,
        ): NewSessionDraftStart =
            NewSessionDraftStart(
                projectKey = project.key,
                projectDirectory = project.mainCheckoutDirectory,
                place = directory?.let { NewSessionPlace.of(project, it) } ?: NewSessionPlace.Local,
            )

        /** [directory]'s project among [projects], or a folder of its own when Home does not know it. */
        fun forDirectory(
            projects: List<HomeProject>,
            directory: String,
        ): NewSessionDraftStart {
            val canonical = ServerPathPolicy.canonical(directory.trim())
            val project =
                projects.firstOrNull { it.worktree(canonical) != null }
                    ?: projects.firstOrNull { it.contains(canonical) }
            return project?.let { of(it, canonical) } ?: NewSessionDraftStart(projectKey = canonical, projectDirectory = canonical)
        }
    }
}

/** A session the first send created, kept so a retry does not create another. */
data class NewSessionCreatedSession(
    val id: String,
    val title: String,
    val directory: String,
)

/**
 * New session before its first send: nothing exists on the server yet. Kept in memory only, one
 * per Connection Profile ([NewSessionDraftStore]).
 */
data class NewSessionDraft(
    val projectKey: String,
    /** The project's main checkout. */
    val projectDirectory: String,
    val place: NewSessionPlace = NewSessionPlace.Local,
    val composer: ComposerDraft = ComposerDraft(text = ""),
    val attachments: List<Attachment> = emptyList(),
    /** Model, variant and agent picked in the draft; null until the composer resolved one. */
    val modelSelection: SessionModelSelection? = null,
    /** The worktree a failed first send already created; a retry reuses it. */
    val createdWorktree: CreatedWorktree? = null,
    /** The session a failed first send already created; a retry reuses it. */
    val createdSession: NewSessionCreatedSession? = null,
) {
    /** Where the composer reads models, commands and files: the place's checkout, else the main checkout. */
    val directory: String
        get() =
            createdWorktree?.directory
                ?: when (val place = place) {
                    is NewSessionPlace.Worktree -> place.directory
                    NewSessionPlace.Local, is NewSessionPlace.NewWorktree -> projectDirectory
                }

    /** Text or attachments; a place choice alone is not worth keeping. */
    val hasContent: Boolean
        get() = composer.text.isNotBlank() || attachments.isNotEmpty()

    /** Another project or place: anything the first send made for the old one no longer applies. */
    fun movedTo(
        projectKey: String,
        projectDirectory: String,
        place: NewSessionPlace,
    ): NewSessionDraft =
        copy(
            projectKey = projectKey,
            projectDirectory = projectDirectory,
            place = place,
            createdWorktree = null,
            createdSession = null,
        )

    companion object {
        fun from(start: NewSessionDraftStart): NewSessionDraft =
            NewSessionDraft(
                projectKey = start.projectKey,
                projectDirectory = start.projectDirectory,
                place = start.place,
            )
    }
}

/**
 * The New session draft of each Connection Profile, in memory only: it survives Back and
 * backgrounding and is gone after an app restart. Profiles never share a draft.
 */
@Singleton
class NewSessionDraftStore
    @Inject
    constructor() {
        private val _drafts = MutableStateFlow<Map<String, NewSessionDraft>>(emptyMap())
        val drafts: StateFlow<Map<String, NewSessionDraft>> = _drafts.asStateFlow()

        /**
         * The draft screen that edits each profile's draft. Only the owner writes, so a screen that
         * is going away cannot overwrite a draft a newer screen (or Home) already changed.
         */
        private val owners = mutableMapOf<String, Any>()

        /** Guarded by [owners]. */
        private val holds = mutableSetOf<FirstSendHold>()

        /**
         * A restored prompt in a profile's slot that no screen has opened yet. A screen that was
         * already open still has its older copy, and must not erase this one. Guarded by [owners].
         */
        private val restored = mutableMapOf<String, NewSessionDraft>()

        /** Restored prompts waiting behind a draft with content, oldest first. Guarded by [owners]. */
        private val parked = mutableMapOf<String, MutableList<NewSessionDraft>>()

        fun draft(profileId: String): NewSessionDraft? = _drafts.value[profileId]

        /** New session: the profile's draft when there is one (it wins), else a fresh one at [start]. */
        fun open(
            profileId: String,
            start: NewSessionDraftStart,
        ): NewSessionDraft {
            synchronized(owners) {
                owners.remove(profileId)
                returnParkedLocked(profileId)
                restored.remove(profileId)
            }
            _drafts.value[profileId]?.let { return it }
            val fresh = NewSessionDraft.from(start)
            _drafts.update { it + (profileId to fresh) }
            return fresh
        }

        /** New session in a chosen place (the Worktrees screen): keeps the draft's text, takes [start]'s place. */
        fun openAt(
            profileId: String,
            start: NewSessionDraftStart,
        ): NewSessionDraft {
            synchronized(owners) {
                owners.remove(profileId)
                returnParkedLocked(profileId)
                restored.remove(profileId)
            }
            val existing = _drafts.value[profileId]
            val next =
                existing?.movedTo(start.projectKey, start.projectDirectory, start.place)
                    ?: NewSessionDraft.from(start)
            _drafts.update { it + (profileId to next) }
            return next
        }

        /** [owner] (a draft screen) now edits [profileId]'s draft: the profile's draft, else a fresh one at [start]. */
        fun claim(
            profileId: String,
            owner: Any,
            start: NewSessionDraftStart,
        ): NewSessionDraft {
            return synchronized(owners) {
                returnParkedLocked(profileId)
                restored.remove(profileId)
                val draft =
                    _drafts.value[profileId]
                        ?: NewSessionDraft.from(start).also { fresh -> _drafts.update { it + (profileId to fresh) } }
                owners[profileId] = owner
                draft
            }
        }

        /** Puts the oldest parked prompt in the slot once nothing with content is there. */
        private fun returnParkedLocked(profileId: String) {
            val waiting = parked[profileId] ?: return
            if (_drafts.value[profileId]?.hasContent == true) return
            val next = waiting.removeAt(0)
            if (waiting.isEmpty()) parked.remove(profileId)
            restored[profileId] = next
            _drafts.update { it + (profileId to next) }
        }

        private fun holdsRestoredLocked(profileId: String): Boolean {
            val current = _drafts.value[profileId]
            return current != null && current === restored[profileId]
        }

        /** [owner]'s current draft for [profileId]; ignored once another screen or Home took the draft over. */
        fun save(
            profileId: String,
            owner: Any,
            draft: NewSessionDraft,
        ) {
            synchronized(owners) {
                if (owners[profileId] !== owner) return
                if (holdsRestoredLocked(profileId)) {
                    // This screen's copy predates the restored prompt. Only real typing replaces it.
                    if (!draft.hasContent) return
                    parked.getOrPut(profileId) { mutableListOf() } += checkNotNull(restored.remove(profileId))
                }
                _drafts.update { it + (profileId to draft) }
            }
        }

        /** Changes [profileId]'s draft in place (Home, tests); a screen that owns it writes with [save]. */
        fun update(
            profileId: String,
            transform: (NewSessionDraft) -> NewSessionDraft,
        ) {
            _drafts.update { drafts ->
                val current = drafts[profileId] ?: return@update drafts
                drafts + (profileId to transform(current))
            }
        }

        /** Discard draft, or the first send created the session: [owner]'s draft is done. */
        fun release(
            profileId: String,
            owner: Any,
        ) {
            synchronized(owners) {
                if (owners[profileId] !== owner) return
                if (holdsRestoredLocked(profileId)) {
                    owners.remove(profileId)
                    return
                }
                discard(profileId)
            }
        }

        fun discard(profileId: String) {
            synchronized(owners) {
                _drafts.update { it - profileId }
                restored.remove(profileId)
                owners.remove(profileId)
                returnParkedLocked(profileId)
            }
        }

        /**
         * The first send created [owner]'s session: its draft leaves the store, so New session
         * cannot reopen a prompt that is on its way. Null when [owner] no longer holds the draft.
         */
        fun holdForFirstSend(
            profileId: String,
            owner: Any,
        ): FirstSendHold? =
            synchronized(owners) {
                if (owners[profileId] !== owner || holdsRestoredLocked(profileId)) return null
                owners.remove(profileId)
                val draft = _drafts.value[profileId] ?: return null
                _drafts.update { it - profileId }
                returnParkedLocked(profileId)
                FirstSendHold(profileId, draft).also(holds::add)
            }

        /** A removed profile: its draft, and one held by a running first send. */
        fun forgetProfile(profileId: String) {
            synchronized(owners) {
                holds.removeAll { it.profileId == profileId }
                restored.remove(profileId)
                parked.remove(profileId)
                owners.remove(profileId)
                _drafts.update { it - profileId }
            }
        }

        /** Clear All: no profile keeps a draft or its attachments. */
        fun clear() {
            synchronized(owners) {
                holds.clear()
                restored.clear()
                parked.clear()
                owners.clear()
                _drafts.value = emptyMap()
            }
        }

        /** A draft out of the store while its first prompt is sent. Settles once. */
        inner class FirstSendHold internal constructor(
            internal val profileId: String,
            private val draft: NewSessionDraft,
        ) {
            /** The prompt is settled on its server: the draft is done. */
            fun finish() {
                synchronized(owners) { holds.remove(this) }
            }

            /**
             * The prompt did not go out. It replaces an empty draft; behind a draft with content it
             * waits, attachments included, and returns when that draft ends.
             */
            fun restore() {
                synchronized(owners) {
                    if (!holds.remove(this)) return
                    parked.getOrPut(profileId) { mutableListOf() } += draft
                    returnParkedLocked(profileId)
                }
            }
        }

        /** [owner] leaves its draft: one with no text and no attachments is not kept. */
        fun discardIfEmpty(
            profileId: String,
            owner: Any,
        ): Boolean {
            synchronized(owners) {
                if (owners[profileId] !== owner) return false
                if (holdsRestoredLocked(profileId)) {
                    owners.remove(profileId)
                    return false
                }
                val draft = _drafts.value[profileId] ?: return false
                if (draft.hasContent) return false
                _drafts.update { it - profileId }
                owners.remove(profileId)
                returnParkedLocked(profileId)
                return true
            }
        }
    }
