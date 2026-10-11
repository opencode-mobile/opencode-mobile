package dev.opencode.mobile.domain.command

import dev.opencode.mobile.data.remote.dto.CommandDto
import dev.opencode.mobile.data.remote.dto.V2CommandDto
import dev.opencode.mobile.data.remote.dto.V2SkillDto
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
    fun toServerCommandsFromV2_mapsSource() {
        val commands =
            adapter.toServerCommandsFromV2(
                listOf(
                    V2CommandDto(name = "init", description = "Setup", source = "command"),
                    V2CommandDto(name = "prompt", source = "mcp"),
                    V2CommandDto(name = "skill-a", source = "skill"),
                ),
            )
        assertEquals(ServerCommandSource.Command, commands[0].source)
        assertEquals(ServerCommandSource.Mcp, commands[1].source)
        assertEquals(ServerCommandSource.Skill, commands[2].source)
    }

    @Test
    fun mergeV2SlashCatalog_appendsSkillsSkippingDuplicatesAndSlashFalse() {
        val commands =
            adapter.toServerCommandsFromV2(
                listOf(V2CommandDto(name = "init", description = "Setup", template = "x")),
            )
        val merged =
            adapter.mergeV2SlashCatalog(
                commands,
                listOf(
                    V2SkillDto(name = "init", description = "dup", content = "ignored"),
                    V2SkillDto(name = "hidden", slash = false, content = "nope"),
                    V2SkillDto(
                        name = "prototype",
                        description = "A skill",
                        location = "/skills/prototype/SKILL.md",
                        content = "Do the thing",
                    ),
                ),
            )
        assertEquals(listOf("init", "prototype"), merged.map { it.name })
        assertEquals(ServerCommandSource.Skill, merged[1].source)
        assertEquals("prototype", merged[1].skillId)
        assertEquals("A skill", merged[1].description)
        assertTrue(merged[1].template!!.contains("Do the thing"))
        assertTrue(merged[1].template!!.contains("Base directory for this skill: /skills/prototype"))
    }

    @Test
    fun toSkillCommand_usesSkillIdNotDisplayName() {
        val command =
            adapter.toSkillCommand(
                V2SkillDto(
                    id = "opencode",
                    name = "OpenCode",
                    path = "/builtin/opencode.md",
                    content = "Ask about OpenCode",
                ),
            )
        assertEquals("OpenCode", command?.name)
        assertEquals("opencode", command?.skillId)
        assertEquals("Ask about OpenCode", command?.template)
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
        assertEquals("init", request.name)
        assertEquals("please", request.text)
        assertEquals("data:image/png;base64,abc", request.files?.single()?.uri)
        assertEquals("image/png", request.files?.single()?.mime)
        assertEquals("a.png", request.files?.single()?.name)
        assertEquals("steer", request.delivery)
        val model =
            adapter.toV2SwitchModelRequest(
                CommandRunOptions(
                    command = "init",
                    arguments = "please",
                    model = PromptModelSelection(providerId = "openai", modelId = "gpt-4"),
                    variant = "high",
                    mode = "build",
                ),
            )
        assertEquals("gpt-4", model?.model?.id)
        assertEquals("openai", model?.model?.providerId)
        assertEquals("high", model?.model?.variant)
    }
}
