package dev.opencode.mobile.ui.sessionchat

/**
 * Derived apply kinds for Session Chat hosts.
 * Canonical change surface remains [TimelineFrame] ID sets; this packet is classify-only.
 */
enum class TimelineApplyKind {
    Structure,
    Content,
    GrowBottom,
    Decorate,
    Required,
}

/**
 * Host-facing classify result. Scroll policy helpers match Phase 1:
 * layoutAffecting excludes GrowBottom; GrowBottom is armed-only.
 */
data class TimelineApply(
    val kinds: Set<TimelineApplyKind>,
    val frame: TimelineFrame,
) {
    val layoutAffectingRowIDs: Set<String>
        get() = frame.layoutAffectingRowIDs()

    val layoutAffectingRevision: Long
        get() = frame.layoutAffectingRevision()

    val structureAffectingRevision: Long
        get() = frame.structureAffectingRevision()

    val growBottomRevision: Long
        get() = frame.growBottomRevision()

    val structureRowIDs: Set<String>
        get() =
            frame.insertedRowIDs +
                frame.removedRowIDs +
                (frame.layoutChangedRowIDs - frame.decorateRowIDs)

    val contentRowIDs: Set<String>
        get() =
            frame.changedRowIDs - frame.layoutChangedRowIDs - frame.grewBottomRowIDs

    companion object {
        fun classify(frame: TimelineFrame): TimelineApply {
            val kinds = linkedSetOf<TimelineApplyKind>()
            if (frame.isRequired) {
                kinds += TimelineApplyKind.Required
            }
            val structureIds =
                frame.insertedRowIDs +
                    frame.removedRowIDs +
                    (frame.layoutChangedRowIDs - frame.decorateRowIDs)
            if (structureIds.isNotEmpty()) {
                kinds += TimelineApplyKind.Structure
            }
            if (frame.decorateRowIDs.isNotEmpty()) {
                kinds += TimelineApplyKind.Decorate
            }
            if (frame.grewBottomRowIDs.isNotEmpty()) {
                kinds += TimelineApplyKind.GrowBottom
            }
            val contentIds =
                frame.changedRowIDs - frame.layoutChangedRowIDs - frame.grewBottomRowIDs
            if (contentIds.isNotEmpty()) {
                kinds += TimelineApplyKind.Content
            }
            return TimelineApply(kinds = kinds, frame = frame)
        }

        fun kindsLabel(kinds: Set<TimelineApplyKind>): String = kinds.joinToString(separator = ",") { it.name }
    }
}
