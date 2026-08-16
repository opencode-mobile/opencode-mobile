package dev.opencode.mobile.domain.command

import dev.opencode.mobile.data.remote.dto.CommandDto
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.PromptModelSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandDomainAdapterTest {
    private val adapter = CommandDomainAdapter()

    @Test
    fun toServerCommands_mapsSources() {
        val commands =
            adapter.toServerCommands(
                listOf(
                    CommandDto(name = "init", description = "Setup", source = "command", template = "x", hints = emptyList()),
                    CommandDto(name = "prompt", description = null, source = "mcp", template = "x", hints = emptyList()),
                    CommandDto(name = "skill-a", description = "A skill", source = "skill", template = "x", hints = emptyList()),
                ),
            )
        assertEquals(ServerCommandSource.Command, commands[0].source)
        assertEquals(ServerCommandSource.Mcp, commands[1].source)
        assertEquals(ServerCommandSource.Skill, commands[2].source)
        assertEquals("Setup", commands[0].description)
    }

    @Test
    fun toRunRequest_mapsComposerSnapshotWithoutMessageId() {
        val request =
            adapter.toRunRequest(
                CommandRunOptions(
                    command = "init",
                    arguments = "please",
                    attachments =
                        listOf(
                            Attachment(
                                id = "att-1",
                                filename = "a.png",
                                mime = "image/png",
                                kind = AttachmentKind.Image,
                                dataUrl = "data:image/png;base64,abc",
                                byteCount = 3,
                            ),
                        ),
                    model = PromptModelSelection(providerId = "openai", modelId = "gpt-4"),
                    variant = "high",
                    mode = "build",
                ),
            )
        assertEquals("init", request.command)
        assertEquals("please", request.arguments)
        assertEquals("build", request.agent)
        assertEquals("openai/gpt-4", request.model)
        assertEquals("high", request.variant)
        assertEquals(1, request.parts.size)
        assertEquals("image/png", request.parts.single().mime)
        assertEquals("data:image/png;base64,abc", request.parts.single().url)
        assertTrue(request.parts.single().id == null)
    }

    @Test
    fun toV2RunRequest_mapsModelFilesAndDelivery() {
        val request =
            adapter.toV2RunRequest(
                CommandRunOptions(
                    command = "init",
                    arguments = "please",
                    attachments =
                        listOf(
                            Attachment(
                                id = "att-1",
                                filename = "a.png",
                                mime = "image/png",
                                kind = AttachmentKind.Image,
                                dataUrl = "data:image/png;base64,abc",
                                byteCount = 3,
                            ),
                        ),
                    model = PromptModelSelection(providerId = "openai", modelId = "gpt-4"),
                    variant = "high",
                    mode = "build",
                ),
            )
        assertEquals("init", request.command)
        assertEquals("please", request.arguments)
        assertEquals("build", request.agent)
        assertEquals("gpt-4", request.model?.id)
        assertEquals("openai", request.model?.providerId)
        assertEquals("high", request.model?.variant)
        assertEquals("data:image/png;base64,abc", request.files?.single()?.uri)
        assertEquals("steer", request.delivery)
    }
}
