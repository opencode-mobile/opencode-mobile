package dev.opencode.mobile.ui.components

enum class ServerAddressScheme(
    val label: String,
    val urlPrefix: String,
) {
    Http("http", "http://"),
    Https("https", "https://"),
    ;

    companion object {
        fun fromLabel(label: String): ServerAddressScheme = entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: Http
    }
}

data class ServerAddressParts(
    val scheme: ServerAddressScheme,
    val hostPort: String,
) {
    fun toBaseUrl(): String {
        val host = hostPort.trim().trimEnd('/')
        if (host.isEmpty()) {
            return scheme.urlPrefix
        }
        return "${scheme.urlPrefix}$host/"
    }
}

/** Hostnames, IPv4/IPv6 literals, and ports. */
private val hostPortAllowedChars =
    buildSet {
        addAll('0'..'9')
        addAll('a'..'z')
        addAll('A'..'Z')
        add('.')
        add('-')
        add('_')
        add(':')
        add('[')
        add(']')
    }

fun filterServerAddressHostPort(raw: String): String = raw.filter { it in hostPortAllowedChars }

fun parseServerAddressValue(
    value: String,
    fallbackScheme: ServerAddressScheme = ServerAddressScheme.Http,
): ServerAddressParts {
    val trimmed = value.trim()
    val scheme =
        when {
            trimmed.startsWith("https://", ignoreCase = true) -> ServerAddressScheme.Https
            trimmed.startsWith("http://", ignoreCase = true) -> ServerAddressScheme.Http
            else -> fallbackScheme
        }
    val withoutScheme =
        when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
            else -> trimmed
        }
    val hostCandidate =
        withoutScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .trimEnd('/')
    return ServerAddressParts(
        scheme = scheme,
        hostPort = filterServerAddressHostPort(hostCandidate),
    )
}

fun applyServerAddressHostEdit(
    rawHostEdit: String,
    currentScheme: ServerAddressScheme,
): ServerAddressParts {
    val trimmed = rawHostEdit.trim()
    if (trimmed.contains("://")) {
        return parseServerAddressValue(trimmed, fallbackScheme = currentScheme)
    }
    return ServerAddressParts(
        scheme = currentScheme,
        hostPort = filterServerAddressHostPort(rawHostEdit),
    )
}
