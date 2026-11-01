package dev.opencode.mobile.domain.shell

/** Payloads captured from opencode v2.2.2 (placeholder paths), shared by the shell tests. */
internal object AgentShellFixtures {
    const val RUNNING_ID = "sh_197d5e595001Fhi2rsSX7g6Gia"
    const val EXITED_ID = "sh_197d5ee10001pAROBiMUStdqpO"
    const val COMMAND = "for i in \$(seq 1 40); do echo line \$i; sleep 0.5; done"

    fun shellInfo(
        id: String = RUNNING_ID,
        sessionId: String = "ses_test",
        status: String = "running",
    ): String =
        """{"id":"$id","status":"$status","command":"for i in ${'$'}(seq 1 40); do echo line ${'$'}i; sleep 0.5; done",""" +
            """"cwd":"/tmp/project","shell":"/bin/zsh","file":"/tmp/data/opencode/shell/global/$id.out","pid":11227,""" +
            """"metadata":{"sessionID":"$sessionId"},"time":{"started":1793548739990}}"""

    fun listBody(vararg infos: String): String = """{"location":{"directory":"/tmp/project"},"data":[${infos.joinToString(",")}]}"""

    fun outputBody(
        output: String,
        cursor: Long,
        size: Long,
        truncated: Boolean = false,
    ): String {
        val escaped = output.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"location":{"directory":"/tmp/project"},""" +
            """"data":{"output":"$escaped","cursor":$cursor,"size":$size,"truncated":$truncated}}"""
    }

    const val NOT_FOUND_BODY =
        """{"_tag":"ShellNotFoundError","id":"$RUNNING_ID","message":"Shell command not found: $RUNNING_ID"}"""

    fun createdEvent(info: String = shellInfo()): String =
        """{"id":"evt_1","created":1793548740025,"type":"shell.created","location":{"directory":"/tmp/project"},"data":{"info":$info}}"""

    const val EXITED_WITH_CODE_EVENT =
        """{"id":"evt_2","created":1793548742191,"type":"shell.exited","location":{"directory":"/tmp/project"},""" +
            """"data":{"id":"$EXITED_ID","exit":3,"status":"exited"}}"""

    const val KILLED_EVENT =
        """{"id":"evt_3","created":1793548743748,"type":"shell.exited","location":{"directory":"/tmp/project"},""" +
            """"data":{"id":"$RUNNING_ID","status":"killed"}}"""

    const val DELETED_EVENT =
        """{"id":"evt_4","created":1793548743748,"type":"shell.deleted","location":{"directory":"/tmp/project"},""" +
            """"data":{"id":"$RUNNING_ID"}}"""
}
