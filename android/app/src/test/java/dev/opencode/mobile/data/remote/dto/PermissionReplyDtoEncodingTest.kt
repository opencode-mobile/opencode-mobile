package dev.opencode.mobile.data.remote.dto

import dev.opencode.mobile.di.NetworkModule
import org.junit.Assert.assertEquals
import org.junit.Test

/** The app's request JSON: `message` goes out only with a deny note (v2 rejects unknown or null keys). */
class PermissionReplyDtoEncodingTest {
    private val json = NetworkModule.provideJson()

    @Test
    fun v1Reply_withoutMessage_omitsTheKey() {
        assertEquals(
            """{"reply":"reject"}""",
            json.encodeToString(PermissionResponseRequestDto.serializer(), PermissionResponseRequestDto(reply = "reject")),
        )
        assertEquals(
            """{"reply":"reject"}""",
            json.encodeToString(
                PermissionResponseRequestDto.serializer(),
                PermissionResponseRequestDto(reply = "reject", message = null),
            ),
        )
    }

    @Test
    fun v1Reply_withMessage_sendsIt() {
        assertEquals(
            """{"reply":"reject","message":"Run the tests first"}""",
            json.encodeToString(
                PermissionResponseRequestDto.serializer(),
                PermissionResponseRequestDto(reply = "reject", message = "Run the tests first"),
            ),
        )
    }

    @Test
    fun v2Reply_withoutMessage_omitsTheKey() {
        assertEquals(
            """{"decision":"reject"}""",
            json.encodeToString(V2PermissionReplyRequestDto.serializer(), V2PermissionReplyRequestDto(decision = "reject", message = null)),
        )
    }

    @Test
    fun v2Reply_withMessage_sendsIt() {
        assertEquals(
            """{"decision":"reject","message":"Use a branch instead"}""",
            json.encodeToString(
                V2PermissionReplyRequestDto.serializer(),
                V2PermissionReplyRequestDto(decision = "reject", message = "Use a branch instead"),
            ),
        )
    }
}
