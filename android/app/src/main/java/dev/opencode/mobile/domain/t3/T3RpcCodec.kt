package dev.opencode.mobile.domain.t3

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

sealed interface T3RpcFrame {
    data class Request(
        val id: String,
        val tag: String,
        val payload: JsonObject,
    ) : T3RpcFrame

    data class ExitSuccess(
        val requestId: String,
        val value: JsonElement,
    ) : T3RpcFrame

    data class ExitFailure(
        val requestId: String,
        val cause: JsonElement,
    ) : T3RpcFrame

    data class Chunk(
        val requestId: String,
        val values: List<JsonElement>,
    ) : T3RpcFrame

    data class Ping(
        val fromServer: Boolean,
    ) : T3RpcFrame

    data class Pong(
        val fromServer: Boolean,
    ) : T3RpcFrame

    data class Unknown(
        val raw: JsonObject,
    ) : T3RpcFrame
}

object T3RpcCodec {
    const val DISPATCH_COMMAND = "orchestration.dispatchCommand"
    const val SUBSCRIBE_THREAD = "orchestration.subscribeThread"
    const val SUBSCRIBE_SHELL = "orchestration.subscribeShell"
    const val LAUNCH_THREAD = "orchestration.launchThread"

    fun encodeRequest(
        id: String,
        tag: String,
        payload: JsonObject,
    ): String =
        T3Json.parser.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("_tag", "Request")
                put("id", id)
                put("tag", tag)
                put("payload", payload)
                put("headers", JsonArray(emptyList()))
            },
        )

    fun encodeAck(requestId: String): String =
        T3Json.parser.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("_tag", "Ack")
                put("requestId", requestId)
            },
        )

    fun encodePing(): String =
        T3Json.parser.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("_tag", "Ping") },
        )

    fun encodePong(): String =
        T3Json.parser.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("_tag", "Pong") },
        )

    fun parse(raw: String): T3RpcFrame {
        val obj = T3Json.parser.parseToJsonElement(raw).jsonObject
        return when (obj.tag()) {
            "Request" ->
                T3RpcFrame.Request(
                    id = obj.string("id").orEmpty(),
                    tag = obj.string("tag").orEmpty(),
                    payload = obj["payload"] as? JsonObject ?: JsonObject(emptyMap()),
                )
            "Exit" -> parseExit(obj)
            "Chunk" ->
                T3RpcFrame.Chunk(
                    requestId = obj.string("requestId").orEmpty(),
                    values = (obj["values"] as? JsonArray)?.toList().orEmpty(),
                )
            "Ping" -> T3RpcFrame.Ping(fromServer = true)
            "Pong" -> T3RpcFrame.Pong(fromServer = true)
            "Ack" -> T3RpcFrame.Unknown(obj)
            else -> T3RpcFrame.Unknown(obj)
        }
    }

    private fun parseExit(obj: JsonObject): T3RpcFrame {
        val requestId = obj.string("requestId").orEmpty()
        val exit = obj["exit"] as? JsonObject ?: JsonObject(emptyMap())
        return when (exit.tag()) {
            "Success" -> T3RpcFrame.ExitSuccess(requestId, exit["value"] ?: JsonNull)
            "Failure" -> T3RpcFrame.ExitFailure(requestId, exit["cause"] ?: JsonArray(emptyList()))
            else -> T3RpcFrame.ExitFailure(requestId, exit)
        }
    }

    private fun JsonObject.tag(): String = string("_tag").orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
