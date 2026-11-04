package dev.opencode.hexcode.firstrun

/**
 * The paired computer's friendly two-word name, the same on every phone: never an address. See "The server's name" in
 * the Hex Code build plan; iOS uses the same seed, hash and word lists.
 */
object ServerName {
    private val adjectives =
        listOf(
            "Bold",
            "Brave",
            "Calm",
            "Chirpy",
            "Clever",
            "Cosy",
            "Curious",
            "Dapper",
            "Eager",
            "Fuzzy",
            "Gentle",
            "Happy",
            "Humble",
            "Jolly",
            "Keen",
            "Lucky",
            "Mellow",
            "Merry",
            "Nimble",
            "Plucky",
            "Polite",
            "Quick",
            "Quiet",
            "Rapid",
            "Sleepy",
            "Snappy",
            "Spry",
            "Sunny",
            "Swift",
            "Tidy",
            "Witty",
            "Zesty",
        )
    private val animals =
        listOf(
            "Badger",
            "Bison",
            "Falcon",
            "Fox",
            "Gecko",
            "Hedgehog",
            "Heron",
            "Koala",
            "Lemur",
            "Lynx",
            "Marmot",
            "Meerkat",
            "Narwhal",
            "Ocelot",
            "Otter",
            "Owl",
            "Panda",
            "Pelican",
            "Penguin",
            "Puffin",
            "Quokka",
            "Raccoon",
            "Robin",
            "Seal",
            "Sloth",
            "Squirrel",
            "Stoat",
            "Tapir",
            "Toucan",
            "Walrus",
            "Wombat",
            "Yak",
        )

    /**
     * The computer's hostname, lowercased without a trailing `.local`; else the first address in the pairing code, as
     * the code writes it (an origin such as `http://192.168.1.2:4096`, no trailing slash).
     */
    fun seed(
        hostname: String?,
        firstAddress: String,
    ): String {
        val host = hostname?.trim()?.lowercase()?.removeSuffix(".local")
        return if (host.isNullOrEmpty()) firstAddress.trim().trimEnd('/') else host
    }

    /** `Rapid Bison` for `ivans-macbook-pro`: an adjective and an animal picked by a 32-bit FNV-1a hash of [seed]. */
    fun of(seed: String): String {
        val h = fnv1a(seed)
        return adjectives[(h % 32u).toInt()] + " " + animals[((h shr 8) % 32u).toInt()]
    }

    /** Hostname-or-address in, friendly name out. */
    fun of(
        hostname: String?,
        firstAddress: String,
    ): String = of(seed(hostname, firstAddress))

    private fun fnv1a(text: String): UInt {
        var hash = 0x811c9dc5u
        for (byte in text.encodeToByteArray()) {
            hash = hash xor byte.toUByte().toUInt()
            hash *= 0x01000193u
        }
        return hash
    }
}
