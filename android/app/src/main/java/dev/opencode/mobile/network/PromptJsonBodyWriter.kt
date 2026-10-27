package dev.opencode.mobile.network

import dev.opencode.mobile.data.remote.dto.RunCommandRequestDto
import dev.opencode.mobile.data.remote.dto.SendMessageRequestDto
import dev.opencode.mobile.data.remote.dto.V2PromptRequestDto
import dev.opencode.mobile.data.remote.dto.V2PromptSkillDto
import dev.opencode.mobile.data.remote.dto.V2RunCommandRequestDto
import dev.opencode.mobile.domain.message.Attachment
import java.io.File
import java.util.UUID

object PromptJsonBodyWriter {
    fun writeV1Prompt(
        body: SendMessageRequestDto,
        attachments: List<Attachment>,
        directory: File,
    ): File =
        write(directory) { writer ->
            writer.beginObject()
            writer.key("parts")
            writer.beginArray()
            var fileIndex = 0
            body.parts.forEach { part ->
                writer.beginObject()
                writer.key("type")
                writer.writeString(part.type)
                part.text?.let {
                    writer.key("text")
                    writer.writeString(it)
                }
                part.mime?.let {
                    writer.key("mime")
                    writer.writeString(it)
                }
                part.url?.let {
                    writer.key("url")
                    if (part.type == "file" && part.fileSource == null) {
                        writeFileUri(attachments, fileIndex, it, part.mime, writer)
                        fileIndex += 1
                    } else {
                        writer.writeString(it)
                    }
                } ?: run {
                    if (part.type == "file" && part.fileSource == null) {
                        writer.key("url")
                        writeFileUri(attachments, fileIndex, "", part.mime, writer)
                        fileIndex += 1
                    }
                }
                part.filename?.let {
                    writer.key("filename")
                    writer.writeString(it)
                }
                part.name?.let {
                    writer.key("name")
                    writer.writeString(it)
                }
                part.fileSource?.let { source ->
                    writer.key("source")
                    writer.beginObject()
                    writer.key("type")
                    writer.writeString("file")
                    writer.key("path")
                    writer.writeString(source.path)
                    writer.key("text")
                    writer.beginObject()
                    writer.key("value")
                    writer.writeString(source.value)
                    writer.key("start")
                    writer.writeInt(source.start)
                    writer.key("end")
                    writer.writeInt(source.end)
                    writer.endObject()
                    writer.endObject()
                } ?: part.source?.let { source ->
                    writer.key("source")
                    writer.beginObject()
                    writer.key("value")
                    writer.writeString(source.value)
                    writer.key("start")
                    writer.writeInt(source.start)
                    writer.key("end")
                    writer.writeInt(source.end)
                    writer.endObject()
                }
                writer.endObject()
            }
            writer.endArray()
            body.model?.let { model ->
                writer.key("model")
                writer.beginObject()
                writer.key("providerID")
                writer.writeString(model.providerId)
                writer.key("modelID")
                writer.writeString(model.modelId)
                writer.endObject()
            }
            body.variant?.let {
                writer.key("variant")
                writer.writeString(it)
            }
            body.agent?.let {
                writer.key("agent")
                writer.writeString(it)
            }
            body.messageId?.let {
                writer.key("messageID")
                writer.writeString(it)
            }
            writer.endObject()
        }

    fun writeV2Prompt(
        body: V2PromptRequestDto,
        attachments: List<Attachment>,
        directory: File,
    ): File =
        write(directory) { writer ->
            writer.beginObject()
            body.id?.let {
                writer.key("id")
                writer.writeString(it)
            }
            writer.key("text")
            writer.writeString(body.text)
            val mentionFiles = body.fileMentions.orEmpty()
            val attachmentFiles = body.files.orEmpty()
            if (attachmentFiles.isNotEmpty() || mentionFiles.isNotEmpty()) {
                writer.key("files")
                writer.beginArray()
                attachmentFiles.forEachIndexed { index, file ->
                    writer.beginObject()
                    writer.key("uri")
                    writeFileUri(attachments, index, file.uri, file.mime, writer)
                    file.name?.let {
                        writer.key("name")
                        writer.writeString(it)
                    }
                    writer.endObject()
                }
                mentionFiles.forEach { file ->
                    writer.beginObject()
                    writer.key("uri")
                    writer.writeString(file.uri)
                    writer.key("name")
                    writer.writeString(file.name)
                    writer.key("mention")
                    writer.beginObject()
                    file.mention.start?.let {
                        writer.key("start")
                        writer.writeInt(it)
                    }
                    file.mention.end?.let {
                        writer.key("end")
                        writer.writeInt(it)
                    }
                    file.mention.text?.let {
                        writer.key("text")
                        writer.writeString(it)
                    }
                    writer.endObject()
                    writer.endObject()
                }
                writer.endArray()
            }
            body.agents?.takeIf { it.isNotEmpty() }?.let { agents ->
                writer.key("agents")
                writer.beginArray()
                agents.forEach { agent ->
                    writer.beginObject()
                    writer.key("name")
                    writer.writeString(agent.name)
                    agent.mention?.let { mention ->
                        writer.key("mention")
                        writer.beginObject()
                        mention.start?.let {
                            writer.key("start")
                            writer.writeInt(it)
                        }
                        mention.end?.let {
                            writer.key("end")
                            writer.writeInt(it)
                        }
                        mention.text?.let {
                            writer.key("text")
                            writer.writeString(it)
                        }
                        writer.endObject()
                    }
                    writer.endObject()
                }
                writer.endArray()
            }
            writeSkills(body.skills, writer)
            body.metadata?.let {
                writer.key("metadata")
                writer.writeRawJson(it.toString())
            }
            writer.key("delivery")
            writer.writeString(body.delivery)
            writer.endObject()
        }

    fun writeV1Command(
        body: RunCommandRequestDto,
        attachments: List<Attachment>,
        directory: File,
    ): File =
        write(directory) { writer ->
            writer.beginObject()
            writer.key("command")
            writer.writeString(body.command)
            writer.key("arguments")
            writer.writeString(body.arguments)
            body.agent?.let {
                writer.key("agent")
                writer.writeString(it)
            }
            body.model?.let {
                writer.key("model")
                writer.writeString(it)
            }
            body.variant?.let {
                writer.key("variant")
                writer.writeString(it)
            }
            if (body.parts.isNotEmpty()) {
                writer.key("parts")
                writer.beginArray()
                body.parts.forEachIndexed { index, part ->
                    writer.beginObject()
                    writer.key("type")
                    writer.writeString(part.type)
                    writer.key("mime")
                    writer.writeString(part.mime)
                    writer.key("url")
                    writeFileUri(attachments, index, part.url, part.mime, writer)
                    part.filename?.let {
                        writer.key("filename")
                        writer.writeString(it)
                    }
                    writer.endObject()
                }
                writer.endArray()
            }
            body.messageId?.let {
                writer.key("messageID")
                writer.writeString(it)
            }
            writer.endObject()
        }

    fun writeV2Command(
        body: V2RunCommandRequestDto,
        attachments: List<Attachment>,
        directory: File,
    ): File =
        write(directory) { writer ->
            writer.beginObject()
            writer.key("name")
            writer.writeString(body.name)
            writer.key("text")
            writer.writeString(body.text)
            body.files?.takeIf { it.isNotEmpty() }?.let { files ->
                writer.key("files")
                writer.beginArray()
                files.forEachIndexed { index, file ->
                    writer.beginObject()
                    writer.key("uri")
                    writeFileUri(attachments, index, file.uri, file.mime, writer)
                    file.name?.let {
                        writer.key("name")
                        writer.writeString(it)
                    }
                    writer.endObject()
                }
                writer.endArray()
            }
            writeSkills(body.skills, writer)
            writer.key("delivery")
            writer.writeString(body.delivery)
            writer.endObject()
        }

    private fun writeSkills(
        skills: List<V2PromptSkillDto>?,
        writer: JsonStreamWriter,
    ) {
        val present = skills?.takeIf { it.isNotEmpty() } ?: return
        writer.key("skills")
        writer.beginArray()
        present.forEach { skill ->
            writer.beginObject()
            writer.key("id")
            writer.writeString(skill.id)
            skill.mention?.let { mention ->
                writer.key("mention")
                writer.beginObject()
                mention.start?.let {
                    writer.key("start")
                    writer.writeInt(it)
                }
                mention.end?.let {
                    writer.key("end")
                    writer.writeInt(it)
                }
                mention.text?.let {
                    writer.key("text")
                    writer.writeString(it)
                }
                writer.endObject()
            }
            writer.endObject()
        }
        writer.endArray()
    }

    private fun write(
        directory: File,
        body: (JsonStreamWriter) -> Unit,
    ): File {
        directory.mkdirs()
        val file = File(directory, "prompt-send-${UUID.randomUUID()}.json")
        file.outputStream().use { output ->
            body(JsonStreamWriter(output))
        }
        return file
    }

    private fun writeFileUri(
        attachments: List<Attachment>,
        index: Int,
        fallback: String,
        mime: String?,
        writer: JsonStreamWriter,
    ) {
        val attachment = attachments.getOrNull(index)
        val path = attachment?.localPath
        if (path != null) {
            writer.writeDataUrl(mime ?: attachment.mime, File(path))
        } else if (fallback.isNotEmpty()) {
            writer.writeDataUrlString(fallback)
        } else if (!attachment?.dataUrl.isNullOrEmpty()) {
            writer.writeDataUrlString(attachment.dataUrl)
        } else {
            writer.writeString("")
        }
    }
}
