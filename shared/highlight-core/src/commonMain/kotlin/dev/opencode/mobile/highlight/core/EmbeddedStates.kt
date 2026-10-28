package dev.opencode.mobile.highlight.core

/**
 * Line-end states for lexers that embed another language: HTML handing `<script>` to the
 * JavaScript lexer, a Markdown fence handing its lines to the fence's language, PHP islands in
 * HTML. A line ending inside an embedded stretch has to carry the host's own state and the
 * embedded lexer's state, and the embedded state can itself be composite (a Markdown fence of
 * HTML ending inside `<script>`), so the pair cannot be packed into one [Int] by bit layout.
 *
 * Instead each session interns `(host, inner)` pairs: [join] returns the pair's state, and
 * [host] and [inner] read it back. A non-negative state is a host state whose embedded state is
 * 0 and needs no table entry, so lines outside an embedded stretch, or ending at the embedded
 * language's top level, never touch the table. A negative state is the inverted index of an
 * interned pair. Interning is canonical (one index per pair), so equal states still mean equal
 * lexing after, and the session keeps comparing plain [Int]s.
 *
 * Trade-off against widening states to [Long]: nesting depth is unlimited and [HighlightLexer]
 * stays an `Int` interface, at the cost of a per-session table that only grows (entries are
 * never freed until [HighlightSession.open] replaces the text). Real code produces few distinct
 * pairs, since most embedded lines end at state 0; the table is capped at [MAX_PAIRS], past
 * which [join] drops the embedded state, so that language restarts from its top level on the
 * next line. Pair indexes mean nothing outside their session.
 *
 * Hosts keep their own states non-negative. Single-threaded, like the session that owns it.
 */
internal class EmbeddedStates {
    private var hosts = IntArray(INITIAL_PAIRS)
    private var inners = IntArray(INITIAL_PAIRS)

    // Open addressing from a pair's hash to its index, -1 when empty; at most half full.
    private var slots = IntArray(INITIAL_PAIRS * 2).also { it.fill(-1) }

    /** Interned pairs. */
    var size: Int = 0
        private set

    /** The state for [host] (non-negative) with the embedded lexer at [inner]. */
    fun join(
        host: Int,
        inner: Int,
    ): Int {
        if (inner == 0) return host
        val mask = slots.size - 1
        var slot = hash(host, inner) and mask
        while (true) {
            val index = slots[slot]
            if (index < 0) break
            if (hosts[index] == host && inners[index] == inner) return index.inv()
            slot = (slot + 1) and mask
        }
        if (size >= MAX_PAIRS) return host
        if (size == hosts.size) {
            hosts = hosts.copyOf(size * 2)
            inners = inners.copyOf(size * 2)
        }
        hosts[size] = host
        inners[size] = inner
        slots[slot] = size
        size += 1
        if (size * 2 > slots.size) rehash()
        return (size - 1).inv()
    }

    /** The host's own state in [state]. */
    fun host(state: Int): Int = if (state >= 0) state else hosts[state.inv()]

    /** The embedded lexer's state in [state]; 0 when the state is a plain host state. */
    fun inner(state: Int): Int = if (state >= 0) 0 else inners[state.inv()]

    fun clear() {
        size = 0
        slots.fill(-1)
    }

    private fun rehash() {
        slots = IntArray(slots.size * 2).also { it.fill(-1) }
        val mask = slots.size - 1
        for (index in 0 until size) {
            var slot = hash(hosts[index], inners[index]) and mask
            while (slots[slot] >= 0) slot = (slot + 1) and mask
            slots[slot] = index
        }
    }

    private fun hash(
        host: Int,
        inner: Int,
    ): Int {
        val mixed = (host * -0x61c88647) xor inner
        return mixed xor (mixed ushr 16)
    }

    companion object {
        private const val INITIAL_PAIRS = 16

        /** About a megabyte of table; real documents stay far below it. */
        const val MAX_PAIRS = 1 shl 16
    }
}
