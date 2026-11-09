package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import java.lang.reflect.Proxy

/** A session repository that only answers `getSession`, from [sessions]; ids in [failing] fail. */
internal class SessionLookupFake(
    var sessions: Map<String, Session>,
    var failing: Set<String> = emptySet(),
) {
    val fetched = mutableListOf<String>()

    val repository: SessionRepository =
        Proxy.newProxyInstance(
            SessionRepository::class.java.classLoader,
            arrayOf(SessionRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getSession" -> {
                    val id = args[0] as String
                    fetched += id
                    if (id in failing) throw SessionError.Http(404, "missing $id")
                    sessions[id] ?: throw SessionError.Http(404, "missing $id")
                }
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "SessionLookupFake"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SessionRepository
}
