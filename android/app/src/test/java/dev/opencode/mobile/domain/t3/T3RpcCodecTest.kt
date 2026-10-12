package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.session.PermissionDecision
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T3RpcCodecTest {
    @Test
    fun encodeRequest_emitsMessageDispatchFrame() {
        val payload =
            T3Commands.messageDispatch(
                threadId = "thr-1",
                text = "Hello",
                modelSelection = T3ModelSelection(instanceId = "inst-1", model = "gpt"),
                running = false,
                messageId = "msg-1",
                commandId = "cmd-1",
            )
        val encoded = T3RpcCodec.encodeRequest("req-1", T3RpcCodec.DISPATCH_COMMAND, payload)
        val obj = T3Json.parseObject(encoded)
        assertEquals("Request", obj["_tag"]!!.jsonPrimitive.content)
        assertEquals("req-1", obj["id"]!!.jsonPrimitive.content)
        assertEquals(T3RpcCodec.DISPATCH_COMMAND, obj["tag"]!!.jsonPrimitive.content)
        assertEquals("message.dispatch", obj["payload"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("thr-1", obj["payload"]!!.jsonObject["threadId"]!!.jsonPrimitive.content)
        assertTrue(obj.containsKey("headers"))
    }

    @Test
    fun messageDispatch_includesRequiredCreationFields() {
        val payload =
            T3Commands.messageDispatch(
                threadId = "thr-1",
                text = "Hello",
                modelSelection = null,
                running = false,
                messageId = "msg-1",
                commandId = "cmd-1",
            )
        assertEquals("user", payload["createdBy"]!!.jsonPrimitive.content)
        assertEquals("mobile", payload["creationSource"]!!.jsonPrimitive.content)
        val dispatchMode = payload["dispatchMode"]!!.jsonObject
        assertEquals("start_immediately", dispatchMode["type"]!!.jsonPrimitive.content)
        assertTrue(payload.containsKey("attachments"))
        assertTrue(!payload.containsKey("modelSelection"))
    }

    @Test
    fun launchThread_includesCreationSource() {
        val payload =
            T3Commands.launchThread(
                projectId = "proj-1",
                title = "New thread",
                modelSelection = T3ModelSelection(instanceId = "inst-1", model = "gpt"),
                runtimeMode = "full-access",
                commandId = "cmd-1",
            )
        assertEquals("mobile", payload["creationSource"]!!.jsonPrimitive.content)
        assertEquals("proj-1", payload["projectId"]!!.jsonPrimitive.content)
        assertEquals("full-access", payload["runtimeMode"]!!.jsonPrimitive.content)
        assertEquals("default", payload["interactionMode"]!!.jsonPrimitive.content)
    }

    @Test
    fun runtimeRequestRespond_mapsAllowOnceToAccept() {
        val payload =
            T3Commands.runtimeRequestRespond(
                threadId = "thr-1",
                requestId = "req-approval",
                decision = PermissionDecision.AllowOnce,
                commandId = "cmd-1",
            )
        assertEquals("runtime-request.respond", payload["type"]!!.jsonPrimitive.content)
        assertEquals("accept", payload["decision"]!!.jsonPrimitive.content)
        assertEquals("req-approval", payload["requestId"]!!.jsonPrimitive.content)
        assertEquals(
            "acceptForSession",
            T3Commands.approvalWire(PermissionDecision.AlwaysAllow),
        )
        assertEquals("decline", T3Commands.approvalWire(PermissionDecision.Deny))
    }

    @Test
    fun parse_exitSuccess() {
        val frame =
            T3RpcCodec.parse(
                """{"_tag":"Exit","requestId":"req-1","exit":{"_tag":"Success","value":{"ok":true}}}""",
            )
        val success = frame as T3RpcFrame.ExitSuccess
        assertEquals("req-1", success.requestId)
        assertEquals(true, success.value.jsonObject["ok"]!!.jsonPrimitive.content.toBoolean())
    }
}
